package dev.payvero.accounting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

/** Amounts are minor units, so 1000 is ten dollars. */
@Schema(description = "Funds an account against the system treasury")
public record DepositRequest(
        @Schema(description = "Integer minor units: 25000 is $250.00", example = "25000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive(message = "must be a positive number of minor units")
        long amountMinorUnits
) {
}
