package dev.payvero.accounting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every movement of money in the platform goes through {@link #postBalancedPair}.
 * There is no code path that writes a single entry, which is what makes the
 * "every transfer nets to zero" invariant true by construction rather than by
 * discipline.
 */
@Service
public class AccountingService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository ledgerEntryRepository;
    private final AccountingProperties properties;
    private final Clock clock;

    public AccountingService(AccountRepository accountRepository,
                         JournalEntryRepository ledgerEntryRepository,
                         AccountingProperties properties,
                         Clock clock) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Writes one debit and one credit of the same amount, then moves both cached
     * balances, all inside a single transaction. Either both entries exist and
     * both balances moved, or neither did.
     * <p>
     * Accounts are addressed by id and loaded here rather than passed in as
     * entities. A caller holding an entity from an earlier transaction holds a
     * stale {@code @Version} too, and merging that would either fail spuriously
     * or, worse, write a balance computed from a figure that has since moved.
     * Loading inside the transaction means the version guarding the write is
     * always the one that was actually read.
     *
     * @return the two entries written, debit first
     */
    @Transactional
    public List<JournalEntry> postBalancedPair(UUID transferId,
                                              UUID debitAccountId,
                                              UUID creditAccountId,
                                              long amount,
                                              String currency) {
        return postBalancedPair(transferId, debitAccountId, creditAccountId, amount, currency, clock.instant());
    }

    /**
     * As above, but with the instant supplied. Seeding historical activity uses
     * this so backdated data is written by exactly this method rather than
     * around it: the balanced pair, the cached balance update and the version
     * check are all still the same code, and only the timestamp differs.
     */
    @Transactional
    public List<JournalEntry> postBalancedPair(UUID transferId,
                                              UUID debitAccountId,
                                              UUID creditAccountId,
                                              long amount,
                                              String currency,
                                              Instant occurredAt) {

        if (amount <= 0) {
            throw new AccountingException("Amount must be a positive number of minor units");
        }
        if (amount > properties.maxMovementMinorUnits()) {
            throw new AccountingException("Amount exceeds the maximum permitted for a single movement");
        }
        if (debitAccountId.equals(creditAccountId)) {
            throw new AccountingException("An account cannot transact with itself");
        }

        Account debitAccount = load(debitAccountId);
        Account creditAccount = load(creditAccountId);
        requireUsable(debitAccount, currency);
        requireUsable(creditAccount, currency);

        JournalEntry debit = new JournalEntry(transferId, debitAccount, Direction.DEBIT, amount, currency, occurredAt);
        JournalEntry credit = new JournalEntry(transferId, creditAccount, Direction.CREDIT, amount, currency, occurredAt);
        ledgerEntryRepository.saveAll(List.of(debit, credit));

        // The cached figures are derived from the entries just written, so they
        // can never drift by more than the lifetime of this transaction. The
        // @Version column on Account is what stops a concurrent transfer from
        // writing a balance computed off a stale read.
        debitAccount.applyToCachedBalance(debit.signedAmount());
        creditAccount.applyToCachedBalance(credit.signedAmount());
        accountRepository.saveAll(List.of(debitAccount, creditAccount));

        return List.of(debit, credit);
    }

    /**
     * Every entry names a transfer, enforced by a foreign key, so there is no
     * ledger-level "deposit": money entering the platform is a transfer from the
     * treasury and is created by the transfer domain like any other movement.
     */
    public Account load(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public long derivedBalance(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return ledgerEntryRepository.deriveBalance(accountId);
    }

    @Transactional(readOnly = true)
    public Account treasury() {
        return accountRepository.findById(Account.TREASURY_ID)
                .orElseThrow(() -> new AccountNotFoundException(Account.TREASURY_ID));
    }

    /**
     * Re-derives every balance from entries and re-checks every transfer's
     * debit/credit symmetry. Nothing here trusts a cached column, which is the
     * point: this is the check that would catch a bug in the code that maintains
     * them, so it cannot share their assumptions.
     */
    @Transactional(readOnly = true)
    public IntegrityReport checkIntegrity() {
        List<IntegrityReport.BalanceMismatch> mismatches = ledgerEntryRepository.findBalanceMismatches().stream()
                .map(view -> new IntegrityReport.BalanceMismatch(
                        view.getAccountId(), view.getCachedBalance(), view.getDerivedBalance()))
                .toList();

        List<IntegrityReport.UnbalancedTransfer> unbalanced = ledgerEntryRepository.findUnbalancedTransfers().stream()
                .map(view -> new IntegrityReport.UnbalancedTransfer(
                        view.getTransferId(), view.getDebitTotal(), view.getCreditTotal()))
                .toList();

        long net = ledgerEntryRepository.sumOfAllSignedEntries();

        return new IntegrityReport(
                clock.instant(),
                mismatches.isEmpty() && unbalanced.isEmpty() && net == 0,
                accountRepository.count(),
                ledgerEntryRepository.count(),
                net,
                mismatches,
                unbalanced);
    }

    public void requireSufficientFunds(Account account, long amount) {
        if (account.getCachedBalance() < amount) {
            throw new InsufficientFundsException();
        }
    }

    private static void requireUsable(Account account, String currency) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountingException("Account " + account.getId() + " is not active");
        }
        if (!account.getCurrency().equals(currency)) {
            throw new AccountingException("Account " + account.getId() + " does not hold " + currency);
        }
    }
}
