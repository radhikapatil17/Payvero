package dev.payvero.fraud;

/**
 * What one user has done inside the current window, including the transfer just
 * observed.
 */
public record VelocitySnapshot(int transferCount, long totalAmountMinorUnits) {
}
