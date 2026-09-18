package dev.payvero.fraud.dto;

import dev.payvero.fraud.FraudFlag;
import dev.payvero.fraud.FraudFlagDetails;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A flagged transfer awaiting or carrying a review decision")
public record FraudFlagResponse(
        UUID id,
        UUID transferId,
        @Schema(description = "VELOCITY_COUNT or VELOCITY_AMOUNT", example = "VELOCITY_COUNT")
        String rule,
        @Schema(description = "OPEN, CLEARED or CONFIRMED", example = "OPEN")
        String status,
        @Schema(description = "What the rule observed, typed rather than an embedded JSON string")
        FraudFlagDetails details,
        @Schema(description = "The admin who decided, null while OPEN")
        UUID reviewedBy,
        Instant reviewedAt,
        Instant createdAt
) {

    public static FraudFlagResponse from(FraudFlag flag, FraudFlagDetails details) {
        return new FraudFlagResponse(
                flag.getId(),
                flag.getTransfer().getId(),
                flag.getRule().name(),
                flag.getStatus().name(),
                details,
                flag.getReviewedBy() == null ? null : flag.getReviewedBy().getId(),
                flag.getReviewedAt(),
                flag.getCreatedAt());
    }
}
