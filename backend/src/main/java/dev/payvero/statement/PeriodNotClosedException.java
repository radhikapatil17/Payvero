package dev.payvero.statement;

/**
 * A statement is immutable, so generating one for a month still in progress
 * would permanently record figures that later entries in that same month could
 * never correct. Waiting for the period to close is what keeps immutability
 * honest rather than lossy.
 */
public class PeriodNotClosedException extends RuntimeException {

    public PeriodNotClosedException(String period) {
        super("Period " + period + " has not ended yet; a statement can only be generated for a closed period");
    }
}
