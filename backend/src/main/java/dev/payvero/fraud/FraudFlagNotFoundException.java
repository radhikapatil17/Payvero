package dev.payvero.fraud;

public class FraudFlagNotFoundException extends RuntimeException {

    public FraudFlagNotFoundException() {
        super("No such fraud flag");
    }
}
