-- The outbox is written inside the same transaction as the business data it
-- describes. That is the entire point: publishing to Kafka from inside a
-- database transaction is a dual write, and a dual write can always fail in the
-- middle, leaving an event with no transfer or a transfer with no event. Here
-- there is one commit. If it succeeds both exist; if it fails neither does.
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500)
);

-- Partial index: the poller only ever looks for unpublished rows, so the index
-- stays small no matter how much history accumulates.
CREATE INDEX idx_outbox_pending ON outbox(created_at) WHERE published_at IS NULL;

-- event_id is the outbox row id, carried through Kafka and unique here. That
-- constraint is what turns at-least-once delivery into an at-most-once effect:
-- the outbox pattern cannot promise exactly-once, so the consumer has to be
-- idempotent, and a redelivered event collides rather than duplicating.
CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    kafka_topic VARCHAR(100),
    kafka_partition INTEGER,
    kafka_offset BIGINT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_log_records_each_event_once UNIQUE (event_id)
);

CREATE INDEX idx_audit_log_aggregate ON audit_log(aggregate_id);
CREATE INDEX idx_audit_log_recorded_at ON audit_log(recorded_at DESC);

-- Same reasoning as ledger_entries: an audit trail that can be edited is not an
-- audit trail. Generic over the table so later append-only tables can reuse it.
-- The outbox is deliberately not protected, because publishing has to mark rows.
CREATE OR REPLACE FUNCTION reject_append_only_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not permitted', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
