package dev.payvero.statement.dto;

import dev.payvero.statement.Statement;
import dev.payvero.statement.StatementLine;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code lineItems} is passed through as the stored JSON rather than reparsed
 * and re-serialised, so what a reader sees is exactly what was recorded.
 */
@Schema(description = "An immutable statement for one closed period, derived from ledger entries")
public record StatementResponse(
        UUID id,
        UUID accountId,
        @Schema(description = "The period, YYYY-MM", example = "2026-05")
        String period,
        long openingBalanceMinorUnits,
        long closingBalanceMinorUnits,
        long netMovementMinorUnits,
        int entryCount,
        @Schema(description = "Entries in the period, each carrying the running balance after it")
        List<StatementLine> lineItems,
        Instant generatedAt
) {

    /**
     * The lines are stored as JSON and parsed here rather than handed over as a
     * string. Storing them as a document is a persistence decision; obliging
     * every client to parse a field and hand-type the result would make it a
     * contract decision too.
     */
    public static StatementResponse from(Statement statement, List<StatementLine> lineItems) {
        return new StatementResponse(
                statement.getId(),
                statement.getAccount().getId(),
                statement.getPeriod(),
                statement.getOpeningBalance(),
                statement.getClosingBalance(),
                statement.netMovement(),
                statement.getEntryCount(),
                lineItems,
                statement.getGeneratedAt());
    }
}
