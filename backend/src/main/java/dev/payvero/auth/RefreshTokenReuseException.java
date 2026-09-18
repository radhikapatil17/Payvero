package dev.payvero.auth;

/**
 * Raised when a refresh token that has already been spent is presented again.
 * The token's entire family is revoked before this is thrown, so the throw must
 * not roll back the surrounding transaction.
 */
public class RefreshTokenReuseException extends InvalidRefreshTokenException {

    public RefreshTokenReuseException() {
        super("Refresh token has already been used");
    }
}
