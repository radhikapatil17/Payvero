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
 * One consumed event, recorded once. Written only, never changed: there is no
 * mutator here and the database rejects UPDATE and DELETE outright.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLogEntry {

    @Id
    @GeneratedValue
    private UUID id;

    /** The originating outbox row id, unique here, which is what makes redelivery harmless. */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "kafka_topic", updatable = false, length = 100)
    private String kafkaTopic;

    @Column(name = "kafka_partition", updatable = false)
    private Integer kafkaPartition;

    @Column(name = "kafka_offset", updatable = false)
    private Long kafkaOffset;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    public AuditLogEntry(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
                         String payload, String kafkaTopic, Integer kafkaPartition, Long kafkaOffset) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.kafkaTopic = kafkaTopic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
    }

    @PrePersist
    void onCreate() {
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
    }
}
