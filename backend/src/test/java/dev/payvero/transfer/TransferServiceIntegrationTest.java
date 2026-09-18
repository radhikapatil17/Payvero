package dev.payvero.transfer;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.accounting.InsufficientFundsException;
import dev.payvero.accounting.IntegrityReport;
import dev.payvero.accounting.JournalEntryRepository;
import dev.payvero.accounting.AccountingService;
import dev.payvero.transfer.dto.CreateTransferRequest;
import dev.payvero.transfer.dto.TransferResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

/**
 * The scheduler is off so PENDING is observable, and settle-after is zero so
 * settlement can be driven directly instead of by waiting on a timer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payvero.settlement.enabled=false",
        "payvero.transfer.settle-after=PT0S",
        "payvero.transfer.rate-limit-per-window=1000"
})
class TransferServiceIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountingService ledgerService;

    @Autowired
    private JournalEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        jdbcTemplate.execute("TRUNCATE journal_entries");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type = 'USER'");
        jdbcTemplate.update("UPDATE accounts SET cached_balance = 0, version = 0 WHERE id = ?",
                Account.TREASURY_ID);
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    /**
     * The signature test. Twenty threads race to spend a balance that can only
     * fund ten of them, against a real Postgres. What must hold afterwards is
     * not "roughly right" but exact: the balance never goes negative, every
     * accepted transfer is fully present, every rejected one left nothing
     * behind, and the books still net to zero.
     */
    @Test
    void concurrentTransfersCanNeverOverdrawAnAccount() throws Exception {
        transferService.deposit(aliceUserId, aliceId, 10_000, null);

        int threads = 20;
        long each = 1_000;
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    release.await();
                    try {
                        transferService.createTransfer(
                                aliceUserId, new CreateTransferRequest(aliceId, bobId, each), null);
                        accepted.incrementAndGet();
                    } catch (InsufficientFundsException e) {
                        refused.incrementAndGet();
                    } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                        // Exhausted the bounded retry under heavy contention.
                        // A refusal, not a partial write.
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            release.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get() + refused.get()).isEqualTo(threads);
        assertThat(accepted.get()).isLessThanOrEqualTo(10);

        long aliceBalance = ledgerService.derivedBalance(aliceId);
        long bobBalance = ledgerService.derivedBalance(bobId);

        // Never negative, and exactly consistent with how many were accepted.
        assertThat(aliceBalance).isNotNegative();
        assertThat(aliceBalance).isEqualTo(10_000 - accepted.get() * each);
        assertThat(bobBalance).isEqualTo(accepted.get() * each);

        // No half written transfer: two entries for the deposit plus two for
        // each accepted transfer, and nothing at all for the refusals.
        assertThat(ledgerEntryRepository.count()).isEqualTo(2L + accepted.get() * 2L);
        assertThat(transferRepository.count()).isEqualTo(1L + accepted.get());

        assertThat(accountService.require(aliceId).getCachedBalance()).isEqualTo(aliceBalance);
        assertThat(accountService.require(bobId).getCachedBalance()).isEqualTo(bobBalance);

        IntegrityReport report = ledgerService.checkIntegrity();
        assertThat(report.healthy()).isTrue();
        assertThat(report.netOfAllEntries()).isZero();
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheFirstAnswerAndMovesNothingAgain() {
        transferService.deposit(aliceUserId, aliceId, 10_000, null);
        CreateTransferRequest request = new CreateTransferRequest(aliceId, bobId, 2_500);

        TransferResponse first = transferService.createTransfer(aliceUserId, request, "key-alpha");
        TransferResponse replay = transferService.createTransfer(aliceUserId, request, "key-alpha");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay).isEqualTo(first);

        // One transfer, one pair of entries, one movement of money.
        assertThat(transferRepository.count()).isEqualTo(2);
        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(7_500);
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(2_500);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        transferService.deposit(aliceUserId, aliceId, 10_000, null);
        transferService.createTransfer(aliceUserId, new CreateTransferRequest(aliceId, bobId, 2_500), "key-beta");

        assertThatThrownBy(() -> transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, 9_999), "key-beta"))
                .isInstanceOf(IdempotencyConflictException.class);

        // The refusal changed nothing.
        assertThat(ledgerService.derivedBalance(aliceId)).isEqualTo(7_500);
        assertThat(transferRepository.count()).isEqualTo(2);
    }

    /**
     * A key whose work failed must not be locked out: nothing was stored to
     * replay, so the caller has to be able to try the same key again.
     */
    @Test
    void aKeyIsReusableAfterTheRequestItGuardedFailed() {
        transferService.deposit(aliceUserId, aliceId, 1_000, null);

        assertThatThrownBy(() -> transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, 5_000), "key-gamma"))
                .isInstanceOf(InsufficientFundsException.class);

        transferService.deposit(aliceUserId, aliceId, 9_000, null);
        TransferResponse retried = transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, 5_000), "key-gamma");

        assertThat(retried.status()).isEqualTo(TransferStatus.PENDING.name());
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(5_000);
    }

    @Test
    void concurrentRequestsSharingOneKeyProduceExactlyOneTransfer() throws Exception {
        transferService.deposit(aliceUserId, aliceId, 10_000, null);
        CreateTransferRequest request = new CreateTransferRequest(aliceId, bobId, 1_000);

        int threads = 8;
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger answered = new AtomicInteger();
        AtomicInteger toldToRetry = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    release.await();
                    try {
                        transferService.createTransfer(aliceUserId, request, "key-shared");
                        answered.incrementAndGet();
                    } catch (RequestInProgressException e) {
                        toldToRetry.incrementAndGet();
                    }
                    return null;
                }));
            }
            release.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Claiming the key before doing the work is what makes this exact: the
        // losers are told to retry rather than being allowed to double spend.
        assertThat(answered.get() + toldToRetry.get()).isEqualTo(threads);
        assertThat(transferRepository.count()).isEqualTo(2);
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(1_000);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();
    }

    @Test
    void settlementMovesAcceptedTransfersOutOfPending() {
        transferService.deposit(aliceUserId, aliceId, 10_000, null);
        TransferResponse created = transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, 3_000), null);

        // Money has already moved: only the lifecycle is outstanding.
        assertThat(created.status()).isEqualTo(TransferStatus.PENDING.name());
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(3_000);

        int settled = settlementService.settleDueTransfers();
        assertThat(settled).isPositive();

        Transfer reloaded = transferRepository.findById(created.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.SETTLED);
        assertThat(reloaded.getSettledAt()).isNotNull();
        assertThat(transferRepository.countByStatus(TransferStatus.PENDING)).isZero();
    }

    /**
     * Settlement and fraud detection both write a transfer's status from
     * different threads. Without a version column the later write silently
     * replaced the earlier one, so the core domain object's state machine was
     * the only concurrency-controlled thing in the system that was not.
     */
    @Test
    void twoConcurrentStatusTransitionsCannotBothWin() throws Exception {
        transferService.deposit(aliceUserId, aliceId, 10_000, null);
        UUID transferId = transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, 1_000), null).id();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        // Both read the same version before either writes, which is exactly the
        // interleaving that used to lose an update.
        Transfer asSettlement = transferRepository.findById(transferId).orElseThrow();
        Transfer asFraud = transferRepository.findById(transferId).orElseThrow();

        transaction.execute(status -> {
            Transfer managed = transferRepository.findById(transferId).orElseThrow();
            managed.markSettled(Instant.now());
            return transferRepository.saveAndFlush(managed);
        });

        assertThatThrownBy(() -> transaction.execute(status -> {
            asFraud.markFlagged();
            return transferRepository.saveAndFlush(asFraud);
        })).isInstanceOf(OptimisticLockingFailureException.class);

        // The winner stands; the loser was refused rather than overwriting it.
        Transfer reloaded = transferRepository.findById(transferId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.SETTLED);
        assertThat(asSettlement.getVersion()).isZero();
        assertThat(reloaded.getVersion()).isEqualTo(1);
    }

    @Test
    void aUserCannotTransferOutOfAnAccountTheyDoNotOwn() {
        transferService.deposit(bobUserId, bobId, 10_000, null);

        assertThatThrownBy(() -> transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(bobId, aliceId, 5_000), null))
                .isInstanceOf(dev.payvero.accounting.AccountNotFoundException.class);

        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(10_000);
        assertThat(ledgerService.derivedBalance(aliceId)).isZero();
    }
}
