package dev.payvero.transfer;

import dev.payvero.accounting.AccountType;

import java.util.UUID;

/**
 * Everything fraud detection needs about a transfer, fetched in one query.
 * A projection rather than the entity on purpose: navigating lazy associations
 * from a Kafka consumer means either an open session it has no business holding
 * or a LazyInitializationException.
 *
 * @param ownerId null when the source is the treasury, which nobody owns
 */
public record TransferVelocityFacts(
        UUID transferId,
        long amountMinorUnits,
        AccountType sourceAccountType,
        UUID ownerId
) {

    public boolean isAttributableToAUser() {
        return sourceAccountType != AccountType.TREASURY && ownerId != null;
    }
}
