package dev.payvero.fraud;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the rule saw when it fired, so a reviewer can judge without rerunning it.
 * A typed record rather than free-form JSON: it is stored as JSON, but that is a
 * storage decision and should not oblige every client to parse a string and hand
 * type the result.
 */
@Schema(description = "The observation that tripped a rule, and the limits it was measured against")
public record FraudFlagDetails(
        String rule,
        long windowSeconds,
        int observedTransferCount,
        long observedAmountMinorUnits,
        int maxTransfersPerWindow,
        long maxAmountPerWindow
) {

    public static FraudFlagDetails of(FraudRule rule, long windowSeconds, VelocitySnapshot snapshot,
                                      int maxTransfersPerWindow, long maxAmountPerWindow) {
        return new FraudFlagDetails(
                rule.name(),
                windowSeconds,
                snapshot.transferCount(),
                snapshot.totalAmountMinorUnits(),
                maxTransfersPerWindow,
                maxAmountPerWindow);
    }
}
