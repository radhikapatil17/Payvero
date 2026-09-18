package dev.payvero.accounting;

import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private static final String DEFAULT_CURRENCY = "USD";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    /**
     * Idempotent by intent: a user gets one account per currency, and asking
     * twice returns the existing one rather than failing. The partial unique
     * index is the real guarantee, so a lost race is translated here too.
     */
    @Transactional
    public Account openAccount(UUID userId, String currency) {
        String normalized = currency == null ? DEFAULT_CURRENCY : currency.toUpperCase(java.util.Locale.ROOT);

        return accountRepository.findByUserIdAndCurrency(userId, normalized)
                .orElseGet(() -> createAccount(userId, normalized));
    }

    private Account createAccount(UUID userId, String currency) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new AccountingException("No user with id " + userId));
        try {
            return accountRepository.saveAndFlush(new Account(owner, AccountType.USER, currency));
        } catch (DataIntegrityViolationException e) {
            return accountRepository.findByUserIdAndCurrency(userId, currency)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public List<Account> accountsOf(UUID userId) {
        return accountRepository.findAllByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Account require(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * Ownership is checked here rather than in the controller so every caller
     * gets the same rule. The treasury has no owner and so is never reachable
     * through this path.
     */
    @Transactional(readOnly = true)
    public Account requireOwnedBy(UUID accountId, UUID userId) {
        Account account = require(accountId);
        if (account.getUser() == null || !account.getUser().getId().equals(userId)) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }
}
