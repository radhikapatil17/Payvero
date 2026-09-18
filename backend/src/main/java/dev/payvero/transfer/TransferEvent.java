package dev.payvero.transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * The published shape of a transfer. Deliberately a copy rather than the entity:
 * an event describes what was true when it happened, and must not change later
 * because the row it came from did.
 */
public record TransferEvent(
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountMinorUnits,
        String currency,
        String status,
        Instant occurredAt
) {

    public static TransferEvent from(Transfer transfer) {
        return new TransferEvent(
                transfer.getId(),
                transfer.getSourceAccount().getId(),
                transfer.getDestinationAccount().getId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getStatus().name(),
                transfer.getCreatedAt());
    }
}
