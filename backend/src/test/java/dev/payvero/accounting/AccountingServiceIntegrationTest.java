package dev.payvero.accounting;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.transfer.TransferService;
import dev.payvero.transfer.dto.CreateTransferRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Not transactional: the integrity checks have to observe committed state, and
 * several tests deliberately corrupt the database behind the service's back to
 * prove the check would notice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AccountingServiceIntegrationTest {

    @Autowired
    private AccountingService ledgerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository ledgerEntryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceUserId;
    private UUID aliceId;
    private UUID bobUserId;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        User alice = userRepository.saveAndFlush(new User("alice@payvero.dev", "hash", Role.USER));
        User bob = userRepository.saveAndFlush(new User("bob@payvero.dev", "hash", Role.USER));
        aliceUserId = alice.getId();
        bobUserId = bob.getId();
        aliceId = accountService.openAccount(aliceUserId, "USD").getId();
        bobId = accountService.openAccount(bobUserId, "USD").getId();
    }

    @AfterEach
    void tearDown() {
        // TRUNCATE rather than DELETE for entries: the append-only trigger
        // rejects row level deletes, and TRUNCATE fires no row trigger. The
        // treasury is seeded by the migration, so it is reset rather than removed.
        jdbcTemplate.execute("TRUNCATE ledger_entries");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type = 'USER'");
        jdbcTemplate.update("UPDATE accounts SET cached_balance = 0, version = 0 WHERE id = ?",
                Account.TREASURY_ID);
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void aDepositIsTwoSidedAgainstTheTreasury() {
        UUID transferId = deposit(aliceUserId, aliceId, 10_000);

        List<JournalEntry> entries = ledgerEntryRepository.findAllByTransferId(transferId);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(JournalEntry::getDirection)
                .containsExactlyInAnyOrder(Direction.DEBIT, Direction.CREDIT);

        // The user gained exactly what the treasury gave up. Nothing was created
        // from nothing, which is the property a single sided write would break.
        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(10_000);
        assertThat(ledgerService.derivedBalance(Account.TREASURY_ID)).isEqualTo(-10_000);
        assertThat(ledgerEntryRepository.sumOfAllSignedEntries()).isZero();
    }

    @Test
    void everyMovementLeavesTheBooksBalancedAndTheCacheHonest() {
        deposit(aliceUserId, aliceId, 50_000);
        deposit(bobUserId, bobId, 25_000);
        transferService.createTransfer(aliceUserId, new CreateTransferRequest(aliceId, bobId, 7_500), null);
        transferService.withdraw(bobUserId, bobId, 2_500, null);

        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(42_500);
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(30_000);

        assertThat(accountRepository.findById(aliceId).orElseThrow().getCachedBalance())
                .isEqualTo(42_500);
        assertThat(accountRepository.findById(bobId).orElseThrow().getCachedBalance())
                .isEqualTo(30_000);

        IntegrityReport report = ledgerService.checkIntegrity();
        assertThat(report.healthy()).isTrue();
        assertThat(report.netOfAllEntries()).isZero();
        assertThat(report.balanceMismatches()).isEmpty();
        assertThat(report.unbalancedTransfers()).isEmpty();
    }

    @Test
    void integrityCheckCatchesADeliberatelyCorruptedCachedBalance() {
        deposit(aliceUserId, aliceId, 10_000);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();

        // Reach past the service and inflate the cached figure, exactly as a bug
        // in balance maintenance would. The entries are untouched, so the derived
        // balance is still the truth.
        jdbcTemplate.update("UPDATE accounts SET cached_balance = cached_balance + 5000 WHERE id = ?",
                aliceId);

        IntegrityReport report = ledgerService.checkIntegrity();

        assertThat(report.healthy()).isFalse();
        assertThat(report.balanceMismatches()).hasSize(1);
        IntegrityReport.BalanceMismatch mismatch = report.balanceMismatches().getFirst();
        assertThat(mismatch.accountId()).isEqualTo(aliceId);
        assertThat(mismatch.cachedBalance()).isEqualTo(15_000);
        assertThat(mismatch.derivedBalance()).isEqualTo(10_000);
        assertThat(mismatch.drift()).isEqualTo(5_000);

        // The entries still balance among themselves: only the cache lied.
        assertThat(report.unbalancedTransfers()).isEmpty();
        assertThat(report.netOfAllEntries()).isZero();
    }

    @Test
    void integrityCheckCatchesATransferThatDoesNotBalance() {
        UUID transferId = deposit(aliceUserId, aliceId, 10_000);

        // Add a third entry to an otherwise balanced transfer, so its debits and
        // credits no longer agree. Attached to a real transfer, because the
        // foreign key now insists every entry names one.
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency)
                VALUES (?, ?, 'CREDIT', 1234, 'USD')
                """, transferId, aliceId);

        IntegrityReport report = ledgerService.checkIntegrity();

        assertThat(report.healthy()).isFalse();
        assertThat(report.unbalancedTransfers()).hasSize(1);
        IntegrityReport.UnbalancedTransfer torn = report.unbalancedTransfers().getFirst();
        assertThat(torn.transferId()).isEqualTo(transferId);
        assertThat(torn.creditTotal()).isEqualTo(11_234);
        assertThat(torn.debitTotal()).isEqualTo(10_000);

        // A stray entry shows up twice over: the transfer no longer balances and
        // the ledger as a whole no longer nets to zero.
        assertThat(report.netOfAllEntries()).isEqualTo(1234);
    }

    /**
     * Uses a real transfer id on purpose. With a random one the new foreign key
     * would reject the row first, and this test would pass without the amount
     * constraint ever being consulted.
     */
    @Test
    void theDatabaseRejectsANonPositiveAmountEvenWhenTheServiceIsBypassed() {
        UUID transferId = deposit(aliceUserId, aliceId, 1_000);
        long entriesBefore = ledgerEntryRepository.count();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency)
                VALUES (?, ?, 'CREDIT', 0, 'USD')
                """, transferId, aliceId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("amount");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency)
                VALUES (?, ?, 'DEBIT', -500, 'USD')
                """, transferId, aliceId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("amount");

        assertThat(ledgerEntryRepository.count()).isEqualTo(entriesBefore);
    }

    @Test
    void theDatabaseRejectsAnUnknownDirection() {
        UUID transferId = deposit(aliceUserId, aliceId, 1_000);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, currency)
                VALUES (?, ?, 'SIDEWAYS', 100, 'USD')
                """, transferId, aliceId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("direction");
    }

    @Test
    void postedEntriesCannotBeRewrittenOrRemoved() {
        UUID transferId = deposit(aliceUserId, aliceId, 10_000);
        UUID entryId = ledgerEntryRepository.findAllByTransferId(transferId).getFirst().getId();

        // History is immutable at the database, not merely by convention: even a
        // direct statement outside the application cannot restate a posted entry.
        // Postgres raises P0001, which Spring cannot categorise further, so the
        // message is the meaningful assertion rather than the exception subtype.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = 1 WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(10_000);
    }

    @Test
    void serviceLevelRulesRejectMalformedMovements() {
        assertThatThrownBy(() -> ledgerService.postBalancedPair(UUID.randomUUID(), aliceId, bobId, 0, "USD"))
                .isInstanceOf(AccountingException.class);

        assertThatThrownBy(() -> ledgerService.postBalancedPair(UUID.randomUUID(), aliceId, aliceId, 100, "USD"))
                .isInstanceOf(AccountingException.class);

        assertThatThrownBy(() -> ledgerService.postBalancedPair(UUID.randomUUID(), aliceId, bobId, 100, "EUR"))
                .isInstanceOf(AccountingException.class);

        assertThat(ledgerEntryRepository.count()).isZero();
    }

    /**
     * The ceiling is what keeps every running total far inside a signed 64 bit
     * integer. Without it a large enough credit would wrap a balance negative,
     * which is the one arithmetic failure a ledger must never absorb quietly.
     */
    @Test
    void aMovementBeyondTheConfiguredCeilingIsRejected() {
        assertThatThrownBy(() -> transferService.deposit(aliceUserId, aliceId, Long.MAX_VALUE, null))
                .isInstanceOf(AccountingException.class)
                .hasMessageContaining("maximum permitted");

        assertThatThrownBy(() -> transferService.deposit(aliceUserId, aliceId, 100_000_001L, null))
                .isInstanceOf(AccountingException.class);

        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();
    }

    @Test
    void withdrawingMoreThanIsHeldIsRejectedAndWritesNothing() {
        deposit(aliceUserId, aliceId, 1_000);

        assertThatThrownBy(() -> transferService.withdraw(aliceUserId, aliceId, 5_000, null))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(1_000);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();
    }

    private UUID deposit(UUID userId, UUID accountId, long amount) {
        return transferService.deposit(userId, accountId, amount, null).id();
    }
}
