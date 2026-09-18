package dev.payvero.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Deliberately looser than {@link RegisterRequest}: a login attempt is checked
 * against what is stored, not against current password policy, so tightening
 * the rules later must not lock existing users out at the validation layer.
 */
public record LoginRequest(
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 200) String password
) {
}
