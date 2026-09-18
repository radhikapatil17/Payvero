package dev.payvero.fraud;

import dev.payvero.fraud.dto.FraudFlagResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Review is an operator function, so the role is enforced twice: by the URL rule
 * in the filter chain and again here, in case a path pattern is ever edited.
 */
@Tag(name = "Admin: fraud", description = "Reviewing flagged transfers")
@RestController
@RequestMapping("/api/admin/fraud-flags")
@PreAuthorize("hasRole('ADMIN')")
public class FraudAdminController {

    private final FraudReviewService reviewService;

    public FraudAdminController(FraudReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "The review queue", description = "Filter by status to see only what is still open.")
    @GetMapping
    Page<FraudFlagResponse> queue(@RequestParam(required = false) FraudFlagStatus status,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return reviewService.queue(status, page, size);
    }

    @Operation(summary = "Clear a flag",
            description = """
                    Records that a human judged this legitimate. Writes an audit event naming the
                    reviewer, and changes no money: a flag is advisory, never a correction.""")
    @PostMapping("/{flagId}/clear")
    FraudFlagResponse clear(@AuthenticationPrincipal UUID reviewerId, @PathVariable UUID flagId) {
        return reviewService.review(flagId, FraudFlagStatus.CLEARED, reviewerId);
    }

    @Operation(summary = "Confirm a flag",
            description = """
                    Records that a human judged this fraudulent. Audited the same way, and equally
                    does not move money: reversing a confirmed fraud is a new opposing transfer.""")
    @PostMapping("/{flagId}/confirm")
    FraudFlagResponse confirm(@AuthenticationPrincipal UUID reviewerId, @PathVariable UUID flagId) {
        return reviewService.review(flagId, FraudFlagStatus.CONFIRMED, reviewerId);
    }
}
