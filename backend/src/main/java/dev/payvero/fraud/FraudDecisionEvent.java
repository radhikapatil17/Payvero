package dev.payvero.fraud;

import java.time.Instant;
import java.util.UUID;

/**
 * The published record of a human decision on a flag. Carries the reviewer's
 * id because the point of auditing a review is knowing who made it: a decision
 * that cannot be attributed is not reviewable, and an operator clearing their
 * own flag silently is exactly the gap this closes.
 */
public record FraudDecisionEvent(
        UUID flagId,
        UUID transferId,
        String rule,
        String decision,
        UUID reviewedBy,
        Instant reviewedAt
) {

    public static FraudDecisionEvent from(FraudFlag flag) {
        return new FraudDecisionEvent(
                flag.getId(),
                flag.getTransfer().getId(),
                flag.getRule().name(),
                flag.getStatus().name(),
                flag.getReviewedBy() == null ? null : flag.getReviewedBy().getId(),
                flag.getReviewedAt());
    }
}
