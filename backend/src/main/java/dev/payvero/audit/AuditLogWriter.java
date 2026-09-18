package dev.payvero.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The insert lives on its own bean, in its own transaction, so the caller can
 * catch a duplicate-key failure without being inside the transaction that
 * failed. Postgres aborts a transaction the moment any statement in it errors,
 * so swallowing the exception in place would poison every later statement.
 */
@Component
public class AuditLogWriter {

    private final AuditLogRepository auditLog;

    public AuditLogWriter(AuditLogRepository auditLog) {
        this.auditLog = auditLog;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID insert(EventEnvelope envelope, String topic, Integer partition, Long offset) {
        return auditLog.saveAndFlush(new AuditLogEntry(
                envelope.eventId(),
                envelope.eventType(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.payload(),
                topic,
                partition,
                offset)).getId();
    }
}
