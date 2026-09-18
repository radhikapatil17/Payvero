package dev.payvero.transfer;

/**
 * The ledger entries are written the moment a transfer is accepted, so money
 * has already moved while a transfer is PENDING. This status tracks the
 * lifecycle around that movement, not whether the movement happened.
 */
public enum TransferStatus {
    PENDING,
    SETTLED,
    FAILED,
    FLAGGED
}
