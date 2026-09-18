package dev.payvero.fraud;

import dev.payvero.auth.User;
import dev.payvero.transfer.Transfer;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_flags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudFlag {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false, updatable = false)
    private Transfer transfer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private FraudRule rule;

    /** What the rule saw when it fired, so a reviewer can judge without rerunning it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudFlagStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public FraudFlag(Transfer transfer, FraudRule rule, String details) {
        this.transfer = transfer;
        this.rule = rule;
        this.details = details;
        this.status = FraudFlagStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Records a reviewer's decision. Only the flag changes; the transfer's money
     * is not touched here, because a review is an opinion about a movement that
     * has already happened.
     */
    public void review(FraudFlagStatus decision, User reviewer, Instant at) {
        if (decision == FraudFlagStatus.OPEN) {
            throw new IllegalArgumentException("A review must resolve the flag");
        }
        this.status = decision;
        this.reviewedBy = reviewer;
        this.reviewedAt = at;
    }

    public boolean isOpen() {
        return status == FraudFlagStatus.OPEN;
    }
}
