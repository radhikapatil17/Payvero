package dev.payvero.accounting;

import java.util.UUID;

public class AccountNotFoundException extends AccountingException {

    public AccountNotFoundException(UUID accountId) {
        super("No account with id " + accountId);
    }
}
