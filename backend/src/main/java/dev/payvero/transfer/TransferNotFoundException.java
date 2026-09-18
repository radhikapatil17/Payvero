package dev.payvero.transfer;

/**
 * Also raised when a transfer exists but involves none of the caller's
 * accounts: confirming existence would leak that an id is real.
 */
public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("No such transfer");
    }
}
