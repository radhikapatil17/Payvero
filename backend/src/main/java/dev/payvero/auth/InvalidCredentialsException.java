package dev.payvero.auth;

/**
 * Raised for both an unknown email and a wrong password, deliberately carrying
 * the same message so a caller cannot tell which address is registered.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
