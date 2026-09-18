package dev.payvero.statement;

public class InvalidPeriodException extends RuntimeException {

    public InvalidPeriodException(String period) {
        super("Period must be formatted as YYYY-MM, received: " + period);
    }
}
