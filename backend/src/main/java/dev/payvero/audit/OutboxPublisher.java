package dev.payvero.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains the outbox to Kafka. Runs entirely after the business transaction has
 * committed, which is what makes the pattern work: the event is already durable
 * in Postgres, so a broker outage delays delivery rather than losing it.
 * <p>
 * Delivery is therefore at-least-once, not exactly-once. A row can be published
 * and the process die before the row is marked, and the next poll will publish
 * it again. That is why {@link AuditTrailConsumer} deduplicates instead of
 * pretending the duplicate cannot happen.
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository outbox,
                           KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper,
                           OutboxProperties properties,
                           Clock clock) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes one batch and reports how many landed. A row is marked published
     * only after the broker acknowledges it: marking first would turn a failed
     * send into a silently dropped event, which is the one outcome the outbox is
     * meant to make impossible.
     */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> pending = outbox.findPending(PageRequest.of(0, BATCH_SIZE));
        int published = 0;

        for (OutboxEvent event : pending) {
            try {
                String topic = topicFor(event.getAggregateType());
                String body = objectMapper.writeValueAsString(EventEnvelope.of(event));

                // Keyed by aggregate id so every event about one transfer lands
                // on one partition and is consumed in the order it was written.
                kafkaTemplate.send(topic, event.getAggregateId().toString(), body)
                        .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);

                event.markPublished(clock.instant());
                published++;
            } catch (Exception e) {
                // Left unpublished on purpose so the next poll retries it.
                event.markFailed(e.getMessage());
                log.warn("Outbox event {} could not be published, will retry: {}",
                        event.getId(), e.getMessage());
            }
        }

        outbox.saveAll(pending);
        return published;
    }

    /**
     * Transfer events go to the transfer stream; everything else, including
     * operator decisions, goes to the audit stream. Defaulting to the audit
     * topic rather than the transfer topic means a new event type is recorded
     * by default instead of silently landing where consumers filter it out.
     */
    private String topicFor(String aggregateType) {
        return "TRANSFER".equals(aggregateType) ? properties.transferTopic() : properties.ledgerTopic();
    }
}
