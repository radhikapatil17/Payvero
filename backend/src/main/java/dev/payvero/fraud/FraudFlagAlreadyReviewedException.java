package dev.payvero.fraud;

/**
 * A decision is recorded once. Re-deciding would overwrite who concluded what
 * and when, which is exactly the history a review trail exists to keep.
 */
public class FraudFlagAlreadyReviewedException extends RuntimeException {

    public FraudFlagAlreadyReviewedException() {
        super("This flag has already been reviewed");
    }
}
