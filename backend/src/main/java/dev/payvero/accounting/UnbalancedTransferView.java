package dev.payvero.accounting;

import java.util.UUID;

/** Projection for one transfer whose debits and credits do not agree. */
public interface UnbalancedTransferView {

    UUID getTransferId();

    long getDebitTotal();

    long getCreditTotal();
}
