package dev.payvero.transfer;

import dev.payvero.accounting.Account;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false, updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false, updatable = false)
    private Account destinationAccount;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * Guards the status transitions. Settlement and fraud detection both write
     * status from different threads; without this the later write would silently
     * replace the earlier one instead of being told it lost.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    public Transfer(Account sourceAccount, Account destinationAccount, long amount,
                    String currency, Instant occurredAt) {
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.currency = currency;
        this.status = TransferStatus.PENDING;
        this.createdAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Timestamps are supplied by the caller's clock, never read from the entity. */
    public void markSettled(Instant at) {
        this.status = TransferStatus.SETTLED;
        this.settledAt = at;
    }

    public void markFailed(String reason) {
        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
    }

    public void markFlagged() {
        this.status = TransferStatus.FLAGGED;
    }
}
