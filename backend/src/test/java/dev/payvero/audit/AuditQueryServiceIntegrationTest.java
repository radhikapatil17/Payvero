package dev.payvero.audit;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.audit.dto.AuditLogResponse;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManagerFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit trail read as an operator reads it. What is under test is almost
 * entirely attribution: a row that says an id did something is not an audit
 * trail anybody can act on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payvero.outbox.enabled=false",
        "payvero.settlement.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class AuditQueryServiceIntegrationTest {

    @Autowired
    private AuditQueryService auditQueryService;

    @Autowired
    private AuditTrailConsumer auditTrailConsumer;

    @Autowired
    private AccountService accountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceUserId;
    private UUID adminUserId;
    private UUID aliceAccountId;
    private UUID bobAccountId;

    @BeforeEach
    void setUp() {
        User alice = userRepository.saveAndFlush(new User("alice@payvero.dev", "hash", Role.USER));
        User bob = userRepository.saveAndFlush(new User("bob@payvero.dev", "hash", Role.USER));
        User admin = userRepository.saveAndFlush(new User("admin@payvero.dev", "hash", Role.ADMIN));
        aliceUserId = alice.getId();
        adminUserId = admin.getId();
        aliceAccountId = accountService.openAccount(aliceUserId, "USD").getId();
        bobAccountId = accountService.openAccount(bob.getId(), "USD").getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE audit_log");
        jdbcTemplate.execute("TRUNCATE ledger_entries");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type = 'USER'");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void namesTheAdminWhoDecidedAFlagRatherThanTheirId() {
        record(UUID.randomUUID(), "FRAUD_FLAG", "FRAUD_FLAG_CONFIRMED", """
                {"flagId":"%s","transferId":"%s","rule":"VELOCITY_COUNT",
                 "decision":"CONFIRMED","reviewedBy":"%s","reviewedAt":"2026-08-01T10:00:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), adminUserId));

        AuditLogResponse row = firstRow();

        assertThat(row.eventType()).isEqualTo("FRAUD_FLAG_CONFIRMED");
        assertThat(row.actor()).isEqualTo("admin@payvero.dev");
    }

    @Test
    void namesTheSenderForATransferBetweenTwoUsers() {
        record(UUID.randomUUID(), "TRANSFER", "TRANSFER_CREATED", """
                {"transferId":"%s","sourceAccountId":"%s","destinationAccountId":"%s",
                 "amountMinorUnits":5000,"currency":"USD","status":"SETTLED",
                 "occurredAt":"2026-08-01T10:00:00Z"}
                """.formatted(UUID.randomUUID(), aliceAccountId, bobAccountId));

        assertThat(firstRow().actor()).isEqualTo("alice@payvero.dev");
    }

    /**
     * A deposit's source is the treasury, which nobody owns. Falling back to
     * the id, or to the string "treasury", would both be worse than naming the
     * person who actually initiated it.
     */
    @Test
    void namesTheRecipientForADepositWhoseSourceIsTheOwnerlessTreasury() {
        record(UUID.randomUUID(), "TRANSFER", "TRANSFER_CREATED", """
                {"transferId":"%s","sourceAccountId":"%s","destinationAccountId":"%s",
                 "amountMinorUnits":5000,"currency":"USD","status":"SETTLED",
                 "occurredAt":"2026-08-01T10:00:00Z"}
                """.formatted(UUID.randomUUID(), Account.TREASURY_ID, aliceAccountId));

        assertThat(firstRow().actor()).isEqualTo("alice@payvero.dev");
    }

    /**
     * Not every event has a person behind it. Null says so; a placeholder would
     * assert something the trail does not actually know.
     */
    @Test
    void leavesTheActorNullWhenTheEventNamesNobody() {
        record(UUID.randomUUID(), "TRANSFER", "TRANSFER_CREATED",
                "{\"note\":\"an event carrying no identity at all\"}");

        assertThat(firstRow().actor()).isNull();
    }

    @Test
    void returnsThePayloadAsAnObjectSoNoClientHasToParseAString() {
        record(UUID.randomUUID(), "TRANSFER", "TRANSFER_CREATED", """
                {"transferId":"%s","sourceAccountId":"%s","destinationAccountId":"%s",
                 "amountMinorUnits":5000,"currency":"USD","status":"SETTLED"}
                """.formatted(UUID.randomUUID(), aliceAccountId, bobAccountId));

        AuditLogResponse row = firstRow();

        assertThat(row.payload().isObject()).isTrue();
        assertThat(row.payload().get("amountMinorUnits").asInt()).isEqualTo(5000);
    }

    @Test
    void filtersToOneAggregateTypeWhenAsked() {
        record(UUID.randomUUID(), "TRANSFER", "TRANSFER_CREATED", "{}");
        record(UUID.randomUUID(), "FRAUD_FLAG", "FRAUD_FLAG_CLEARED", "{}");

        assertThat(auditQueryService.page(null, 0, 20).getTotalElements()).isEqualTo(2);

        Page<AuditLogResponse> flagsOnly = auditQueryService.page("FRAUD_FLAG", 0, 20);
        assertThat(flagsOnly.getTotalElements()).isEqualTo(1);
        assertThat(flagsOnly.getContent().getFirst().eventType()).isEqualTo("FRAUD_FLAG_CLEARED");
    }

    /**
     * The claim the batching comment makes, held to a number.
     *
     * Twenty rows naming two accounts and a reviewer each would be sixty
     * lookups done row by row. Counting statements is the only way to tell the
     * difference, because a correct page and an N+1 page look identical.
     */
    @Test
    void resolvesAWholePageWithoutAQueryPerRow() {
        for (int i = 0; i < 20; i++) {
            record(UUID.randomUUID(), "TRANSFER", "TRANSFER_CREATED", """
                    {"transferId":"%s","sourceAccountId":"%s","destinationAccountId":"%s",
                     "amountMinorUnits":%d,"currency":"USD","status":"SETTLED"}
                    """.formatted(UUID.randomUUID(), aliceAccountId, bobAccountId, 100 + i));
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Page<AuditLogResponse> page = auditQueryService.page(null, 0, 20);

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allSatisfy(row ->
                assertThat(row.actor()).isEqualTo("alice@payvero.dev"));

        // The page itself, its count, one batch of accounts, one batch of users,
        // and room for the lazy owner fetches Hibernate does per distinct
        // account. Nowhere near the sixty a per-row lookup would cost.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(10);
    }

    private void record(UUID eventId, String aggregateType, String eventType, String payload) {
        auditTrailConsumer.record(
                new EventEnvelope(eventId, aggregateType, UUID.randomUUID(), eventType,
                        Instant.parse("2026-08-01T10:00:00Z"), payload),
                "payvero.events", 0, 1L);
    }

    private AuditLogResponse firstRow() {
        return auditQueryService.page(null, 0, 20).getContent().getFirst();
    }
}
