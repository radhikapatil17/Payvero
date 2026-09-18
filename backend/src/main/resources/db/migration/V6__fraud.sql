-- A flag is a marker for human review, never a correction. Nothing here touches
-- ledger_entries or a balance: money that has moved stays moved, and a reversal
-- would be a new opposing transfer rather than an edit to history.
CREATE TABLE fraud_flags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID NOT NULL REFERENCES transfers(id) ON DELETE RESTRICT,
    rule VARCHAR(30) NOT NULL CHECK (rule IN ('VELOCITY_COUNT', 'VELOCITY_AMOUNT')),
    details JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLEARED', 'CONFIRMED')),
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Events arrive at least once, so the detector will sometimes see the same
    -- transfer twice. This is what makes a redelivery collide instead of
    -- raising a second identical flag against the same transfer.
    CONSTRAINT fraud_flags_one_per_rule_per_transfer UNIQUE (transfer_id, rule),

    -- A decision has to have an author. An OPEN flag has no reviewer yet; a
    -- resolved one cannot be anonymous.
    CONSTRAINT fraud_flags_resolved_flags_name_a_reviewer
        CHECK (status = 'OPEN' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);

CREATE INDEX idx_fraud_flags_status ON fraud_flags(status) WHERE status = 'OPEN';
CREATE INDEX idx_fraud_flags_transfer ON fraud_flags(transfer_id);
CREATE INDEX idx_fraud_flags_created_at ON fraud_flags(created_at DESC);
