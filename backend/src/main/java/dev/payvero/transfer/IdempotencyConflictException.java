package dev.payvero.transfer;

/**
 * The key has been seen before carrying a different request. Replaying the
 * stored answer would be wrong because it answers a different question, and
 * executing the new request would break the promise the key represents, so the
 * only safe response is to refuse.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("This idempotency key was already used for a different request");
    }
}
