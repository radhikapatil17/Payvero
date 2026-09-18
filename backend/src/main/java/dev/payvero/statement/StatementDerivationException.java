package dev.payvero.statement;

/**
 * Raised when walking a period's entries from its opening balance does not land
 * on its closing balance. Both numbers come from the same entries, so this
 * should be unreachable; it exists so that if it ever does happen the statement
 * is refused rather than stored, because an internally inconsistent statement is
 * worse than a missing one.
 */
public class StatementDerivationException extends RuntimeException {

    public StatementDerivationException(String period, long opening, long closing, long walked) {
        super("Refusing to store statement for %s: entries walk from %d to %d, but the period boundaries give %d"
                .formatted(period, opening, walked, closing));
    }
}
