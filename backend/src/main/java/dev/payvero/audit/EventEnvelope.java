package dev.payvero.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * What actually travels over Kafka. {@code eventId} is the outbox row id and is
 * carried explicitly so a consumer can deduplicate without needing to
 * understand the payload.
 */
public record EventEnvelope(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        Instant occurredAt,
        String payload
) {

    public static EventEnvelope of(OutboxEvent event) {
        return new EventEnvelope(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getPayload());
    }
}
