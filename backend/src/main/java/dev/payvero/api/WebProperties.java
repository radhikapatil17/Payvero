package dev.payvero.api;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * @param allowedOrigins exact origins the browser may call from.
 */
@Validated
@ConfigurationProperties(prefix = "payvero.web")
public record WebProperties(
        @NotEmpty List<String> allowedOrigins
) {
}
