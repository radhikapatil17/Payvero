package dev.payvero.statement;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.accounting.AccountingService;
import dev.payvero.statement.dto.StatementResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * History is inserted directly with chosen timestamps, because entries take
 * their {@code created_at} on persist and the table is append-only, so a
 * backdated period cannot be produced through the service. The rows are shaped
 * exactly as the transfer path writes them: a transfer plus one balanced pair.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payvero.outbox.enabled=false",
        "payvero.settlement.enabled=false"
})
class StatementServiceIntegrationTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final YearMonth MAY = YearMonth.of(2026, 5);

    @Autowired
    private StatementService statementService;

    @Autowired
    private StatementRepository statements;

    @Autowired
    private AccountingService ledgerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID aliceId;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        User alice = userRepository.saveAndFlush(new User("alice@payvero.dev", "hash", Role.USER));
        User bob = userRepository.saveAndFlush(new User("bob@payvero.dev", "hash", Role.USER));
        aliceId = accountService.openAccount(alice.getId(), "USD").getId();
        bobId = accountService.openAccount(bob.getId(), "USD").getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE statements");
        jdbcTemplate.execute("TRUNCATE audit_log");
        jdbcTemplate.execute("TRUNCATE journal_entries");
        jdbcTemplate.update("DELETE FROM fraud_flags");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type = 'USER'");
        jdbcTemplate.update("UPDATE accounts SET cached_balance = 0, version = 0 WHERE id = ?",
                Account.TREASURY_ID);
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    // ---------------------------------------------------------------
    // Derived from entries, never from the cache
    // ---------------------------------------------------------------

    /**
     * The test that matters most. A statement computed from
     * {@code cached_balance} would agree perfectly with a corrupted cache while
     * both disagreed with the entries, and would look internally consistent
     * while being wrong. Corrupting the cache before generating proves the
     * figures come from primary records.
     */
    @Test
    void totalsAreDerivedFromEntriesEvenWhenTheCachedBalanceIsCorrupted() {
        fund(aliceId, 100_000, at(MARCH, 2));
        transfer(aliceId, bobId, 20_000, at(MARCH, 10));

        // Wreck the cache by a large, obvious amount. The entries are untouched.
        jdbcTemplate.update("UPDATE accounts SET cached_balance = 999_999 WHERE id = ?", aliceId);
        assertThat(accountService.require(aliceId).getCachedBalance()).isEqualTo(999_999);
        // The corruption is real and the ledger notices it.
        assertThat(ledgerService.checkIntegrity().healthy()).isFalse();

        StatementResponse march = statementService.generate(aliceId, MARCH);

        // Entry-derived truth, not the 999_999 sitting in the cache.
        assertThat(march.openingBalanceMinorUnits()).isZero();
        assertThat(march.closingBalanceMinorUnits()).isEqualTo(80_000);
        assertThat(march.netMovementMinorUnits()).isEqualTo(80_000);
        assertThat(march.entryCount()).isEqualTo(2);
        // No line carries the corrupted figure either: the running balances were
        // walked from the derived opening, not seeded from the cache.
        assertThat(march.lineItems())
                .extracting(StatementLine::balanceAfterMinorUnits)
                .containsExactly(100_000L, 80_000L);
    }

    // ---------------------------------------------------------------
    // Continuity across consecutive periods
    // ---------------------------------------------------------------

    /**
     * Continuity is the whole point of a statement, and a single period proves
     * nothing about it. Three consecutive months, each with real movement in
     * both directions, and each opening balance must equal the previous
     * closing exactly.
     */
    @Test
    void eachPeriodOpensExactlyWhereThePreviousOneClosed() {
        // March: +100,000 then -20,000  -> closes at 80,000
        fund(aliceId, 100_000, at(MARCH, 2));
        transfer(aliceId, bobId, 20_000, at(MARCH, 20));
        // April: +50,000 then -10,000   -> closes at 120,000
        fund(aliceId, 50_000, at(APRIL, 5));
        transfer(aliceId, bobId, 10_000, at(APRIL, 25));
        // May: -30,000                  -> closes at 90,000
        transfer(aliceId, bobId, 30_000, at(MAY, 14));

        StatementResponse march = statementService.generate(aliceId, MARCH);
        StatementResponse april = statementService.generate(aliceId, APRIL);
        StatementResponse may = statementService.generate(aliceId, MAY);

        assertThat(march.openingBalanceMinorUnits()).isZero();
        assertThat(march.closingBalanceMinorUnits()).isEqualTo(80_000);

        assertThat(april.openingBalanceMinorUnits()).isEqualTo(march.closingBalanceMinorUnits());
        assertThat(april.closingBalanceMinorUnits()).isEqualTo(120_000);

        assertThat(may.openingBalanceMinorUnits()).isEqualTo(april.closingBalanceMinorUnits());
        assertThat(may.closingBalanceMinorUnits()).isEqualTo(90_000);

        // Every period is internally consistent too.
        for (StatementResponse statement : java.util.List.of(march, april, may)) {
            assertThat(statement.closingBalanceMinorUnits() - statement.openingBalanceMinorUnits())
                    .isEqualTo(statement.netMovementMinorUnits());
        }

        // And the final closing balance is the account's real balance today.
        assertThat(may.closingBalanceMinorUnits()).isEqualTo(ledgerService.derivedBalance(aliceId));

        assertThat(march.entryCount()).isEqualTo(2);
        assertThat(april.entryCount()).isEqualTo(2);
        assertThat(may.entryCount()).isEqualTo(1);
    }

    /**
     * A month with no activity still has to carry the balance forward, or the
     * chain breaks the first time someone does nothing for a month.
     */
    @Test
    void aQuietPeriodCarriesTheBalanceForwardUnchanged() {
        fund(aliceId, 40_000, at(MARCH, 3));

        StatementResponse march = statementService.generate(aliceId, MARCH);
        StatementResponse april = statementService.generate(aliceId, APRIL);
        StatementResponse may = statementService.generate(aliceId, MAY);

        assertThat(april.entryCount()).isZero();
        assertThat(april.openingBalanceMinorUnits()).isEqualTo(march.closingBalanceMinorUnits());
        assertThat(april.closingBalanceMinorUnits()).isEqualTo(40_000);
        assertThat(april.netMovementMinorUnits()).isZero();
        assertThat(may.openingBalanceMinorUnits()).isEqualTo(april.closingBalanceMinorUnits());
    }

    @Test
    void lineItemsCoverExactlyThePeriodAndWalkToTheClosingBalance() {
        fund(aliceId, 100_000, at(MARCH, 2));
        transfer(aliceId, bobId, 20_000, at(APRIL, 10));
        transfer(aliceId, bobId, 5_000, at(APRIL, 28));
        transfer(aliceId, bobId, 1_000, at(MAY, 1));

        StatementResponse april = statementService.generate(aliceId, APRIL);

        assertThat(april.entryCount()).isEqualTo(2);
        assertThat(april.openingBalanceMinorUnits()).isEqualTo(100_000);
        assertThat(april.closingBalanceMinorUnits()).isEqualTo(75_000);

        // Typed now, so the assertions are on values rather than on how Postgres
        // happened to reformat the stored document.
        List<StatementLine> lines = april.lineItems();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).occurredAt()).isEqualTo(at(APRIL, 10));
        assertThat(lines.get(1).occurredAt()).isEqualTo(at(APRIL, 28));

        // The running balance walks from the opening balance to the closing one;
        // a March or May entry leaking in would break that chain.
        assertThat(lines.get(0).balanceAfterMinorUnits()).isEqualTo(80_000);
        assertThat(lines.get(1).balanceAfterMinorUnits())
                .isEqualTo(april.closingBalanceMinorUnits());
    }

    // ---------------------------------------------------------------
    // Immutability and idempotency
    // ---------------------------------------------------------------

    @Test
    void regeneratingAPeriodReturnsTheSameRowWithTheSameFigures() {
        fund(aliceId, 60_000, at(MARCH, 4));

        StatementResponse first = statementService.generate(aliceId, MARCH);
        StatementResponse again = statementService.generate(aliceId, MARCH);
        StatementResponse third = statementService.generate(aliceId, MARCH);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(third.id()).isEqualTo(first.id());
        assertThat(again).isEqualTo(first);
        assertThat(statements.count()).isEqualTo(1);
    }

    /**
     * Regeneration must read, not recompute. If later entries could change an
     * already-issued statement, the document would not be a record of anything.
     */
    @Test
    void aStatementDoesNotChangeIfTheLedgerLaterGrows() {
        fund(aliceId, 60_000, at(MARCH, 4));
        StatementResponse original = statementService.generate(aliceId, MARCH);

        // A late entry lands inside an already-issued period.
        transfer(aliceId, bobId, 25_000, at(MARCH, 28));

        StatementResponse reread = statementService.generate(aliceId, MARCH);
        assertThat(reread).isEqualTo(original);
        assertThat(reread.closingBalanceMinorUnits()).isEqualTo(60_000);
        assertThat(statements.count()).isEqualTo(1);

        // The ledger itself of course reflects it; only the issued document does not.
        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(35_000);
    }

    @Test
    void issuedStatementsCannotBeRewrittenOrRemoved() {
        fund(aliceId, 60_000, at(MARCH, 4));
        UUID statementId = UUID.fromString(statementService.generate(aliceId, MARCH).id().toString());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE statements SET closing_balance = 1 WHERE id = ?", statementId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM statements WHERE id = ?", statementId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(statements.findById(statementId).orElseThrow().getClosingBalance())
                .isEqualTo(60_000);
    }

    @Test
    void aPeriodStillInProgressCannotBeStatemented() {
        YearMonth thisMonth = YearMonth.from(OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> statementService.generate(aliceId, thisMonth))
                .isInstanceOf(PeriodNotClosedException.class);

        assertThat(statements.count()).isZero();
    }

    // ---------------------------------------------------------------

    /** Money entering from the treasury, backdated. */
    private void fund(UUID accountId, long amount, Instant at) {
        writePair(Account.TREASURY_ID, accountId, amount, at);
    }

    private void transfer(UUID from, UUID to, long amount, Instant at) {
        writePair(from, to, amount, at);
    }

    /** One transfer and one balanced pair, exactly as the transfer path writes them. */
    private void writePair(UUID from, UUID to, long amount, Instant at) {
        UUID transferId = UUID.randomUUID();
        OffsetDateTime when = at.atOffset(ZoneOffset.UTC);

        jdbcTemplate.update("""
                INSERT INTO transfers (id, source_account_id, destination_account_id, amount,
                                       currency, status, created_at, settled_at, version)
                VALUES (?, ?, ?, ?, 'USD', 'SETTLED', ?, ?, 0)
                """, transferId, from, to, amount, when, when);

        jdbcTemplate.update("""
                INSERT INTO journal_entries (transfer_id, account_id, direction, amount, currency, created_at)
                VALUES (?, ?, 'DEBIT', ?, 'USD', ?)
                """, transferId, from, amount, when);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (transfer_id, account_id, direction, amount, currency, created_at)
                VALUES (?, ?, 'CREDIT', ?, 'USD', ?)
                """, transferId, to, amount, when);

        // Keep the cache in step, so a test that has not deliberately corrupted
        // it still sees a healthy ledger.
        jdbcTemplate.update("UPDATE accounts SET cached_balance = cached_balance - ? WHERE id = ?", amount, from);
        jdbcTemplate.update("UPDATE accounts SET cached_balance = cached_balance + ? WHERE id = ?", amount, to);
    }

    private static Instant at(YearMonth period, int dayOfMonth) {
        return period.atDay(dayOfMonth).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }
}
