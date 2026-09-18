CREATE TABLE schema_sanity (
    id BIGSERIAL PRIMARY KEY,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO schema_sanity (note) VALUES ('Payvero Flyway pipeline works');
