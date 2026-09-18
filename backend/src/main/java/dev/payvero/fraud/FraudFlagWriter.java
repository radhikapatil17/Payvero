package dev.payvero.fraud;

import dev.payvero.transfer.Transfer;
import dev.payvero.transfer.TransferRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The flag insert, on its own bean and in its own transaction, so a duplicate
 * key can be caught by the caller from outside the transaction that hit it.
 * Postgres aborts a transaction on any failed statement, so catching in place
 * would poison everything after it.
 */
@Component
public class FraudFlagWriter {

    private final FraudFlagRepository flags;
    private final TransferRepository transfers;

    public FraudFlagWriter(FraudFlagRepository flags, TransferRepository transfers) {
        this.flags = flags;
        this.transfers = transfers;
    }

    /**
     * Raises the flag and marks the transfer for review. Nothing here writes a
     * ledger entry or moves a balance: the money has already moved and stays
     * moved. Undoing it would be a new opposing transfer, never an edit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID raise(UUID transferId, FraudRule rule, String details) {
        Transfer transfer = transfers.findById(transferId).orElseThrow();
        UUID flagId = flags.saveAndFlush(new FraudFlag(transfer, rule, details)).getId();

        transfer.markFlagged();
        transfers.saveAndFlush(transfer);
        return flagId;
    }
}
