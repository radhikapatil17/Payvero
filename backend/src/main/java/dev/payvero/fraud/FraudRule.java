package dev.payvero.fraud;

/**
 * Rules are evaluated independently, so one transfer can carry both: many small
 * transfers trip VELOCITY_COUNT, while a few large ones trip VELOCITY_AMOUNT.
 * Neither subsumes the other, which is why they are separate flags rather than
 * one combined score.
 */
public enum FraudRule {
    VELOCITY_COUNT,
    VELOCITY_AMOUNT
}
