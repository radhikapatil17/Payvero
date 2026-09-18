package dev.payvero.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Outbox lag is the one thing that can be badly wrong while every request still
 * succeeds: writes keep committing, the API stays green, and events quietly
 * stop reaching Kafka. Nothing else in the system would notice, so health
 * reports it explicitly.
 * <p>
 * DOWN rather than a warning above the threshold, because a backlog means the
 * audit trail and fraud detection are both running blind on stale data, and a
 * degraded state nobody pages on is the same as no signal at all.
 */
@Component
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventRepository outbox;
    private final long threshold;

    public OutboxHealthIndicator(OutboxEventRepository outbox,
                                 MeterRegistry registry,
                                 @Value("${payvero.outbox.lag-threshold:250}") long threshold) {
        this.outbox = outbox;
        this.threshold = threshold;

        // A gauge as well as health, so the backlog is graphable over time and
        // not just a boolean at the moment someone happens to look.
        registry.gauge("payvero.outbox.pending", this, self -> self.outbox.countByPublishedAtIsNull());
    }

    @Override
    public Health health() {
        long pending = outbox.countByPublishedAtIsNull();

        Health.Builder builder = pending > threshold ? Health.down() : Health.up();
        return builder
                .withDetail("pendingEvents", pending)
                .withDetail("threshold", threshold)
                .build();
    }
}
