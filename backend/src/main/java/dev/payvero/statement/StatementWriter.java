package dev.payvero.statement;

import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The insert, on its own bean and in its own transaction, so a duplicate-key
 * collision can be caught from outside the transaction that hit it. Postgres
 * aborts a transaction on any failed statement, so catching in place would
 * poison everything after it.
 */
@Component
public class StatementWriter {

    private final StatementRepository statements;
    private final AccountRepository accounts;

    public StatementWriter(StatementRepository statements, AccountRepository accounts) {
        this.statements = statements;
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID insert(UUID accountId, String period, long opening, long closing,
                       int entryCount, String lineItems) {
        Account account = accounts.findById(accountId).orElseThrow();
        return statements.saveAndFlush(
                new Statement(account, period, opening, closing, entryCount, lineItems)).getId();
    }
}
