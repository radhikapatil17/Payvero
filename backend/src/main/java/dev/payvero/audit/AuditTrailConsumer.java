package dev.payvero.audit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes every consumed event to the audit trail, exactly once per event.
 * <p>
 * The outbox guarantees at-least-once delivery, never exactly-once: a row can
 * reach Kafka and the publisher die before marking it, and Kafka itself can
 * redeliver after a rebalance. Rather than pretend otherwise, this consumer is
 * idempotent. The unique constraint on {@code event_id} is the real guarantee;
 * the existence check in front of it only avoids the cost of a doomed insert.
 */
@Component
public class AuditTrailConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailConsumer.class);

    private final AuditLogRepository auditLog;
    private final AuditLogWriter writer;
    private final ObjectMapper objectMapper;

    public AuditTrailConsumer(AuditLogRepository auditLog, AuditLogWriter writer, ObjectMapper objectMapper) {
        this.auditLog = auditLog;
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {"${payvero.outbox.transfer-topic}", "${payvero.outbox.ledger-topic}"},
            groupId = "${payvero.outbox.consumer-group}")
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        record(envelope, record.topic(), record.partition(), record.offset());
    }

    /**
     * Deliberately not transactional: the duplicate has to be caught outside the
     * transaction that hit the constraint, because that transaction is already
     * aborted by the time the exception surfaces.
     *
     * @return true when this call created the row, false when it was already there
     */
    public boolean record(EventEnvelope envelope, String topic, Integer partition, Long offset) {
        if (auditLog.existsByEventId(envelope.eventId())) {
            return false;
        }
        try {
            writer.insert(envelope, topic, partition, offset);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Two deliveries raced past the check above. The constraint settled
            // it, which is exactly what it is for.
            log.debug("Event {} was already recorded by a concurrent delivery", envelope.eventId());
            return false;
        }
    }
}
