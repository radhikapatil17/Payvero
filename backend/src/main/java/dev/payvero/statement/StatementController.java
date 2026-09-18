package dev.payvero.statement;

import dev.payvero.accounting.AccountService;
import dev.payvero.statement.dto.StatementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Tag(name = "Statements", description = "Closed-period statements derived from ledger entries")
@RestController
@RequestMapping("/api/accounts/{accountId}/statements")
public class StatementController {

    private final StatementService statementService;
    private final AccountService accountService;

    public StatementController(StatementService statementService, AccountService accountService) {
        this.statementService = statementService;
        this.accountService = accountService;
    }

    @Operation(summary = "List statements", description = "Newest period first.")
    @GetMapping
    List<StatementResponse> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID accountId) {
        accountService.requireOwnedBy(accountId, userId);
        return statementService.listFor(accountId);
    }

    @Operation(summary = "Fetch one statement", description = "Period is YYYY-MM, for example 2026-05.")
    @GetMapping("/{period}")
    StatementResponse one(@AuthenticationPrincipal UUID userId,
                          @PathVariable UUID accountId,
                          @PathVariable String period) {
        accountService.requireOwnedBy(accountId, userId);
        return statementService.require(accountId, parse(period));
    }

    /**
     * Returns 200 rather than 201 when the statement already existed, so a
     * caller can tell whether this request generated it. The figures are
     * identical either way: regeneration reads, it does not recompute.
     */
    @Operation(summary = "Generate a statement",
            description = """
                    Derives opening and closing balances from ledger entries, never from a cached
                    balance, so the figures are provable from primary records.

                    Statements are immutable once issued and generation is idempotent: asking
                    again returns the stored figures unchanged rather than recomputing them, even
                    if entries have since landed in that period. Only closed periods can be
                    generated; a month still in progress returns 409.""")
    @PostMapping("/{period}")
    ResponseEntity<StatementResponse> generate(@AuthenticationPrincipal UUID userId,
                                               @PathVariable UUID accountId,
                                               @PathVariable String period) {
        accountService.requireOwnedBy(accountId, userId);
        YearMonth parsed = parse(period);

        boolean existed = statementService.exists(accountId, parsed);
        StatementResponse statement = statementService.generate(accountId, parsed);
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED).body(statement);
    }

    private static YearMonth parse(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new InvalidPeriodException(period);
        }
    }
}
