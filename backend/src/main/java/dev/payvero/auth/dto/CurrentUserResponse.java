package dev.payvero.auth.dto;

import dev.payvero.auth.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "The authenticated caller, resolved server-side from the token")
public record CurrentUserResponse(
        UUID userId,
        String email,
        @Schema(description = "USER or ADMIN", example = "USER") String role,
        Instant createdAt
) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getRole().name(), user.getCreatedAt());
    }
}
