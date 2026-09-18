package dev.payvero.accounting.dto;

import jakarta.validation.constraints.Pattern;

public record OpenAccountRequest(
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three letter currency code")
        String currency
) {
}
