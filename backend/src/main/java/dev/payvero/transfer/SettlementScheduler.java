package dev.payvero.transfer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A thin trigger, deliberately holding no logic of its own: the work lives in
 * {@link SettlementService} so a test can drive it directly and deterministically
 * instead of waiting for a timer. Disabling the schedule is what lets a test
 * observe a transfer while it is still PENDING.
 */
@Component
@ConditionalOnProperty(name = "payvero.settlement.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementScheduler {

    private final SettlementService settlementService;

    public SettlementScheduler(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(fixedDelayString = "${payvero.settlement.poll-interval:1000}")
    void settleDue() {
        settlementService.settleDueTransfers();
    }
}
