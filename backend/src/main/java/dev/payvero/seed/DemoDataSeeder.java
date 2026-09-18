package dev.payvero.seed;

import dev.payvero.audit.AuditLogRepository;
import dev.payvero.audit.OutboxPublisher;
import dev.payvero.auth.AuthService;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.fraud.FraudFlagDetails;
import dev.payvero.fraud.FraudFlagRepository;
import dev.payvero.fraud.FraudFlagStatus;
import dev.payvero.fraud.FraudFlagWriter;
import dev.payvero.fraud.FraudReviewService;
import dev.payvero.fraud.FraudRule;
import dev.payvero.fraud.VelocitySnapshot;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.accounting.IntegrityReport;
import dev.payvero.accounting.AccountingService;
import dev.payvero.statement.StatementService;
import dev.payvero.transfer.SettlementService;
import dev.payvero.transfer.Transfer;
import dev.payvero.transfer.TransferWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Populates a demo dataset so every feature has something to show.
 * <p>
 * <strong>How this runs, and why.</strong> A Spring profile with an
 * {@link ApplicationRunner}, not a Flyway migration and not a default-on
 * component. A migration is the wrong tool twice over: migrations are schema,
 * they run in tests and in production automatically, and they would have to
 * write ledger rows with raw SQL, bypassing every invariant this system
 * exists to enforce. A profile is opt-in, so it cannot run in tests (which
 * activate no profile) or in production (which simply does not enable it),
 * and it can call the real services.
 * <p>
 * <strong>Money goes through the real path.</strong> Nothing here inserts a
 * ledger entry. Every movement calls {@link TransferWriter}, which is the same
 * code the API uses: the balance check, the balanced pair, the version-guarded
 * cache update and the outbox event all happen exactly as they do for a real
 * transfer. Only the timestamp is supplied, which is why the entity
 * constructors take an instant. If seeding could produce an unbalanced ledger
 * the seeding would be wrong, so it ends by running the integrity check and
 * refusing to start if it fails.
 * <p>
 * <strong>Re-running is safe.</strong> The seeder checks for its own marker
 * user and returns immediately if the data is already there, so it never
 * duplicates history or double-credits an account.
 */
@Component
@Profile("seed")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    public static final String ALICE = "alice@payvero.dev";
    public static final String BOB = "bob@payvero.dev";
    public static final String ADMIN = "admin@payvero.dev";
    public static final String PASSWORD = "demo-password-123";

    /** Fixed seed so the demo looks the same every time it is built. */
    private static final long RANDOM_SEED = 20260101L;
    private static final int CLOSED_MONTHS = 3;

    private final AuthService authService;
    private final UserRepository users;
    private final AccountService accountService;
    private final TransferWriter transferWriter;
    private final SettlementService settlementService;
    private final AccountingService ledgerService;
    private final StatementService statementService;
    private final FraudFlagWriter fraudFlagWriter;
    private final FraudFlagRepository fraudFlags;
    private final FraudReviewService fraudReviewService;
    private final OutboxPublisher outboxPublisher;
    private final AuditLogRepository auditLog;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;

    private final Random random = new Random(RANDOM_SEED);

    public DemoDataSeeder(AuthService authService,
                          UserRepository users,
                          AccountService accountService,
                          TransferWriter transferWriter,
                          SettlementService settlementService,
                          AccountingService ledgerService,
                          StatementService statementService,
                          FraudFlagWriter fraudFlagWriter,
                          FraudFlagRepository fraudFlags,
                          FraudReviewService fraudReviewService,
                          OutboxPublisher outboxPublisher,
                          AuditLogRepository auditLog,
                          tools.jackson.databind.ObjectMapper objectMapper,
                          Clock clock) {
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.users = users;
        this.accountService = accountService;
        this.transferWriter = transferWriter;
        this.settlementService = settlementService;
        this.ledgerService = ledgerService;
        this.statementService = statementService;
        this.fraudFlagWriter = fraudFlagWriter;
        this.fraudFlags = fraudFlags;
        this.fraudReviewService = fraudReviewService;
        this.outboxPublisher = outboxPublisher;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(ALICE)) {
            log.info("Demo data already present, leaving it alone");
            return;
        }

        log.info("Seeding demo data");
        UUID aliceUserId = createUser(ALICE, Role.USER);
        UUID bobUserId = createUser(BOB, Role.USER);
        UUID adminUserId = createUser(ADMIN, Role.ADMIN);

        UUID aliceAccount = accountService.openAccount(aliceUserId, "USD").getId();
        UUID bobAccount = accountService.openAccount(bobUserId, "USD").getId();

        List<Transfer> everything = seedHistory(aliceAccount, bobAccount);
        settleWithPlausibleLag(everything);
        seedFraudQueue(everything, adminUserId);
        generateStatements(aliceAccount, bobAccount);
        drainOutboxIntoTheAuditTrail();

        verifyOrRefuseToStart();
        report(aliceAccount, bobAccount);
    }

    private UUID createUser(String email, Role role) {
        authService.register(email, PASSWORD);
        User user = users.findByEmail(email).orElseThrow();
        if (role != Role.USER) {
            // No endpoint grants a role, deliberately. Seeding promotes through
            // the domain rather than inventing an escalation path in the API.
            user.assignRole(role);
            users.saveAndFlush(user);
        }
        return user.getId();
    }

    /**
     * Three closed months plus the current one. Amounts and timings vary so the
     * balance-over-time chart has a shape rather than a staircase, and so the
     * statements read like statements.
     */
    private List<Transfer> seedHistory(UUID aliceAccount, UUID bobAccount) {
        List<Transfer> written = new ArrayList<>();
        YearMonth current = YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC));

        for (int monthsBack = CLOSED_MONTHS; monthsBack >= 0; monthsBack--) {
            YearMonth month = current.minusMonths(monthsBack);
            boolean isCurrentMonth = monthsBack == 0;
            int lastDay = isCurrentMonth
                    ? clock.instant().atOffset(ZoneOffset.UTC).getDayOfMonth()
                    : month.lengthOfMonth();

            // Salary-style funding early in the month, for both parties.
            written.add(deposit(aliceAccount, 450_000 + step(50_000), at(month, 2, 9, 15)));
            written.add(deposit(bobAccount, 380_000 + step(40_000), at(month, 3, 8, 40)));

            // Then a spread of movement in both directions.
            int movements = 14 + random.nextInt(6);
            for (int i = 0; i < movements && lastDay > 4; i++) {
                int day = 4 + random.nextInt(Math.max(1, lastDay - 4));
                Instant when = at(month, day, 8 + random.nextInt(12), random.nextInt(60));

                boolean alicePays = random.nextBoolean();
                long amount = plausibleAmount();
                written.add(alicePays
                        ? transfer(aliceAccount, bobAccount, amount, when)
                        : transfer(bobAccount, aliceAccount, amount, when));
            }

            // One larger movement mid-month, so the chart has visible steps.
            if (lastDay > 15) {
                written.add(transfer(aliceAccount, bobAccount, 120_000 + step(30_000),
                        at(month, 15, 13, 5)));
            }
        }
        log.info("Seeded {} transfers across {} months", written.size(), CLOSED_MONTHS + 1);
        return written;
    }

    /** Small, varied, and never round: real spending does not come in hundreds. */
    private long plausibleAmount() {
        long[] shapes = {1_250, 2_499, 3_780, 4_995, 7_340, 9_990, 12_450, 18_600, 24_990, 31_200};
        return shapes[random.nextInt(shapes.length)] + random.nextInt(500);
    }

    private long step(int spread) {
        return random.nextInt(spread);
    }

    private Transfer deposit(UUID accountId, long amount, Instant when) {
        return transferWriter.write(Account.TREASURY_ID, accountId, amount, when);
    }

    private Transfer transfer(UUID from, UUID to, long amount, Instant when) {
        return transferWriter.write(from, to, amount, when);
    }

    /**
     * Settles each transfer a plausible interval after it was created, rather
     * than stamping the whole backdated history with the moment seeding ran. A
     * May transfer that settled today reads as a three month settlement lag.
     */
    private void settleWithPlausibleLag(List<Transfer> transfers) {
        for (Transfer transfer : transfers) {
            Instant settledAt = transfer.getCreatedAt().plusSeconds(20 + random.nextInt(600));
            settlementService.settleAt(transfer.getId(), settledAt);
        }
    }

    /**
     * One flag in each state, so the review screen shows a real queue rather
     * than a single row. The two resolved ones go through the review service so
     * they emit the same audit events an operator's decision would.
     */
    private void seedFraudQueue(List<Transfer> transfers, UUID adminUserId) {
        List<Transfer> recent = transfers.stream()
                .filter(t -> !t.getSourceAccount().getId().equals(Account.TREASURY_ID))
                .toList();
        if (recent.size() < 3) {
            return;
        }

        UUID open = raise(recent.get(recent.size() - 1), FraudRule.VELOCITY_COUNT);
        UUID cleared = raise(recent.get(recent.size() - 2), FraudRule.VELOCITY_AMOUNT);
        UUID confirmed = raise(recent.get(recent.size() - 3), FraudRule.VELOCITY_COUNT);

        fraudReviewService.review(cleared, FraudFlagStatus.CLEARED, adminUserId);
        fraudReviewService.review(confirmed, FraudFlagStatus.CONFIRMED, adminUserId);

        log.info("Fraud queue seeded: 1 open ({}), 1 cleared, 1 confirmed", open);
    }

    /**
     * Seeded flags carry the same typed detail shape the detector produces, so
     * the review screen is not looking at demo data that is shaped differently
     * from the real thing.
     */
    private UUID raise(Transfer transfer, FraudRule rule) {
        FraudFlagDetails details = FraudFlagDetails.of(
                rule, 60, new VelocitySnapshot(6, transfer.getAmount() * 6), 5, 500_000);
        return fraudFlagWriter.raise(transfer.getId(), rule, objectMapper.writeValueAsString(details));
    }

    private void generateStatements(UUID aliceAccount, UUID bobAccount) {
        YearMonth current = YearMonth.from(clock.instant().atOffset(ZoneOffset.UTC));
        for (int monthsBack = CLOSED_MONTHS; monthsBack >= 1; monthsBack--) {
            YearMonth month = current.minusMonths(monthsBack);
            statementService.generate(aliceAccount, month);
            statementService.generate(bobAccount, month);
        }
        log.info("Generated statements for {} closed months", CLOSED_MONTHS);
    }

    /**
     * Publishes what seeding produced and waits briefly for the consumer, so a
     * fresh demo has an audit trail rather than an empty table that fills in a
     * few seconds later.
     */
    private void drainOutboxIntoTheAuditTrail() {
        while (outboxPublisher.publishPending() > 0) {
            // keep draining
        }
        long deadline = System.currentTimeMillis() + 20_000;
        while (auditLog.count() == 0 && System.currentTimeMillis() < deadline) {
            sleepBriefly();
        }
        log.info("Audit trail holds {} events", auditLog.count());
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The seeder's own proof. If seeded data could leave the books unbalanced,
     * the seeding is wrong and the demo is worse than useless, so this refuses
     * to finish starting rather than serving a corrupt ledger.
     */
    private void verifyOrRefuseToStart() {
        IntegrityReport report = ledgerService.checkIntegrity();
        if (!report.healthy()) {
            throw new IllegalStateException(
                    "Seeded data failed the integrity check: net=%d, %d balance mismatches, %d unbalanced transfers"
                            .formatted(report.netOfAllEntries(),
                                    report.balanceMismatches().size(),
                                    report.unbalancedTransfers().size()));
        }
        log.info("Integrity check passed: {} accounts, {} entries, net {}",
                report.accountsChecked(), report.entriesChecked(), report.netOfAllEntries());
    }

    private void report(UUID aliceAccount, UUID bobAccount) {
        log.info("Demo ready. Sign in with {} / {} (also {} and {} for admin)",
                ALICE, PASSWORD, BOB, ADMIN);
        log.info("  alice balance {}  bob balance {}  open fraud flags {}",
                ledgerService.derivedBalance(aliceAccount),
                ledgerService.derivedBalance(bobAccount),
                fraudFlags.countByStatus(FraudFlagStatus.OPEN));
    }

    private static Instant at(YearMonth month, int day, int hour, int minute) {
        int safeDay = Math.min(day, month.lengthOfMonth());
        return month.atDay(safeDay).atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }
}
