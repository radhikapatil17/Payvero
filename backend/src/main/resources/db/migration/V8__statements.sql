-- A statement is a closed-period artefact derived entirely from ledger_entries.
-- It stores figures rather than recomputing them on every read, which only
-- makes sense if those figures can never drift: hence immutable, and hence
-- generated only for periods that have ended.
CREATE TABLE statements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    -- VARCHAR, not CHAR: CHAR maps to bpchar and Hibernate validates a String
    -- field as varchar, which is a startup failure rather than a subtle bug.
    period VARCHAR(7) NOT NULL CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    opening_balance BIGINT NOT NULL,
    closing_balance BIGINT NOT NULL,
    entry_count INTEGER NOT NULL,
    line_items JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One statement per account per period, forever. This is what makes
    -- regeneration idempotent rather than a way to quietly restate history.
    CONSTRAINT statements_one_per_account_period UNIQUE (account_id, period)
);

CREATE INDEX idx_statements_account ON statements(account_id, period DESC);

-- Same reasoning as ledger_entries and audit_log. A statement that can be
-- rewritten is a claim about the past that the past cannot contradict; the
-- correction for a wrong statement is a corrected ledger and a new period,
-- not an edit. Reuses the trigger function introduced in V5.
CREATE TRIGGER statements_immutable
    BEFORE UPDATE OR DELETE ON statements
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
