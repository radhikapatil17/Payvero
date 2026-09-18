package dev.payvero.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Used by both refresh and logout: each presents one raw refresh token. */
public record RefreshTokenRequest(
        @NotBlank @Size(max = 200) String refreshToken
) {
}
