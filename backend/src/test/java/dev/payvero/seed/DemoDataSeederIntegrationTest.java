package dev.payvero.seed;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.audit.AuditLogRepository;
import dev.payvero.auth.Role;
import dev.payvero.auth.UserRepository;
import dev.payvero.fraud.FraudFlagRepository;
import dev.payvero.fraud.FraudFlagStatus;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.accounting.IntegrityReport;
import dev.payvero.accounting.JournalEntryRepository;
import dev.payvero.accounting.AccountingService;
import dev.payvero.statement.StatementRepository;
import dev.payvero.transfer.Transfer;
import dev.payvero.transfer.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Activating the profile is what runs the seeder, so this test exercises it the
 * way a demo does. The context loading at all is already a partial proof: the
 * seeder refuses to finish starting if the ledger it produced does not balance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("seed")
class DemoDataSeederIntegrationTest {

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountingService ledgerService;

    @Autowired
    private JournalEntryRepository ledgerEntries;

    @Autowired
    private TransferRepository transfers;

    @Autowired
    private StatementRepository statements;

    @Autowired
    private FraudFlagRepository fraudFlags;

    @Autowired
    private AuditLogRepository auditLog;

    @Autowired
    private Clock clock;

    @Test
    void theSeededLedgerBalances() {
        IntegrityReport report = ledgerService.checkIntegrity();

        assertThat(report.healthy()).isTrue();
        assertThat(report.netOfAllEntries()).isZero();
        assertThat(report.balanceMismatches()).isEmpty();
        assertThat(report.unbalancedTransfers()).isEmpty();

        // Two entries per transfer, exactly: seeding never wrote a single side.
        assertThat(ledgerEntries.count()).isEqualTo(transfers.count() * 2);
    }

    @Test
    void thereAreTwoDemoUsersAnAdminAndFundedAccounts() {
        assertThat(users.findByEmail(DemoDataSeeder.ALICE)).isPresent();
        assertThat(users.findByEmail(DemoDataSeeder.BOB)).isPresent();
        assertThat(users.findByEmail(DemoDataSeeder.ADMIN))
                .get()
                .satisfies(admin -> assertThat(admin.getRole()).isEqualTo(Role.ADMIN));

        assertThat(ledgerService.derivedBalance(aliceAccount())).isPositive();
        assertThat(ledgerService.derivedBalance(bobAccount())).isPositive();

        // The treasury holds the negative of everything it issued.
        assertThat(ledgerService.derivedBalance(Account.TREASURY_ID)).isNegative();
    }

    /**
     * Statement figures have to agree with the entries they were derived from,
     * checked here against a fresh derivation rather than against each other.
     */
    @Test
    void seededStatementsAgreeWithTheSeededEntries() {
        YearMonth current = YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC));

        // Three closed months, both accounts.
        assertThat(statements.count()).isEqualTo(6);

        for (int monthsBack = 3; monthsBack >= 1; monthsBack--) {
            YearMonth month = current.minusMonths(monthsBack);
            String label = "%04d-%02d".formatted(month.getYear(), month.getMonthValue());

            for (UUID accountId : List.of(aliceAccount(), bobAccount())) {
                var statement = statements.findByAccountIdAndPeriod(accountId, label).orElseThrow();

                long derivedOpening = ledgerEntries.deriveBalanceBefore(
                        accountId, month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                long derivedClosing = ledgerEntries.deriveBalanceBefore(
                        accountId, month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());

                assertThat(statement.getOpeningBalance()).isEqualTo(derivedOpening);
                assertThat(statement.getClosingBalance()).isEqualTo(derivedClosing);
            }
        }
    }

    @Test
    void closingBalancesChainAcrossTheClosedMonths() {
        YearMonth current = YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC));
        UUID accountId = aliceAccount();

        var oldest = statement(accountId, current.minusMonths(3));
        var middle = statement(accountId, current.minusMonths(2));
        var newest = statement(accountId, current.minusMonths(1));

        assertThat(middle.getOpeningBalance()).isEqualTo(oldest.getClosingBalance());
        assertThat(newest.getOpeningBalance()).isEqualTo(middle.getClosingBalance());
    }

    /**
     * A chart needs points on many different days, not a handful of clustered
     * timestamps, or the balance-over-time view is three dots and a line.
     */
    @Test
    void historySpansEnoughDistinctDaysToPlot() {
        List<Transfer> all = transfers.findAll();

        Set<String> distinctDays = all.stream()
                .map(t -> t.getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString())
                .collect(Collectors.toSet());
        Set<String> distinctMonths = all.stream()
                .map(t -> t.getCreatedAt().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).toString())
                .collect(Collectors.toSet());

        assertThat(distinctMonths).hasSizeGreaterThanOrEqualTo(4);
        assertThat(distinctDays).hasSizeGreaterThanOrEqualTo(25);

        // Varied amounts, not fifty identical transfers.
        assertThat(all.stream().map(Transfer::getAmount).collect(Collectors.toSet()))
                .hasSizeGreaterThan(15);

        // Movement in both directions between the two demo accounts.
        UUID alice = aliceAccount();
        UUID bob = bobAccount();
        assertThat(all).anySatisfy(t -> {
            assertThat(t.getSourceAccount().getId()).isEqualTo(alice);
            assertThat(t.getDestinationAccount().getId()).isEqualTo(bob);
        });
        assertThat(all).anySatisfy(t -> {
            assertThat(t.getSourceAccount().getId()).isEqualTo(bob);
            assertThat(t.getDestinationAccount().getId()).isEqualTo(alice);
        });
    }

    @Test
    void theFraudQueueShowsAllThreeStates() {
        assertThat(fraudFlags.countByStatus(FraudFlagStatus.OPEN)).isEqualTo(1);
        assertThat(fraudFlags.countByStatus(FraudFlagStatus.CLEARED)).isEqualTo(1);
        assertThat(fraudFlags.countByStatus(FraudFlagStatus.CONFIRMED)).isEqualTo(1);
    }

    @Test
    void theAuditTrailReflectsTheSeededActivity() {
        assertThat(auditLog.count()).isPositive();

        // The two operator decisions are auditable like everything else.
        assertThat(auditLog.findAll()).anySatisfy(row ->
                assertThat(row.getEventType()).isEqualTo("FRAUD_FLAG_CLEARED"));
        assertThat(auditLog.findAll()).anySatisfy(row ->
                assertThat(row.getEventType()).isEqualTo("FRAUD_FLAG_CONFIRMED"));
    }

    /**
     * Running against an already-seeded database must be a no-op. Anything else
     * would double-credit accounts every time someone restarted the demo.
     */
    @Test
    void seedingAgainChangesNothing() {
        long usersBefore = users.count();
        long transfersBefore = transfers.count();
        long entriesBefore = ledgerEntries.count();
        long statementsBefore = statements.count();
        long aliceBefore = ledgerService.derivedBalance(aliceAccount());

        seeder.run(null);

        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(transfers.count()).isEqualTo(transfersBefore);
        assertThat(ledgerEntries.count()).isEqualTo(entriesBefore);
        assertThat(statements.count()).isEqualTo(statementsBefore);
        assertThat(ledgerService.derivedBalance(aliceAccount())).isEqualTo(aliceBefore);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();
    }

    private UUID aliceAccount() {
        return accountOf(DemoDataSeeder.ALICE);
    }

    private UUID bobAccount() {
        return accountOf(DemoDataSeeder.BOB);
    }

    private UUID accountOf(String email) {
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        return accountService.accountsOf(userId).getFirst().getId();
    }

    private dev.payvero.statement.Statement statement(UUID accountId, YearMonth month) {
        String label = "%04d-%02d".formatted(month.getYear(), month.getMonthValue());
        return statements.findByAccountIdAndPeriod(accountId, label).orElseThrow();
    }
}
