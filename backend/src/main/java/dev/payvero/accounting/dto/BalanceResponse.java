package dev.payvero.accounting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Exposes both figures deliberately. The derived value is authoritative; the
 * cached one is shown beside it so a drift is visible to a caller rather than
 * silently papered over.
 */
@Schema(description = "Both the authoritative and the cached balance, so drift is visible rather than hidden")
public record BalanceResponse(
        UUID accountId,
        String currency,
        @Schema(description = "Summed from ledger entries. This is the truth.", example = "1178348")
        long derivedBalanceMinorUnits,
        @Schema(description = "The maintained column, shown for comparison", example = "1178348")
        long cachedBalanceMinorUnits,
        @Schema(description = "False means the cache has drifted and the ledger needs investigating")
        boolean consistent
) {

    public static BalanceResponse of(UUID accountId, String currency, long derived, long cached) {
        return new BalanceResponse(accountId, currency, derived, cached, derived == cached);
    }
}
