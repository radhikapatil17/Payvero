package dev.payvero.statement;

import dev.payvero.accounting.JournalEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a statement. {@code balanceAfter} is carried so a reader can
 * follow the balance down the page without re-deriving it, and so the last
 * line's value can be checked against the stored closing balance.
 */
public record StatementLine(
        UUID entryId,
        UUID transferId,
        String direction,
        long amountMinorUnits,
        long balanceAfterMinorUnits,
        String currency,
        Instant occurredAt
) {

    public static StatementLine of(JournalEntry entry, long balanceAfter) {
        return new StatementLine(
                entry.getId(),
                entry.getTransferId(),
                entry.getDirection().name(),
                entry.getAmount(),
                balanceAfter,
                entry.getCurrency(),
                entry.getCreatedAt());
    }
}
