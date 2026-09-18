CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    account_type VARCHAR(20) NOT NULL DEFAULT 'USER',
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    cached_balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_user_required_for_user_accounts
        CHECK (account_type <> 'USER' OR user_id IS NOT NULL),
    CONSTRAINT accounts_system_has_no_owner
        CHECK (account_type <> 'TREASURY' OR user_id IS NULL)
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);

CREATE UNIQUE INDEX idx_accounts_owner_currency ON accounts(user_id, currency)
    WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX idx_accounts_single_treasury ON accounts((account_type))
    WHERE account_type = 'TREASURY';

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_journal_entries_account_id ON journal_entries(account_id);
CREATE INDEX idx_journal_entries_transfer_id ON journal_entries(transfer_id);

CREATE OR REPLACE FUNCTION reject_journal_entry_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'journal_entries is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_journal_entry_mutation();

INSERT INTO accounts (id, user_id, account_type, currency, cached_balance, version, status)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'TREASURY', 'USD', 0, 0, 'ACTIVE');
