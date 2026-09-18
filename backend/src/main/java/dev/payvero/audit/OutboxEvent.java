package dev.payvero.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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
 * A message waiting to be published, written in the same transaction as the
 * business data it describes. Its id travels with the event and becomes the
 * consumer's deduplication key.
 */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    /** When the row was written. Drives publication ordering. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the fact this describes actually happened. Equal to
     * {@code createdAt} for live traffic, and deliberately not for backdated or
     * replayed events, so a consumer reasoning about time reads the domain's
     * clock rather than the publisher's.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType,
                       String payload, Instant occurredAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (occurredAt == null) {
            occurredAt = createdAt;
        }
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    /**
     * Records a failed attempt without marking the row published, so the next
     * poll picks it up again. Delivery is retried forever by design: an event
     * that describes committed money must not be dropped because a broker was
     * briefly unavailable.
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
