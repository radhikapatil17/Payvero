package dev.payvero.transfer;

import dev.payvero.audit.OutboxRecorder;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountType;
import dev.payvero.accounting.AccountingService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The single ACID unit of a transfer, kept in its own bean so the retry loop in
 * {@link TransferService} sits outside the transaction. Retrying inside would
 * reuse a transaction already marked rollback-only and fail on every attempt.
 * <p>
 * REQUIRES_NEW guarantees a genuinely fresh transaction per attempt even if a
 * caller happens to be inside one, so each retry re-reads the current version
 * rather than replaying against the stale state that lost the previous race.
 */
@Component
public class TransferWriter {

    private final AccountingService ledgerService;
    private final TransferRepository transferRepository;
    private final OutboxRecorder outboxRecorder;
    private final Clock clock;

    public TransferWriter(AccountingService ledgerService,
                          TransferRepository transferRepository,
                          OutboxRecorder outboxRecorder,
                          Clock clock) {
        this.ledgerService = ledgerService;
        this.transferRepository = transferRepository;
        this.outboxRecorder = outboxRecorder;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer write(UUID sourceAccountId, UUID destinationAccountId, long amount) {
        return write(sourceAccountId, destinationAccountId, amount, clock.instant());
    }

    /**
     * As above with the instant supplied, so seeded history is written by this
     * method rather than around it. Everything that makes a transfer correct —
     * the balance check, the balanced pair, the version guarded cache update and
     * the outbox event — is the same code path; only the timestamp differs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer write(UUID sourceAccountId, UUID destinationAccountId, long amount, Instant occurredAt) {
        Account source = ledgerService.load(sourceAccountId);
        Account destination = ledgerService.load(destinationAccountId);

        // Read the balance inside this transaction, immediately before the write
        // that depends on it. The version column is what makes that read binding:
        // if another transfer moves this balance first, the flush below fails
        // rather than silently overdrawing.
        //
        // The treasury is exempt. It is the account money is issued from, so its
        // balance is the negative of everything the platform owes and is
        // supposed to run below zero.
        if (source.getAccountType() != AccountType.TREASURY) {
            ledgerService.requireSufficientFunds(source, amount);
        }

        Transfer transfer = transferRepository.saveAndFlush(
                new Transfer(source, destination, amount, source.getCurrency(), occurredAt));

        // Flushed above because ledger_entries.transfer_id is a foreign key: the
        // transfer row has to exist before its entries can reference it.
        ledgerService.postBalancedPair(
                transfer.getId(), sourceAccountId, destinationAccountId, amount,
                source.getCurrency(), occurredAt);

        // Same transaction as the money. Publishing to Kafka from here instead
        // would be a dual write: the broker call can succeed and this
        // transaction still roll back, leaving an event for a transfer that
        // never happened. One commit covers both, and a poller publishes after.
        outboxRecorder.record("TRANSFER", transfer.getId(), "TRANSFER_CREATED",
                TransferEvent.from(transfer), occurredAt);

        return transfer;
    }
}
