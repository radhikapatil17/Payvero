package dev.payvero.accounting;

/** Base for ledger rule violations that a caller can be told about safely. */
public class AccountingException extends RuntimeException {

    public AccountingException(String message) {
        super(message);
    }
}
