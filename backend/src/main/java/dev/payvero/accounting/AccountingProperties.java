package dev.payvero.accounting;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds on a single movement. These exist for two reasons: a ceiling keeps any
 * one mistake or abuse small, and it keeps every arithmetic result far inside
 * {@code long}, so a balance can never wrap around into a negative number.
 */
@Validated
@ConfigurationProperties(prefix = "payvero.accounting")
public record AccountingProperties(
        @NotNull @Positive Long maxMovementMinorUnits
) {
}
