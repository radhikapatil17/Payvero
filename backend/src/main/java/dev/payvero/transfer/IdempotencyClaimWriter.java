package dev.payvero.transfer;

import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Holds the claim insert in a transaction of its own, on a separate bean so the
 * caller's recovery read runs in a different one.
 * <p>
 * That separation is not stylistic. Postgres aborts an entire transaction when
 * any statement in it fails, so once the unique constraint rejects a duplicate
 * claim, every later statement in that same transaction is refused with
 * "current transaction is aborted". Reading back the winning row therefore has
 * to happen outside the transaction that lost.
 */
@Component
public class IdempotencyClaimWriter {

    private final IdempotencyRecordRepository records;
    private final UserRepository userRepository;

    public IdempotencyClaimWriter(IdempotencyRecordRepository records, UserRepository userRepository) {
        this.records = records;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID insertClaim(String key, UUID userId, String requestHash) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        return records.saveAndFlush(new IdempotencyRecord(key, user, requestHash)).getId();
    }
}
