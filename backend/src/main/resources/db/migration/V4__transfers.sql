CREATE TABLE transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    destination_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SETTLED', 'FAILED', 'FLAGGED')),
    failure_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ,
    CONSTRAINT transfers_move_between_two_accounts
        CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX idx_transfers_source ON transfers(source_account_id);
CREATE INDEX idx_transfers_destination ON transfers(destination_account_id);
CREATE INDEX idx_transfers_status ON transfers(status);
CREATE INDEX idx_transfers_created_at ON transfers(created_at DESC);

-- Deferred from V3, where transfers did not exist yet. Every ledger entry now
-- has to name a real transfer, so there is no way to post entries that belong
-- to nothing: a deposit is a transfer from the treasury, not a special case.
ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_transfer_id_fkey
    FOREIGN KEY (transfer_id) REFERENCES transfers(id) ON DELETE RESTRICT;

-- The stored response is what makes a retry genuinely free: the second caller
-- gets the first caller's answer rather than a fresh execution. request_hash is
-- what distinguishes "the same request again" from "a different request reusing
-- a key", which is a client bug and must be refused rather than served.
--
-- The row is claimed before the work runs, not written after it. Writing after
-- would leave a window in which two concurrent requests carrying one key both
-- pass the lookup and both execute, which is the precise failure idempotency is
-- supposed to prevent. The unique constraint decides the winner; the loser sees
-- IN_PROGRESS and is told to retry rather than being allowed to double spend.
CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_hash VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INTEGER,
    response_body JSONB,
    transfer_id UUID REFERENCES transfers(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT idempotency_keys_scoped_to_user UNIQUE (idempotency_key, user_id),
    CONSTRAINT idempotency_keys_completed_rows_carry_a_response
        CHECK (state <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);

CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys(created_at);
