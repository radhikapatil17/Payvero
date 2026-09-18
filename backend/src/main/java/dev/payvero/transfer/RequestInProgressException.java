package dev.payvero.transfer;

/**
 * An identical request carrying this key is still running. Answering now would
 * mean either executing it twice or inventing a result, so the caller is asked
 * to retry once the first attempt has landed.
 */
public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException() {
        super("A request with this idempotency key is still in progress; retry shortly");
    }
}
