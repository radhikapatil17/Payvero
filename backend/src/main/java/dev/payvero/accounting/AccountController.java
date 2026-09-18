package dev.payvero.accounting;

import dev.payvero.accounting.dto.AccountResponse;
import dev.payvero.accounting.dto.BalanceResponse;
import dev.payvero.accounting.dto.DepositRequest;
import dev.payvero.accounting.dto.OpenAccountRequest;
import dev.payvero.transfer.TransferService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Accounts", description = "Opening accounts, balances, and funding")
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountingService ledgerService;
    private final TransferService transferService;

    public AccountController(AccountService accountService,
                             AccountingService ledgerService,
                             TransferService transferService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.transferService = transferService;
    }

    @Operation(summary = "List your accounts", description = "Every account you own, with its cached balance.")
    @GetMapping
    List<AccountResponse> myAccounts(@AuthenticationPrincipal UUID userId) {
        return accountService.accountsOf(userId).stream().map(AccountResponse::from).toList();
    }

    @Operation(summary = "Open an account",
            description = "Idempotent: one account per currency, so asking twice returns the existing one.")
    @PostMapping
    ResponseEntity<AccountResponse> openAccount(@AuthenticationPrincipal UUID userId,
                                                @Valid @RequestBody(required = false) OpenAccountRequest request) {
        String currency = request == null ? null : request.currency();
        Account account = accountService.openAccount(userId, currency);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    /**
     * Ownership is resolved before anything is read, so one user cannot probe
     * another's balances by guessing account ids. An account belonging to
     * someone else is reported as not found rather than forbidden, which avoids
     * confirming that the id exists at all.
     */
    @Operation(summary = "Balance for an account",
            description = """
                    Returns the authoritative balance derived from ledger entries alongside the
                    cached figure, and a flag saying whether they agree. Someone else's account
                    is reported as not found rather than forbidden, so ids cannot be probed.""")
    @GetMapping("/{accountId}/balance")
    BalanceResponse balance(@AuthenticationPrincipal UUID userId, @PathVariable UUID accountId) {
        Account account = accountService.requireOwnedBy(accountId, userId);
        return BalanceResponse.of(
                account.getId(),
                account.getCurrency(),
                ledgerService.derivedBalance(account.getId()),
                account.getCachedBalance());
    }

    /**
     * Funding routes through the transfer domain rather than writing entries
     * directly, so a deposit gets the same idempotency, rate limiting and
     * lifecycle as any other movement of money.
     */
    @Operation(summary = "Fund an account",
            description = """
                    Credits the account against the system treasury as a balanced pair, so a
                    deposit is a real two-sided movement rather than money appearing.

                    This deployment has no external funding source: deposits mint money and are
                    bounded only by a per-movement ceiling and a rate limit. It is a sandbox.""")
    @PostMapping("/{accountId}/deposits")
    ResponseEntity<BalanceResponse> deposit(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {

        transferService.deposit(userId, accountId, request.amountMinorUnits(), idempotencyKey);

        Account refreshed = accountService.require(accountId);
        return ResponseEntity.status(HttpStatus.CREATED).body(BalanceResponse.of(
                refreshed.getId(),
                refreshed.getCurrency(),
                ledgerService.derivedBalance(accountId),
                refreshed.getCachedBalance()));
    }
}
