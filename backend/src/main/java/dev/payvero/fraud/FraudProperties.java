package dev.payvero.fraud;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param maxTransfersPerWindow the count a user may reach without being flagged;
 *                              exceeding it is what trips the rule, so exactly
 *                              this many is still fine
 * @param maxAmountPerWindow    the same boundary expressed in minor units
 */
@Validated
@ConfigurationProperties(prefix = "payvero.fraud")
public record FraudProperties(
        @NotNull Duration window,
        @NotNull @Positive Integer maxTransfersPerWindow,
        @NotNull @Positive Long maxAmountPerWindow,
        @NotNull @Positive Integer maxOptimisticRetries
) {
}
