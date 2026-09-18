package dev.payvero.accounting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.Instant;
import java.util.UUID;

/**
 * One side of a balanced pair. Rows are written once and never changed: there
 * is no setter, no revoke, no status, and the database rejects UPDATE and
 * DELETE outright. A correction is expressed as a new opposing pair, which is
 * what makes the history auditable rather than merely current.
 */
@Entity
@Table(name = "journal_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The caller supplies the instant. Entries are append-only, so a timestamp
     * assigned here can never be corrected afterwards — which means the service
     * layer, which owns the clock, has to be the one that decides it. That is
     * also what makes backdated history possible without bypassing this class.
     */
    public JournalEntry(UUID transferId, Account account, Direction direction,
                       long amount, String currency, Instant occurredAt) {
        this.transferId = transferId;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = occurredAt;
    }

    /** Fallback only: a caller that supplied an instant has already set it. */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Credits add, debits subtract. The only arithmetic an entry knows. */
    public long signedAmount() {
        return direction == Direction.CREDIT ? amount : -amount;
    }
}
