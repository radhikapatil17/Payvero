package dev.payvero.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One consumed event, exactly as it was recorded and never since changed")
public record AuditLogResponse(
        UUID id,
        @Schema(description = "The originating outbox row. Unique here, which is what makes redelivery harmless")
        UUID eventId,
        @Schema(example = "TRANSFER_CREATED")
        String eventType,
        @Schema(example = "TRANSFER")
        String aggregateType,
        UUID aggregateId,
        @Schema(description = """
                Who the event names, resolved to an email rather than left as an id. Null when no
                person acted: a scheduled or system-originated event has no actor, and inventing
                one would be worse than admitting there is none.""",
                example = "admin@payvero.dev")
        String actor,
        @Schema(description = "The stored event, as an object. Clients should not have to parse a string")
        JsonNode payload,
        @Schema(description = "Where this arrived from. Null for events recorded without a broker round trip")
        String kafkaTopic,
        Integer kafkaPartition,
        Long kafkaOffset,
        @Schema(description = "Processing time: when the consumer wrote this row, not when the event happened")
        Instant recordedAt
) {
}
