package dev.payvero.statement;

import dev.payvero.accounting.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Written once and never changed: no mutator exists here and the database
 * rejects UPDATE and DELETE. Every column is {@code updatable = false} so a
 * stray dirty-check can never even attempt a write.
 */
@Entity
@Table(name = "statements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Statement {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    /** {@code YYYY-MM}. */
    @Column(nullable = false, updatable = false, length = 7)
    private String period;

    @Column(name = "opening_balance", nullable = false, updatable = false)
    private long openingBalance;

    @Column(name = "closing_balance", nullable = false, updatable = false)
    private long closingBalance;

    @Column(name = "entry_count", nullable = false, updatable = false)
    private int entryCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "line_items", nullable = false, updatable = false)
    private String lineItems;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    public Statement(Account account, String period, long openingBalance, long closingBalance,
                     int entryCount, String lineItems) {
        this.account = account;
        this.period = period;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.entryCount = entryCount;
        this.lineItems = lineItems;
    }

    @PrePersist
    void onCreate() {
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }

    /** The period's net movement, implied by the two balances rather than stored twice. */
    public long netMovement() {
        return closingBalance - openingBalance;
    }
}
