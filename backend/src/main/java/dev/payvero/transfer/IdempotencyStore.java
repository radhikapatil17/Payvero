package dev.payvero.transfer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Claims and completes idempotency keys in transactions separate from the
 * transfer's. The claim has to survive even if the work that follows it rolls
 * back, and the stored response has to commit independently of whatever
 * transaction produced it.
 * <p>
 * {@code claim} itself is deliberately not transactional: each step needs its
 * own, because a duplicate-key failure aborts the transaction it happened in
 * and the winning row can only be read from a fresh one.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository records;
    private final IdempotencyClaimWriter claimWriter;
    private final TransferRepository transferRepository;
    private final Clock clock;

    public IdempotencyStore(IdempotencyRecordRepository records,
                            IdempotencyClaimWriter claimWriter,
                            TransferRepository transferRepository,
                            Clock clock) {
        this.records = records;
        this.claimWriter = claimWriter;
        this.transferRepository = transferRepository;
        this.clock = clock;
    }

    /**
     * Wins the key or reports what the winner already produced.
     *
     * @throws IdempotencyConflictException the key was used for a different request
     * @throws RequestInProgressException   an identical request is still running
     */
    public IdempotencyClaim claim(String key, UUID userId, String requestHash) {
        Optional<IdempotencyRecord> existing = records.findByIdempotencyKeyAndUserId(key, userId);
        if (existing.isPresent()) {
            return inspect(existing.get(), requestHash);
        }

        try {
            return new IdempotencyClaim(claimWriter.insertClaim(key, userId, requestHash), null);
        } catch (DataIntegrityViolationException e) {
            // Another request claimed the key between the lookup and the insert.
            // The unique constraint is what actually decides the winner; this
            // read runs in a new transaction because the losing one is aborted.
            IdempotencyRecord winner = records.findByIdempotencyKeyAndUserId(key, userId)
                    .orElseThrow(() -> e);
            return inspect(winner, requestHash);
        }
    }

    private IdempotencyClaim inspect(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        if (!record.isCompleted()) {
            throw new RequestInProgressException();
        }
        return new IdempotencyClaim(record.getId(), record.getResponseBody());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID recordId, int status, String responseBody, UUID transferId) {
        IdempotencyRecord record = records.findById(recordId).orElseThrow();
        Transfer transfer = transferId == null ? null : transferRepository.findById(transferId).orElseThrow();
        record.complete(status, responseBody, transfer, clock.instant());
        records.saveAndFlush(record);
    }

    /**
     * Drops a claim whose work failed, so the caller is free to retry the same
     * key. A claim abandoned after a failure would lock the key out forever with
     * nothing stored to replay.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID recordId) {
        records.findById(recordId).ifPresent(records::delete);
    }
}
