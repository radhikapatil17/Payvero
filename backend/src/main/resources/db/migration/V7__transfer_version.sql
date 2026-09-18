-- Settlement writes SETTLED and fraud detection writes FLAGGED, from different
-- threads reacting to different triggers. Without a version column the second
-- write silently overwrites the first: the state machine of the core domain
-- object was last-write-wins, which is the one place in this system that had no
-- concurrency control while balances two tables over had it.
--
-- Existing rows start at 0, matching the default Hibernate assigns to a freshly
-- loaded entity, so no backfill is needed.
ALTER TABLE transfers ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
