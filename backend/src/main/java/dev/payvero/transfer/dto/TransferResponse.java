package dev.payvero.transfer.dto;

import dev.payvero.accounting.AccountType;
import dev.payvero.transfer.TransferListItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A transfer as one party sees it. {@code direction} and {@code counterparty}
 * are relative to the caller, computed server-side because the server already
 * knows who is asking; a client would otherwise have to hold every account id it
 * owns and derive them, and would still be left rendering a raw uuid for the
 * other party.
 */
@Schema(description = "A transfer, described from the perspective of the caller")
public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        @Schema(description = "DEBIT if this moved money out of your account, CREDIT if in",
                example = "DEBIT")
        String direction,
        @Schema(description = "The other party's account", example = "bob@payvero.dev")
        String counterpartyLabel,
        UUID counterpartyAccountId,
        @Schema(description = "Integer minor units: 25000 is $250.00", example = "25000")
        long amountMinorUnits,
        String currency,
        @Schema(description = "PENDING, SETTLED, FAILED or FLAGGED", example = "SETTLED")
        String status,
        String failureReason,
        Instant createdAt,
        Instant settledAt
) {

    private static final String TREASURY_LABEL = "Treasury";

    /**
     * @param viewerAccountIds the caller's accounts. Whichever side of the
     *                         transfer is not theirs is the counterparty.
     */
    public static TransferResponse forViewer(TransferListItem item, Set<UUID> viewerAccountIds) {
        boolean viewerIsSource = viewerAccountIds.contains(item.sourceAccountId());

        UUID counterpartyId = viewerIsSource ? item.destinationAccountId() : item.sourceAccountId();
        String counterpartyLabel = viewerIsSource
                ? label(item.destinationAccountType(), item.destinationOwnerEmail())
                : label(item.sourceAccountType(), item.sourceOwnerEmail());

        return new TransferResponse(
                item.id(),
                item.sourceAccountId(),
                item.destinationAccountId(),
                viewerIsSource ? "DEBIT" : "CREDIT",
                counterpartyLabel,
                counterpartyId,
                item.amount(),
                item.currency(),
                item.status().name(),
                item.failureReason(),
                item.createdAt(),
                item.settledAt());
    }

    /**
     * The counterparty's email is shown to the other party to the transaction.
     * That is a deliberate disclosure rather than an oversight: someone who sent
     * or received money is already party to it and needs to know who with. It is
     * only ever the counterparty of a transfer the caller is on, never a
     * directory, and no endpoint lets a caller look up an unrelated user.
     */
    private static String label(AccountType type, String ownerEmail) {
        if (type == AccountType.TREASURY) {
            return TREASURY_LABEL;
        }
        return ownerEmail == null ? "Unknown account" : ownerEmail;
    }
}
