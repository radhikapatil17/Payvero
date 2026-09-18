package dev.payvero.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "payvero.outbox")
public record OutboxProperties(
        @NotBlank String transferTopic,
        @NotBlank String ledgerTopic,
        @NotNull Duration sendTimeout
) {
}
