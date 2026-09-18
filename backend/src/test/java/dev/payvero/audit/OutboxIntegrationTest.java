package dev.payvero.audit;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.accounting.InsufficientFundsException;
import dev.payvero.transfer.TransferService;
import dev.payvero.transfer.dto.CreateTransferRequest;
import dev.payvero.transfer.dto.TransferResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * The outbox publisher's schedule is off, so this test controls exactly when
 * publication happens. That is what makes "the broker was unreachable" a state
 * the test can actually observe rather than a race it has to hope for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payvero.outbox.enabled=false",
        "payvero.settlement.enabled=false",
        "payvero.transfer.rate-limit-per-window=1000"
})
class OutboxIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private AuditTrailConsumer auditTrailConsumer;

    @Autowired
    private AccountService accountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceUserId;
    private UUID aliceId;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        User alice = userRepository.saveAndFlush(new User("alice@payvero.dev", "hash", Role.USER));
        User bob = userRepository.saveAndFlush(new User("bob@payvero.dev", "hash", Role.USER));
        aliceUserId = alice.getId();
        aliceId = accountService.openAccount(aliceUserId, "USD").getId();
        bobId = accountService.openAccount(bob.getId(), "USD").getId();
    }

    @AfterEach
    void tearDown() {
        // audit_log and ledger_entries both reject row level deletes, so both
        // are cleared with TRUNCATE, which fires no row trigger.
        jdbcTemplate.execute("TRUNCATE audit_log");
        jdbcTemplate.execute("TRUNCATE ledger_entries");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type = 'USER'");
        jdbcTemplate.update("UPDATE accounts SET cached_balance = 0, version = 0 WHERE id = ?",
                Account.TREASURY_ID);
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void theEventIsWrittenInTheSameTransactionAsTheTransfer() {
        TransferResponse deposit = transferService.deposit(aliceUserId, aliceId, 10_000, null);

        List<OutboxEvent> events = outbox.findAllByAggregateId(deposit.id());
        assertThat(events).hasSize(1);

        OutboxEvent event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("TRANSFER");
        assertThat(event.getEventType()).isEqualTo("TRANSFER_CREATED");
        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPayload()).contains(deposit.id().toString());
    }

    /**
     * The other half of the atomicity claim. A transfer that never committed
     * must leave no event, or a consumer would learn about money that did not
     * move. Both live in one transaction, so both roll back together.
     */
    @Test
    void aRejectedTransferLeavesNoEventBehind() {
        transferService.deposit(aliceUserId, aliceId, 1_000, null);
        long eventsAfterDeposit = outbox.count();

        assertThatThrownBy(() -> transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, 500_000), null))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(outbox.count()).isEqualTo(eventsAfterDeposit);
    }

    /**
     * The reason the outbox exists. With the publisher stopped the event is
     * already durable in Postgres, so nothing is lost while the broker is
     * unreachable; the next poll delivers it.
     */
    @Test
    void anEventSurvivesAPublisherOutageAndIsDeliveredOnTheNextPoll() {
        TransferResponse deposit = transferService.deposit(aliceUserId, aliceId, 10_000, null);

        // Publisher is not running: the event is stranded but safe.
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);
        assertThat(auditLog.count()).isZero();

        // The publisher comes back.
        int published = outboxPublisher.publishPending();
        assertThat(published).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();

        OutboxEvent event = outbox.findAllByAggregateId(deposit.id()).getFirst();
        assertThat(event.isPublished()).isTrue();

        // And it reaches the audit trail through Kafka.
        awaitAuditRows(deposit.id(), 1);
        AuditLogEntry recorded = auditLog.findAllByAggregateId(deposit.id()).getFirst();
        assertThat(recorded.getEventId()).isEqualTo(event.getId());
        assertThat(recorded.getEventType()).isEqualTo("TRANSFER_CREATED");
        assertThat(recorded.getKafkaOffset()).isNotNull();
    }

    /**
     * The outbox promises at-least-once, never exactly-once, so a redelivered
     * event must be absorbed rather than duplicated.
     */
    @Test
    void replayingTheSameEventRecordsExactlyOneAuditRow() {
        EventEnvelope envelope = envelope(UUID.randomUUID());

        assertThat(auditTrailConsumer.record(envelope, "transfer.events", 0, 1L)).isTrue();
        assertThat(auditTrailConsumer.record(envelope, "transfer.events", 0, 1L)).isFalse();
        assertThat(auditTrailConsumer.record(envelope, "transfer.events", 0, 99L)).isFalse();

        assertThat(auditLog.count()).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfOneEventStillRecordOneAuditRow() throws Exception {
        EventEnvelope envelope = envelope(UUID.randomUUID());

        int threads = 8;
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger created = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    release.await();
                    if (auditTrailConsumer.record(envelope, "transfer.events", 0, 1L)) {
                        created.incrementAndGet();
                    }
                    return null;
                }));
            }
            release.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // The existence check alone would let several deliveries through here.
        // The unique constraint is what actually holds the line.
        assertThat(created.get()).isEqualTo(1);
        assertThat(auditLog.count()).isEqualTo(1);
    }

    @Test
    void auditRowsCannotBeRewrittenOrRemoved() {
        EventEnvelope envelope = envelope(UUID.randomUUID());
        auditTrailConsumer.record(envelope, "transfer.events", 0, 1L);
        UUID rowId = auditLog.findAll().getFirst().getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE audit_log SET event_type = 'REWRITTEN' WHERE id = ?", rowId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", rowId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(auditLog.count()).isEqualTo(1);
    }

    private EventEnvelope envelope(UUID eventId) {
        return new EventEnvelope(eventId, "TRANSFER", UUID.randomUUID(), "TRANSFER_CREATED",
                Instant.parse("2026-01-01T12:00:00Z"), "{\"note\":\"under test\"}");
    }

    /**
     * Kafka delivery is genuinely asynchronous with no hook to wait on, so this
     * polls to a deadline rather than sleeping a fixed guess.
     */
    private void awaitAuditRows(UUID aggregateId, int expected) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (auditLog.findAllByAggregateId(aggregateId).size() >= expected) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("no audit row for aggregate %s within the deadline".formatted(aggregateId));
    }
}
