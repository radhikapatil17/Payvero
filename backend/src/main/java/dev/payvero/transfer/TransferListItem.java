package dev.payvero.transfer;

import dev.payvero.accounting.AccountType;

import java.time.Instant;
import java.util.UUID;

/**
 * A transfer plus both parties' identities, fetched in one query.
 * <p>
 * A projection rather than the entity because rendering a transfer needs the
 * counterparty's name, and reaching it through {@code account.user} lazily
 * would be an N+1 across a page of results.
 *
 * @param sourceOwnerEmail null when the account has no owner, which is only the treasury
 */
public record TransferListItem(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String sourceOwnerEmail,
        String destinationOwnerEmail,
        AccountType sourceAccountType,
        AccountType destinationAccountType,
        long amount,
        String currency,
        TransferStatus status,
        String failureReason,
        Instant createdAt,
        Instant settledAt
) {
}
