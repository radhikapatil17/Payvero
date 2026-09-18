package dev.payvero.fraud;

import dev.payvero.audit.OutboxRecorder;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.fraud.dto.FraudFlagResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class FraudReviewService {

    private final FraudFlagRepository flags;
    private final UserRepository users;
    private final OutboxRecorder outboxRecorder;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;

    public FraudReviewService(FraudFlagRepository flags,
                              UserRepository users,
                              OutboxRecorder outboxRecorder,
                              tools.jackson.databind.ObjectMapper objectMapper,
                              Clock clock) {
        this.flags = flags;
        this.users = users;
        this.outboxRecorder = outboxRecorder;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<FraudFlagResponse> queue(FraudFlagStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<FraudFlag> found = status == null
                ? flags.findAllByOrderByCreatedAtDesc(pageable)
                : flags.findAllByStatusOrderByCreatedAtDesc(status, pageable);
        return found.map(this::describe);
    }

    /** Parsed here, once, so no client has to parse a JSON string out of a field. */
    private FraudFlagResponse describe(FraudFlag flag) {
        return FraudFlagResponse.from(flag,
                objectMapper.readValue(flag.getDetails(), FraudFlagDetails.class));
    }

    /**
     * Records a reviewer's decision on the flag alone. The transfer's status and
     * its ledger entries are untouched: a review says what a human concluded, it
     * does not move money. Reversing a confirmed fraud is a new opposing
     * transfer, which keeps the history additive.
     */
    @Transactional
    public FraudFlagResponse review(UUID flagId, FraudFlagStatus decision, UUID reviewerId) {
        FraudFlag flag = flags.findById(flagId).orElseThrow(FraudFlagNotFoundException::new);
        if (!flag.isOpen()) {
            throw new FraudFlagAlreadyReviewedException();
        }
        User reviewer = users.findById(reviewerId)
                .orElseThrow(() -> new IllegalStateException("Authenticated reviewer no longer exists"));

        flag.review(decision, reviewer, clock.instant());
        FraudFlag reviewed = flags.saveAndFlush(flag);

        // Same transaction as the decision itself, through the same outbox the
        // transfer path uses. If the decision commits the event commits with it,
        // and if the decision is refused there is no event to explain away.
        outboxRecorder.record("FRAUD_FLAG", reviewed.getId(),
                "FRAUD_FLAG_" + decision.name(), FraudDecisionEvent.from(reviewed),
                reviewed.getReviewedAt());

        return describe(reviewed);
    }
}
