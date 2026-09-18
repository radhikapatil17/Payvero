package dev.payvero.accounting;

import java.util.UUID;

/** Projection for one account whose cached balance disagrees with its entries. */
public interface BalanceMismatchView {

    UUID getAccountId();

    long getCachedBalance();

    long getDerivedBalance();
}
