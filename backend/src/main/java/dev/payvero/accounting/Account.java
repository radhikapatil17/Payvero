package dev.payvero.accounting;

import dev.payvero.auth.User;
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

/**
 * {@code cachedBalance} is an optimisation, never the source of truth. The
 * authoritative balance is always the sum over {@code ledger_entries}; this
 * column exists so a balance read is not an aggregate scan, and the integrity
 * check exists to prove the two still agree.
 * <p>
 * {@code version} guards that cached value: two concurrent transfers touching
 * the same account cannot both write a balance derived from the same stale read.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    /** Seeded by V3 so the platform counterparty is findable without a lookup. */
    public static final UUID TREASURY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "cached_balance", nullable = false)
    private long cachedBalance;

    @Version
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Account(User user, AccountType accountType, String currency) {
        this.user = user;
        this.accountType = accountType;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Applies an already-validated movement to the cached figure. The service
     * decides whether the movement is legal; the entity only records it, and
     * the optimistic version column is what makes the write safe.
     * <p>
     * {@code addExact} rather than {@code +}: a silent overflow would turn a
     * very large credit into a negative balance, which is the one arithmetic
     * failure a ledger must never absorb quietly. The configured movement
     * ceiling should make this unreachable; it throws rather than trusting that.
     */
    public void applyToCachedBalance(long signedAmount) {
        this.cachedBalance = Math.addExact(this.cachedBalance, signedAmount);
    }
}
