package dev.payvero.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PayveroMetrics {

    private final MeterRegistry registry;
    private final Counter optimisticRetries;
    private final Counter idempotencyReplays;
    private final Counter rateLimitRejections;

    public PayveroMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.optimisticRetries = Counter.builder("payvero.transfer.optimistic.retries")
                .description("Transfer writes replayed after losing a version race")
                .register(registry);
        this.idempotencyReplays = Counter.builder("payvero.idempotency.replays")
                .description("Requests answered from a stored response instead of executing")
                .register(registry);
        this.rateLimitRejections = Counter.builder("payvero.transfer.ratelimit.rejections")
                .description("Transfers refused because the caller's window was full")
                .register(registry);

        for (dev.payvero.transfer.TransferStatus status : dev.payvero.transfer.TransferStatus.values()) {
            registry.counter("payvero.transfers", "status", status.name());
        }
        for (dev.payvero.fraud.FraudRule rule : dev.payvero.fraud.FraudRule.values()) {
            registry.counter("payvero.fraud.flags", "rule", rule.name());
        }
    }

    public void transferRecorded(String status) {
        registry.counter("payvero.transfers", "status", status).increment();
    }

    public void optimisticRetry() {
        optimisticRetries.increment();
    }

    public void idempotencyReplay() {
        idempotencyReplays.increment();
    }

    public void rateLimitRejected() {
        rateLimitRejections.increment();
    }

    public void fraudFlagRaised(String rule) {
        registry.counter("payvero.fraud.flags", "rule", rule).increment();
    }
}
