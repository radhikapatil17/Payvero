package dev.payvero.accounting;

import java.time.Instant;
import java.util.List;

/**
 * The result of re-deriving the entire ledger from primary records.
 *
 * @param netOfAllEntries every signed entry summed; must be exactly zero,
 *                        because each credit was written with a matching debit
 */
public record IntegrityReport(
        Instant checkedAt,
        boolean healthy,
        long accountsChecked,
        long entriesChecked,
        long netOfAllEntries,
        List<BalanceMismatch> balanceMismatches,
        List<UnbalancedTransfer> unbalancedTransfers
) {

    public record BalanceMismatch(java.util.UUID accountId, long cachedBalance, long derivedBalance) {

        public long drift() {
            return cachedBalance - derivedBalance;
        }
    }

    public record UnbalancedTransfer(java.util.UUID transferId, long debitTotal, long creditTotal) {

        public long drift() {
            return creditTotal - debitTotal;
        }
    }
}
