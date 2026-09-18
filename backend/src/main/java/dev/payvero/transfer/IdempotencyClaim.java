package dev.payvero.transfer;

import java.util.UUID;

/**
 * @param replayBody the stored response when this key has already completed,
 *                   otherwise null, meaning the caller now holds the claim and
 *                   is responsible for completing or releasing it
 */
public record IdempotencyClaim(UUID recordId, String replayBody) {

    public boolean isReplay() {
        return replayBody != null;
    }
}
