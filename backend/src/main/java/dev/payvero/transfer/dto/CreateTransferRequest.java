package dev.payvero.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

@Schema(description = "A request to move money between two accounts")
public record CreateTransferRequest(
        @Schema(description = "Must be an account you own", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID sourceAccountId,
        @Schema(description = "Any account, including another user's", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID destinationAccountId,
        @Schema(description = "Integer minor units, never a decimal: 25000 is $250.00",
                example = "25000", requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive(message = "must be a positive number of minor units") long amountMinorUnits
) {
}
