package dev.payvero.transfer;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Moves accepted transfers from PENDING to SETTLED out of band. The money has
 * already moved by the time this runs: the ledger entries were written in the
 * accepting transaction. What settles here is the lifecycle around them, which
 * is what makes the status real rather than a field that is always SETTLED.
 */
@Service
public class SettlementService {

    private static final int BATCH_SIZE = 200;

    private final TransferRepository transferRepository;
    private final TransferProperties properties;
    private final Clock clock;

    public SettlementService(TransferRepository transferRepository,
                             TransferProperties properties,
                             Clock clock) {
        this.transferRepository = transferRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Settles one bounded batch, oldest first, and reports how many moved. A
     * batch rather than everything pending, so a backlog cannot turn one poll
     * into a transaction that holds locks over the whole table.
     */
    @Transactional
    public int settleDueTransfers() {
        Instant now = clock.instant();
        List<Transfer> due = transferRepository.findSettlementCandidates(
                now.minus(properties.settleAfter()), PageRequest.of(0, BATCH_SIZE));

        due.forEach(transfer -> transfer.markSettled(now));
        transferRepository.saveAll(due);
        return due.size();
    }

    /**
     * Settles one transfer at a given instant. Exists for backdated history: a
     * transfer created three months ago should not read as having settled today,
     * which is what happens when a batch stamps everything with its own clock.
     */
    @Transactional
    public void settleAt(UUID transferId, Instant settledAt) {
        transferRepository.findById(transferId).ifPresent(transfer -> {
            if (transfer.getStatus() == TransferStatus.PENDING) {
                transfer.markSettled(settledAt);
                transferRepository.saveAndFlush(transfer);
            }
        });
    }
}
