-- created_at answers "when did we record this event"; occurred_at answers "when
-- did the thing happen". They coincide for live traffic and diverge the moment
-- anything is backdated or replayed, and conflating them made a consumer that
-- reasons about time — fraud velocity — read a drained backlog as a burst that
-- never took place.
ALTER TABLE outbox ADD COLUMN occurred_at TIMESTAMPTZ;
UPDATE outbox SET occurred_at = created_at WHERE occurred_at IS NULL;
ALTER TABLE outbox ALTER COLUMN occurred_at SET NOT NULL;
