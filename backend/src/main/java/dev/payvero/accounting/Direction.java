package dev.payvero.accounting;

/**
 * Which side of a balanced pair an entry sits on. Balance is defined as
 * credits minus debits, so a CREDIT moves money into an account and a DEBIT
 * moves it out. Every transfer writes exactly one of each.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
