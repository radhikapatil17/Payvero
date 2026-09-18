package dev.payvero.audit;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Records an event for later publication. Deliberately has no transaction
 * annotation of its own: it must join whatever transaction its caller is in, so
 * the event and the business data it describes commit together or not at all.
 * Opening a transaction here would recreate the dual write the outbox exists to
 * remove.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxEventRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * @param occurredAt when the fact happened, which is not always now: a
     *                   backdated movement must publish an event that says so,
     *                   or any consumer reasoning about time sees the
     *                   publisher's clock instead of the domain's.
     */
    public OutboxEvent record(String aggregateType, UUID aggregateId, String eventType,
                              Object payload, Instant occurredAt) {
        return outbox.save(new OutboxEvent(aggregateType, aggregateId, eventType,
                objectMapper.writeValueAsString(payload), occurredAt));
    }
}
