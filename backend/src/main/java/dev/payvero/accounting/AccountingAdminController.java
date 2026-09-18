package dev.payvero.accounting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Re-deriving every balance is deliberately not something an ordinary user can
 * trigger: it is a full scan, and it reports on accounts that are not theirs.
 * <p>
 * The role is enforced twice, by the URL rule in the filter chain and by
 * {@code @PreAuthorize} here. The duplication is deliberate: a future edit to a
 * path pattern should not be able to quietly expose this.
 */
@Tag(name = "Admin: ledger", description = "Operator view of ledger integrity")
@RestController
@RequestMapping("/api/admin/ledger")
@PreAuthorize("hasRole('ADMIN')")
public class AccountingAdminController {

    private final AccountingService ledgerService;

    public AccountingAdminController(AccountingService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Operation(summary = "Re-derive and verify the whole ledger",
            description = """
                    Recomputes every account balance from entries and re-checks every transfer's
                    debit and credit symmetry, trusting no cached column. Reports the net of all
                    signed entries, which must be exactly zero. ADMIN only.""")
    @GetMapping("/integrity")
    IntegrityReport integrity() {
        return ledgerService.checkIntegrity();
    }
}
