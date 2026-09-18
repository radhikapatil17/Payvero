package dev.payvero.fraud;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.audit.AuditLogEntry;
import dev.payvero.audit.AuditLogRepository;
import dev.payvero.audit.OutboxEvent;
import dev.payvero.audit.OutboxEventRepository;
import dev.payvero.audit.OutboxPublisher;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.accounting.JournalEntryRepository;
import dev.payvero.accounting.AccountingService;
import dev.payvero.transfer.Transfer;
import dev.payvero.transfer.TransferRepository;
import dev.payvero.transfer.TransferService;
import dev.payvero.transfer.TransferStatus;
import dev.payvero.transfer.dto.CreateTransferRequest;
import dev.payvero.transfer.dto.TransferResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * The outbox scheduler is off so events only flow when a test publishes them.
 * Rule evaluation is otherwise driven directly, which is what makes the
 * threshold boundary observable to the exact transfer rather than approximately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payvero.outbox.enabled=false",
        "payvero.settlement.enabled=false",
        "payvero.transfer.rate-limit-per-window=1000",
        "payvero.fraud.window=PT1M",
        "payvero.fraud.max-transfers-per-window=5",
        "payvero.fraud.max-amount-per-window=500000"
})
class FraudDetectionIntegrationTest {

    private static final int MAX_TRANSFERS = 5;

    @Autowired
    private FraudDetectionService fraudDetection;

    @Autowired
    private FraudVelocityTracker velocity;

    @Autowired
    private FraudReviewService reviewService;

    @Autowired
    private FraudFlagRepository flags;

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferRepository transfers;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private AccountingService ledgerService;

    @Autowired
    private JournalEntryRepository ledgerEntries;

    @Autowired
    private AccountService accountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    private UUID aliceUserId;
    private UUID aliceId;
    private UUID bobId;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        User alice = userRepository.saveAndFlush(new User("alice@payvero.dev", "hash", Role.USER));
        User bob = userRepository.saveAndFlush(new User("bob@payvero.dev", "hash", Role.USER));
        User admin = userRepository.saveAndFlush(new User("admin@payvero.dev", "hash", Role.ADMIN));
        aliceUserId = alice.getId();
        adminId = admin.getId();
        aliceId = accountService.openAccount(aliceUserId, "USD").getId();
        bobId = accountService.openAccount(bob.getId(), "USD").getId();
        transferService.deposit(aliceUserId, aliceId, 5_000_000, null);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM fraud_flags");
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

        // Velocity counters live in Redis and outlive the database reset, so a
        // burst from one test would otherwise leak into the next one's counts.
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ---------------------------------------------------------------
    // Threshold boundary
    // ---------------------------------------------------------------

    /**
     * Exactly the configured maximum is still acceptable. A test that fires far
     * past the limit would pass against an implementation whose boundary is off
     * by one, so the interesting assertion is the transfer that does not flag.
     */
    @Test
    void reachingTheLimitExactlyDoesNotFlag() {
        for (int i = 0; i < MAX_TRANSFERS; i++) {
            assertThat(evaluateNewTransfer(1_000)).isEmpty();
        }

        assertThat(flags.count()).isZero();
        assertThat(transfers.countByStatus(TransferStatus.FLAGGED)).isZero();
    }

    /** The very next one crosses it, and it is that transfer that carries the flag. */
    @Test
    void theTransferThatExceedsTheLimitIsTheOneFlagged() {
        for (int i = 0; i < MAX_TRANSFERS; i++) {
            assertThat(evaluateNewTransfer(1_000)).isEmpty();
        }

        UUID crossing = newTransfer(1_000);
        assertThat(fraudDetection.evaluate(crossing)).containsExactly(FraudRule.VELOCITY_COUNT);

        List<FraudFlag> raised = flags.findAllByTransferId(crossing);
        assertThat(raised).hasSize(1);
        assertThat(raised.getFirst().getRule()).isEqualTo(FraudRule.VELOCITY_COUNT);
        assertThat(raised.getFirst().getStatus()).isEqualTo(FraudFlagStatus.OPEN);
        assertThat(raised.getFirst().getDetails()).contains("observedTransferCount");

        assertThat(transfers.findById(crossing).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.FLAGGED);
    }

    @Test
    void theAmountRuleTripsIndependentlyOfTheCountRule() {
        // Two transfers, well under the count limit, but past the amount ceiling.
        assertThat(evaluateNewTransfer(300_000)).isEmpty();

        UUID crossing = newTransfer(300_000);
        assertThat(fraudDetection.evaluate(crossing)).containsExactly(FraudRule.VELOCITY_AMOUNT);
        assertThat(flags.findAllByTransferId(crossing)).hasSize(1);
    }

    /** Funding has no user to attribute velocity to, so it must not count. */
    @Test
    void treasuryFundedMovementsAreNotAttributedToAUser() {
        for (int i = 0; i < 10; i++) {
            TransferResponse deposit = transferService.deposit(aliceUserId, aliceId, 1_000, null);
            assertThat(fraudDetection.evaluate(deposit.id())).isEmpty();
        }
        assertThat(flags.count()).isZero();
    }

    // ---------------------------------------------------------------
    // Sliding window, not a fixed bucket
    // ---------------------------------------------------------------

    /**
     * The distinguishing test. Two events two seconds apart, deliberately
     * straddling a wall-clock minute boundary. A counter keyed by the current
     * minute would file them in different buckets and report one each; a sliding
     * window sees both, because they are two seconds apart.
     */
    @Test
    void aWindowFollowsTheClockRatherThanResettingOnBucketBoundaries() {
        UUID user = UUID.randomUUID();
        Instant justBeforeTheMinute = Instant.parse("2026-01-01T10:59:59Z");
        Instant justAfterTheMinute = Instant.parse("2026-01-01T11:00:01Z");

        assertThat(velocity.observeAt(user, UUID.randomUUID(), 100, justBeforeTheMinute).transferCount())
                .isEqualTo(1);
        assertThat(velocity.observeAt(user, UUID.randomUUID(), 100, justAfterTheMinute).transferCount())
                .isEqualTo(2);
    }

    /** And anything genuinely older than the window drops out of it. */
    @Test
    void observationsOlderThanTheWindowAgeOut() {
        UUID user = UUID.randomUUID();
        Instant start = Instant.parse("2026-01-01T12:00:00Z");

        velocity.observeAt(user, UUID.randomUUID(), 100, start);
        velocity.observeAt(user, UUID.randomUUID(), 100, start.plusSeconds(30));
        VelocitySnapshot beforeExpiry =
                velocity.observeAt(user, UUID.randomUUID(), 100, start.plusSeconds(59));
        assertThat(beforeExpiry.transferCount()).isEqualTo(3);
        assertThat(beforeExpiry.totalAmountMinorUnits()).isEqualTo(300);

        // 61 seconds in, the first observation is outside the minute.
        VelocitySnapshot afterExpiry =
                velocity.observeAt(user, UUID.randomUUID(), 100, start.plusSeconds(61));
        assertThat(afterExpiry.transferCount()).isEqualTo(3);
        assertThat(afterExpiry.totalAmountMinorUnits()).isEqualTo(300);
    }

    // ---------------------------------------------------------------
    // Flagging must not touch money
    // ---------------------------------------------------------------

    /**
     * A flag is a marker for review, not a correction. Money that moved stays
     * moved; reversing it would be a new opposing transfer, never an edit to
     * entries that are append-only by construction.
     */
    @Test
    void flaggingMovesNoMoneyAndRewritesNoHistory() {
        for (int i = 0; i < MAX_TRANSFERS; i++) {
            evaluateNewTransfer(1_000);
        }

        long entriesBefore = ledgerEntries.count();
        long aliceBefore = ledgerService.derivedBalance(aliceId);
        long bobBefore = ledgerService.derivedBalance(bobId);

        UUID crossing = newTransfer(1_000);
        assertThat(fraudDetection.evaluate(crossing)).isNotEmpty();

        // The flagged transfer's own money moved, as it should have: it was
        // accepted before the rule ever ran. What must not change is anything
        // the flag itself touched.
        assertThat(ledgerEntries.count()).isEqualTo(entriesBefore + 2);
        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(aliceBefore - 1_000);
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(bobBefore + 1_000);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();

        // Reviewing it changes the flag alone, not the ledger.
        UUID flagId = flags.findAllByTransferId(crossing).getFirst().getId();
        reviewService.review(flagId, FraudFlagStatus.CONFIRMED, adminId);

        assertThat(ledgerEntries.count()).isEqualTo(entriesBefore + 2);
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(bobBefore + 1_000);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();
    }

    // ---------------------------------------------------------------
    // Redelivery and review
    // ---------------------------------------------------------------

    /** Events arrive at least once, so a second look must not raise a second flag. */
    @Test
    void reEvaluatingTheSameTransferRaisesOneFlag() {
        for (int i = 0; i < MAX_TRANSFERS; i++) {
            evaluateNewTransfer(1_000);
        }
        UUID crossing = newTransfer(1_000);

        assertThat(fraudDetection.evaluate(crossing)).containsExactly(FraudRule.VELOCITY_COUNT);
        assertThat(fraudDetection.evaluate(crossing)).isEmpty();
        assertThat(fraudDetection.evaluate(crossing)).isEmpty();

        assertThat(flags.findAllByTransferId(crossing)).hasSize(1);
    }

    @Test
    void aFlagIsReviewedOnceAndRecordsWhoDecided() {
        for (int i = 0; i < MAX_TRANSFERS; i++) {
            evaluateNewTransfer(1_000);
        }
        UUID crossing = newTransfer(1_000);
        fraudDetection.evaluate(crossing);
        UUID flagId = flags.findAllByTransferId(crossing).getFirst().getId();

        var cleared = reviewService.review(flagId, FraudFlagStatus.CLEARED, adminId);
        assertThat(cleared.status()).isEqualTo("CLEARED");
        assertThat(cleared.reviewedBy()).isEqualTo(adminId);
        assertThat(cleared.reviewedAt()).isNotNull();

        assertThatThrownBy(() -> reviewService.review(flagId, FraudFlagStatus.CONFIRMED, adminId))
                .isInstanceOf(FraudFlagAlreadyReviewedException.class);
    }

    // ---------------------------------------------------------------
    // Operator decisions are auditable
    // ---------------------------------------------------------------

    /**
     * An operator clearing a flag with no trace was the gap this closes: in a
     * system whose whole claim is auditability, the reviewer's own action was
     * the one thing not recorded.
     */
    @Test
    void clearingAFlagIsRecordedInTheAuditTrailNamingTheAdmin() {
        UUID flagId = raiseAFlag();
        long eventsBefore = outbox.count();

        reviewService.review(flagId, FraudFlagStatus.CLEARED, adminId);

        assertThat(outbox.count()).isEqualTo(eventsBefore + 1);
        OutboxEvent event = outbox.findAllByAggregateId(flagId).getFirst();
        assertThat(event.getAggregateType()).isEqualTo("FRAUD_FLAG");
        assertThat(event.getEventType()).isEqualTo("FRAUD_FLAG_CLEARED");
        assertThat(event.getPayload()).contains(adminId.toString()).contains("CLEARED");

        outboxPublisher.publishPending();
        awaitAuditRows(flagId, 1);
        AuditLogEntry recorded = auditLog.findAllByAggregateId(flagId).getFirst();
        assertThat(recorded.getEventType()).isEqualTo("FRAUD_FLAG_CLEARED");
        assertThat(recorded.getPayload()).contains(adminId.toString());
    }

    @Test
    void confirmingAFlagIsRecordedInTheAuditTrailNamingTheAdmin() {
        UUID flagId = raiseAFlag();

        reviewService.review(flagId, FraudFlagStatus.CONFIRMED, adminId);
        outboxPublisher.publishPending();

        awaitAuditRows(flagId, 1);
        assertThat(auditLog.findAllByAggregateId(flagId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getEventType()).isEqualTo("FRAUD_FLAG_CONFIRMED");
                    assertThat(row.getPayload()).contains(adminId.toString());
                });
    }

    /**
     * The mirror of aRejectedTransferLeavesNoEventBehind: a refused decision
     * must not leave an event claiming a decision was made.
     */
    @Test
    void aRefusedDecisionLeavesNoEventBehind() {
        UUID flagId = raiseAFlag();
        reviewService.review(flagId, FraudFlagStatus.CLEARED, adminId);
        long eventsAfterFirstDecision = outbox.count();

        assertThatThrownBy(() -> reviewService.review(flagId, FraudFlagStatus.CONFIRMED, adminId))
                .isInstanceOf(FraudFlagAlreadyReviewedException.class);

        assertThat(outbox.count()).isEqualTo(eventsAfterFirstDecision);
    }

    // ---------------------------------------------------------------
    // End to end through Kafka
    // ---------------------------------------------------------------

    @Test
    void aBurstPublishedThroughKafkaReachesTheDetector() {
        for (int i = 0; i < MAX_TRANSFERS + 1; i++) {
            newTransfer(1_000);
        }

        // Nothing has been evaluated yet: detection is asynchronous by design.
        assertThat(flags.count()).isZero();

        outboxPublisher.publishPending();

        awaitFlag();
        assertThat(flags.countByStatus(FraudFlagStatus.OPEN)).isPositive();
        assertThat(transfers.countByStatus(TransferStatus.FLAGGED)).isPositive();
    }

    // ---------------------------------------------------------------

    private UUID newTransfer(long amount) {
        return transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, amount), null).id();
    }

    private List<FraudRule> evaluateNewTransfer(long amount) {
        return fraudDetection.evaluate(newTransfer(amount));
    }

    /** Drives the velocity rule past its limit and returns the resulting flag. */
    private UUID raiseAFlag() {
        for (int i = 0; i < MAX_TRANSFERS; i++) {
            evaluateNewTransfer(1_000);
        }
        UUID crossing = newTransfer(1_000);
        fraudDetection.evaluate(crossing);
        return flags.findAllByTransferId(crossing).getFirst().getId();
    }

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

    private void awaitFlag() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (flags.count() > 0) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("no fraud flag was raised within the deadline");
    }
}
