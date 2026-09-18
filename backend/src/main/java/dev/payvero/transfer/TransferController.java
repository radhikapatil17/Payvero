package dev.payvero.transfer;

import dev.payvero.api.ErrorResponse;
import dev.payvero.transfer.dto.CreateTransferRequest;
import dev.payvero.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Transfers", description = "Moving money between accounts")
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * {@code Idempotency-Key} is optional but strongly advised: without it a
     * retried request is a second transfer. It is a header rather than a body
     * field because it describes the delivery of the request, not the money.
     */
    @Operation(
            summary = "Create a transfer",
            description = """
                    Debits the source account and credits the destination as one balanced pair,
                    in a single transaction. The response is PENDING: the money has already
                    moved, and settlement flips the status shortly afterwards.

                    **Idempotency-Key.** Optional but strongly advised, because without it a
                    retried request is a second transfer. Send the same key with the same body
                    and you get the original response back with nothing new created. Send the
                    same key with a *different* body and the request is refused with 422, since
                    the key has already been bound to another request. If an identical request
                    carrying that key is still in flight you get 409 and should retry.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer accepted, or the stored response for a replayed key"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, expired or invalid token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Source account does not exist or is not yours",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "An identical request with this key is still running, or the account changed mid-flight",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Insufficient funds, or this idempotency key was already used for a different request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many transfers in the current window",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    ResponseEntity<TransferResponse> create(
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "Client-generated key making this request safe to retry",
                    example = "8f14e45f-ceea-467a-9575-1a1b2c3d4e5f")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {

        TransferResponse response = transferService.createTransfer(userId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List your transfers",
            description = "Newest first, across every account you own, as sender or recipient.")
    @GetMapping
    Page<TransferResponse> list(@AuthenticationPrincipal UUID userId,
                                @RequestParam(defaultValue = "0") int page,
                                @Parameter(description = "Capped at 100") @RequestParam(defaultValue = "20") int size) {
        return transferService.listForUser(userId, page, size);
    }

    @Operation(summary = "Fetch one transfer",
            description = """
                    Visible if any of your accounts is the source or the destination. A transfer
                    that exists but involves neither returns 404 rather than 403, so transfer ids
                    cannot be probed for existence.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "No such transfer, or none of your accounts is a party to it",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{transferId}")
    TransferResponse one(@AuthenticationPrincipal UUID userId, @PathVariable UUID transferId) {
        return transferService.requireVisibleTo(transferId, userId);
    }
}
