package dev.payvero.accounting;

/**
 * Carries no balance figure in its message: the caller already knows their own
 * balance, and an error string is the wrong place to disclose another party's.
 */
public class InsufficientFundsException extends AccountingException {

    public InsufficientFundsException() {
        super("Insufficient available balance for this movement");
    }
}
