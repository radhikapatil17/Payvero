package dev.payvero.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param maxOptimisticRetries how many times a write may be replayed after
 *                             losing a version race. Bounded on purpose: an
 *                             unbounded retry turns contention into a livelock
 *                             that looks like a hang rather than an error.
 * @param settleAfter          how long a transfer stays PENDING before the
 *                             worker settles it, so the lifecycle is observable
 * @param rateLimitWindow      sliding window for the per-user transfer limit
 * @param rateLimitPerWindow   how many transfers one user may start per window
 */
@Validated
@ConfigurationProperties(prefix = "payvero.transfer")
public record TransferProperties(
        @NotNull @Positive Integer maxOptimisticRetries,
        @NotNull Duration settleAfter,
        @NotNull Duration rateLimitWindow,
        @NotNull @Positive Integer rateLimitPerWindow
) {
}
