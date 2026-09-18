package dev.payvero.fraud;

import dev.payvero.audit.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the same transfer stream as the audit trail but under its own consumer
 * group, so each gets every event independently and a slow or failing fraud
 * check can never cost the audit trail a record.
 */
@Component
public class FraudDetectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionConsumer.class);
    private static final String TRANSFER_CREATED = "TRANSFER_CREATED";

    private final FraudDetectionService fraudDetection;
    private final ObjectMapper objectMapper;

    public FraudDetectionConsumer(FraudDetectionService fraudDetection, ObjectMapper objectMapper) {
        this.fraudDetection = fraudDetection;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${payvero.outbox.transfer-topic}",
            groupId = "${payvero.fraud.consumer-group}")
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        if (!TRANSFER_CREATED.equals(envelope.eventType())) {
            return;
        }
        // The envelope carries when the transfer happened; using that rather
        // than the consumption time keeps a replayed backlog from reading as a
        // burst that never occurred.
        fraudDetection.evaluate(envelope.aggregateId(), envelope.occurredAt()).forEach(rule ->
                log.info("Transfer {} flagged by {}", envelope.aggregateId(), rule));
    }
}
