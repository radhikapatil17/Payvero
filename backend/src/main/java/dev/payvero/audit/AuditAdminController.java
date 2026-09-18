package dev.payvero.audit;

import dev.payvero.audit.dto.AuditLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trail is readable but only readable: there is no write mapping here, and
 * no amount of API surface could add one, because the table itself refuses
 * UPDATE and DELETE. ADMIN only — it spans every user's activity.
 * <p>
 * The role is enforced twice, by the URL rule in the filter chain and by
 * {@code @PreAuthorize} here, so an edit to a path pattern cannot quietly
 * expose one user's history to another.
 */
@Tag(name = "Admin: audit", description = "The append-only record of everything that happened")
@RestController
@RequestMapping("/api/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
public class AuditAdminController {

    private final AuditQueryService auditQueryService;

    public AuditAdminController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @Operation(summary = "Read the audit trail",
            description = """
                    Newest first, by the time the consumer recorded each event. Every row names
                    the actor where the event names one, so a decision can be attributed without
                    joining ids by hand.

                    Ordered by processing time rather than event time on purpose: this view
                    answers "what has this system observed, and when did it know", which is the
                    question an operator investigating a backlog is actually asking.""")
    @GetMapping
    Page<AuditLogResponse> list(@RequestParam(required = false) String aggregateType,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return auditQueryService.page(aggregateType, page, size);
    }
}
