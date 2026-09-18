package dev.payvero.auth;

/**
 * Covers every refresh failure a caller is allowed to distinguish: unknown or
 * expired tokens. Reuse is a subtype so a handler can treat the whole family
 * uniformly while rollback rules target reuse alone.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
