package dev.payvero.audit;

import dev.payvero.audit.dto.AuditLogResponse;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reading the audit trail. There is no writer here on purpose: rows arrive only
 * from {@link AuditLogWriter} consuming the outbox, and the database rejects
 * UPDATE and DELETE outright, so this class could not amend history if it tried.
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository auditLog;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    public AuditQueryService(AuditLogRepository auditLog,
                             AccountRepository accounts,
                             UserRepository users,
                             ObjectMapper objectMapper) {
        this.auditLog = auditLog;
        this.accounts = accounts;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> page(String aggregateType, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<AuditLogEntry> found = aggregateType == null || aggregateType.isBlank()
                ? auditLog.findAllByOrderByRecordedAtDesc(pageable)
                : auditLog.findAllByAggregateTypeOrderByRecordedAtDesc(aggregateType, pageable);

        List<JsonNode> payloads = found.getContent().stream()
                .map(entry -> (JsonNode) objectMapper.readTree(entry.getPayload()))
                .toList();

        ActorDirectory actors = resolveActors(payloads);

        List<AuditLogResponse> described = new ArrayList<>(payloads.size());
        for (int i = 0; i < payloads.size(); i++) {
            AuditLogEntry entry = found.getContent().get(i);
            JsonNode payload = payloads.get(i);
            described.add(new AuditLogResponse(
                    entry.getId(),
                    entry.getEventId(),
                    entry.getEventType(),
                    entry.getAggregateType(),
                    entry.getAggregateId(),
                    actors.actorFor(payload),
                    payload,
                    entry.getKafkaTopic(),
                    entry.getKafkaPartition(),
                    entry.getKafkaOffset(),
                    entry.getRecordedAt()));
        }
        return new org.springframework.data.domain.PageImpl<>(described, pageable, found.getTotalElements());
    }

    /**
     * Resolves every id on the page in two queries rather than two per row.
     * An audit view is read whole pages at a time, so a per-row lookup would
     * turn one screen into a hundred round trips.
     */
    private ActorDirectory resolveActors(List<JsonNode> payloads) {
        Set<UUID> accountIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>();
        for (JsonNode payload : payloads) {
            uuidAt(payload, "sourceAccountId").ifPresent(accountIds::add);
            uuidAt(payload, "destinationAccountId").ifPresent(accountIds::add);
            uuidAt(payload, "reviewedBy").ifPresent(userIds::add);
        }

        Map<UUID, Account> accountsById = new HashMap<>();
        for (Account account : accounts.findAllById(accountIds)) {
            accountsById.put(account.getId(), account);
        }
        Map<UUID, String> emailsByUserId = new HashMap<>();
        for (User user : users.findAllById(userIds)) {
            emailsByUserId.put(user.getId(), user.getEmail());
        }
        return new ActorDirectory(accountsById, emailsByUserId);
    }

    private static java.util.Optional<UUID> uuidAt(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull() || !value.isString()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(value.asString()));
        } catch (IllegalArgumentException e) {
            // A payload is a historical record and is never rewritten, so a
            // malformed id from an older shape has to be tolerated rather than
            // allowed to fail the whole page.
            return java.util.Optional.empty();
        }
    }

    private record ActorDirectory(Map<UUID, Account> accountsById, Map<UUID, String> emailsByUserId) {

        /**
         * Who the event names, by what the payload carries.
         *
         * A reviewed flag names its reviewer outright. A transfer names its
         * source account, whose owner moved the money — except a deposit, whose
         * source is the treasury and which the recipient initiated. Anything
         * else has no person behind it and returns null rather than a guess.
         */
        String actorFor(JsonNode payload) {
            String reviewer = uuidAt(payload, "reviewedBy").map(emailsByUserId::get).orElse(null);
            if (reviewer != null) {
                return reviewer;
            }

            String source = uuidAt(payload, "sourceAccountId").map(this::ownerEmail).orElse(null);
            if (source != null) {
                return source;
            }
            return uuidAt(payload, "destinationAccountId").map(this::ownerEmail).orElse(null);
        }

        private String ownerEmail(UUID accountId) {
            Account account = accountsById.get(accountId);
            if (account == null || account.getUser() == null) {
                return null;
            }
            return account.getUser().getEmail();
        }
    }
}
