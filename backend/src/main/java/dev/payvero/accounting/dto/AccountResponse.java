package dev.payvero.accounting.dto;

import dev.payvero.accounting.Account;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code balanceMinorUnits} is named for what it is. Every money figure crossing
 * this API is an integer count of cents, never a decimal, so no client can
 * introduce a rounding error by parsing it as a float.
 */
public record AccountResponse(
        UUID id,
        String currency,
        long balanceMinorUnits,
        String status,
        Instant createdAt
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCurrency(),
                account.getCachedBalance(),
                account.getStatus().name(),
                account.getCreatedAt());
    }
}
