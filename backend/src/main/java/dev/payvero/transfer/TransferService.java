package dev.payvero.transfer;

import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.transfer.dto.CreateTransferRequest;
import dev.payvero.transfer.dto.TransferResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestration only. The single ACID unit lives in {@link TransferWriter}; the
 * retry loop, the rate limit and the idempotency handshake all sit outside it,
 * because each of them has to be able to observe or survive a failed attempt.
 */
@Service
public class TransferService {

    private final TransferWriter writer;
    private final TransferRepository transferRepository;
    private final IdempotencyStore idempotencyStore;
    private final AccountService accountService;
    private final TransferRateLimiter rateLimiter;
    private final TransferProperties properties;
    private final ObjectMapper objectMapper;
    private final dev.payvero.api.LedgerMetrics metrics;

    public TransferService(TransferWriter writer,
                           TransferRepository transferRepository,
                           IdempotencyStore idempotencyStore,
                           AccountService accountService,
                           TransferRateLimiter rateLimiter,
                           TransferProperties properties,
                           ObjectMapper objectMapper,
                           dev.payvero.api.LedgerMetrics metrics) {
        this.metrics = metrics;
        this.writer = writer;
        this.transferRepository = transferRepository;
        this.idempotencyStore = idempotencyStore;
        this.accountService = accountService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public TransferResponse createTransfer(UUID userId, CreateTransferRequest request, String idempotencyKey) {
        // Ownership first, so a caller cannot even discover whether an account
        // exists by watching which error a spent rate limit or a reused key gives.
        accountService.requireOwnedBy(request.sourceAccountId(), userId);

        return execute(
                userId,
                request.sourceAccountId(),
                idempotencyKey,
                hashRequest(request.sourceAccountId(), request.destinationAccountId(), request.amountMinorUnits()),
                () -> writeWithRetry(
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        request.amountMinorUnits()));
    }

    /** Money entering the platform: a transfer whose source is the treasury. */
    public TransferResponse deposit(UUID userId, UUID accountId, long amount, String idempotencyKey) {
        accountService.requireOwnedBy(accountId, userId);
        return execute(
                userId,
                accountId,
                idempotencyKey,
                hashRequest(Account.TREASURY_ID, accountId, amount),
                () -> writeWithRetry(Account.TREASURY_ID, accountId, amount));
    }

    /** Money leaving the platform, the mirror of {@link #deposit}. */
    public TransferResponse withdraw(UUID userId, UUID accountId, long amount, String idempotencyKey) {
        accountService.requireOwnedBy(accountId, userId);
        return execute(
                userId,
                accountId,
                idempotencyKey,
                hashRequest(accountId, Account.TREASURY_ID, amount),
                () -> writeWithRetry(accountId, Account.TREASURY_ID, amount));
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> listForUser(UUID userId, int page, int size) {
        Set<UUID> accountIds = accountIdsOf(userId);
        if (accountIds.isEmpty()) {
            return Page.empty();
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return transferRepository.findListItemsForAccounts(List.copyOf(accountIds), pageable)
                .map(item -> TransferResponse.forViewer(item, accountIds));
    }

    @Transactional(readOnly = true)
    public TransferResponse requireVisibleTo(UUID transferId, UUID userId) {
        TransferListItem item = transferRepository.findListItem(transferId)
                .orElseThrow(TransferNotFoundException::new);

        Set<UUID> accountIds = accountIdsOf(userId);
        boolean involvesCaller = accountIds.contains(item.sourceAccountId())
                || accountIds.contains(item.destinationAccountId());
        if (!involvesCaller) {
            throw new TransferNotFoundException();
        }
        return TransferResponse.forViewer(item, accountIds);
    }

    private Set<UUID> accountIdsOf(UUID userId) {
        return accountService.accountsOf(userId).stream().map(Account::getId).collect(Collectors.toSet());
    }

    /**
     * Re-reads the transfer through the projection so a freshly created one is
     * described exactly like one fetched later, rather than through a second
     * mapping that could drift. The viewer's side is known without a lookup:
     * it is the account the request was authorised against.
     */
    private TransferResponse describe(Transfer transfer, UUID viewerAccountId) {
        return transferRepository.findListItem(transfer.getId())
                .map(item -> TransferResponse.forViewer(item, Set.of(viewerAccountId)))
                .orElseThrow(TransferNotFoundException::new);
    }

    /**
     * The idempotency handshake. A replay short circuits before the rate limit
     * is touched: a retry of an answered request is not new work and should not
     * spend the caller's allowance.
     */
    private TransferResponse execute(UUID userId, UUID viewerAccountId, String idempotencyKey,
                                     String requestHash, java.util.function.Supplier<Transfer> work) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            rateLimiter.requireSlot(userId);
            return describe(work.get(), viewerAccountId);
        }

        IdempotencyClaim claim = idempotencyStore.claim(idempotencyKey, userId, requestHash);
        if (claim.isReplay()) {
            metrics.idempotencyReplay();
            return objectMapper.readValue(claim.replayBody(), TransferResponse.class);
        }

        rateLimiter.requireSlot(userId);
        try {
            Transfer transfer = work.get();
            TransferResponse response = describe(transfer, viewerAccountId);
            idempotencyStore.complete(claim.recordId(), 201,
                    objectMapper.writeValueAsString(response), transfer.getId());
            return response;
        } catch (RuntimeException e) {
            // Release rather than store the failure, so the caller can retry the
            // same key. A claim abandoned after a failure would lock the key out
            // permanently with nothing to replay.
            idempotencyStore.release(claim.recordId());
            throw e;
        }
    }

    /**
     * Bounded retry on a lost version race. Bounded because contention is not
     * an error worth hiding forever: past a few attempts the honest answer is a
     * conflict, not a request that never returns.
     */
    private Transfer writeWithRetry(UUID sourceAccountId, UUID destinationAccountId, long amount) {
        OptimisticLockingFailureException lastFailure = null;

        for (int attempt = 0; attempt <= properties.maxOptimisticRetries(); attempt++) {
            try {
                Transfer written = writer.write(sourceAccountId, destinationAccountId, amount);
                metrics.transferRecorded(written.getStatus().name());
                return written;
            } catch (OptimisticLockingFailureException e) {
                metrics.optimisticRetry();
                lastFailure = e;
            }
        }
        throw lastFailure;
    }

    /**
     * Binds a key to the exact request it answered. Two different requests
     * carrying one key must be distinguishable, or a client bug turns into a
     * wrong answer served with confidence.
     */
    static String hashRequest(UUID sourceAccountId, UUID destinationAccountId, long amount) {
        String canonical = sourceAccountId + "|" + destinationAccountId + "|" + amount;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
