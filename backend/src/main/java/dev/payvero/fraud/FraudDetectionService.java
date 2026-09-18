package dev.payvero.fraud;

import dev.payvero.transfer.TransferRepository;
import dev.payvero.transfer.TransferVelocityFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

/**
 * Runs after the money has already moved, off a Kafka event rather than in the
 * transfer's transaction. That is deliberate and mirrors how real fraud
 * operations work: a check that had to complete before a payment could settle
 * would make every payment as slow as the slowest rule, and an outage in fraud
 * detection would stop payments entirely. Here it can only ever add a flag.
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final TransferRepository transfers;
    private final FraudFlagRepository flags;
    private final FraudFlagWriter writer;
    private final FraudVelocityTracker velocity;
    private final FraudProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final dev.payvero.api.LedgerMetrics metrics;

    public FraudDetectionService(TransferRepository transfers,
                                 FraudFlagRepository flags,
                                 FraudFlagWriter writer,
                                 FraudVelocityTracker velocity,
                                 FraudProperties properties,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 dev.payvero.api.LedgerMetrics metrics) {
        this.clock = clock;
        this.metrics = metrics;
        this.transfers = transfers;
        this.flags = flags;
        this.writer = writer;
        this.velocity = velocity;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates one transfer and returns the rules it tripped.
     * <p>
     * Not transactional: the duplicate-key catch below has to sit outside the
     * transaction that hit the constraint.
     */
    public List<FraudRule> evaluate(UUID transferId) {
        return evaluate(transferId, clock.instant());
    }

    /**
     * Velocity is measured against when the transfer happened, not when the
     * event was consumed. Those coincide for live traffic, but they diverge the
     * moment anything is replayed: a backlog drained after an outage, a consumer
     * catching up, or seeded history would otherwise all land in one window and
     * flag transfers that were minutes apart in reality.
     */
    public List<FraudRule> evaluate(UUID transferId, Instant observedAt) {
        Optional<TransferVelocityFacts> maybeFacts = transfers.findVelocityFacts(transferId);
        if (maybeFacts.isEmpty() || !maybeFacts.get().isAttributableToAUser()) {
            // A treasury funded movement has no user to attribute velocity to.
            return List.of();
        }

        TransferVelocityFacts facts = maybeFacts.get();
        VelocitySnapshot snapshot =
                velocity.observeAt(facts.ownerId(), transferId, facts.amountMinorUnits(), observedAt);

        List<FraudRule> tripped = new ArrayList<>();
        if (snapshot.transferCount() > properties.maxTransfersPerWindow()) {
            raise(transferId, FraudRule.VELOCITY_COUNT, snapshot, tripped);
        }
        if (snapshot.totalAmountMinorUnits() > properties.maxAmountPerWindow()) {
            raise(transferId, FraudRule.VELOCITY_AMOUNT, snapshot, tripped);
        }
        return tripped;
    }

    /**
     * Raising a flag also transitions the transfer's status, and that write is
     * now version-guarded, so it can lose to a concurrent settlement. Retrying
     * is correct here in a way it is not for settlement: the whole raise runs in
     * one transaction, so a loss leaves nothing behind, and abandoning it would
     * leave a suspicious transfer unmarked. Settlement needs no retry because it
     * re-selects PENDING transfers on the next poll, and a transfer that lost to
     * a flag is no longer PENDING and should stay that way.
     */
    private void raise(UUID transferId, FraudRule rule, VelocitySnapshot snapshot, List<FraudRule> tripped) {
        for (int attempt = 0; attempt <= properties.maxOptimisticRetries(); attempt++) {
            if (flags.existsByTransferIdAndRule(transferId, rule)) {
                return;
            }
            try {
                writer.raise(transferId, rule, describe(rule, snapshot));
                metrics.fraudFlagRaised(rule.name());
                tripped.add(rule);
                return;
            } catch (DataIntegrityViolationException e) {
                // A redelivery of the same event raced this one. The unique
                // constraint settled it, which is what it is there for.
                log.debug("Transfer {} already carries a {} flag", transferId, rule);
                return;
            } catch (OptimisticLockingFailureException e) {
                log.debug("Lost a status race flagging transfer {}, attempt {}", transferId, attempt);
            }
        }
        log.warn("Could not flag transfer {} with {}: status contention exhausted retries", transferId, rule);
    }

    private String describe(FraudRule rule, VelocitySnapshot snapshot) {
        return objectMapper.writeValueAsString(FraudFlagDetails.of(
                rule,
                properties.window().toSeconds(),
                snapshot,
                properties.maxTransfersPerWindow(),
                properties.maxAmountPerWindow()));
    }

}
