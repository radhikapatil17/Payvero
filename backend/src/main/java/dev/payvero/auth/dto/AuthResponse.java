package dev.payvero.auth.dto;

import dev.payvero.auth.TokenPair;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A freshly issued token pair")
public record AuthResponse(
        @Schema(description = "Send as: Authorization: Bearer <token>")
        String accessToken,
        @Schema(description = "Single use. Rotating it revokes it and issues a successor.")
        String refreshToken,
        String tokenType,
        @Schema(description = "Lifetime of the access token", example = "300")
        long expiresInSeconds
) {

    public static AuthResponse bearer(TokenPair tokens, long expiresInSeconds) {
        return new AuthResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer", expiresInSeconds);
    }
}
