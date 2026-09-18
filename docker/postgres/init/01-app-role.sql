-- Two roles, deliberately.
--
--   payvero      owns the schema and runs Flyway. Migrations need DDL, and DDL
--                is exactly the power the running application must not have.
--   payvero_app  the runtime connection. Reads and writes rows and nothing else.
--
-- The distinction matters because a table's owner can always run
-- ALTER TABLE ... DISABLE TRIGGER, which would switch off the append-only
-- guards on journal_entries and audit_log. Those guards are only meaningful if
-- the process most likely to be compromised cannot turn them off. Splitting the
-- roles is what makes "the database refuses to rewrite history" a real claim
-- rather than a claim that holds until someone reaches the application.
--
-- Runs automatically on a fresh volume. Applying it to an existing database is
-- documented in the README.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payvero_app') THEN
        CREATE ROLE payvero_app LOGIN PASSWORD 'payvero_app_dev';
    END IF;
END
$$;

-- No CREATEDB, no CREATEROLE, no SUPERUSER, and explicitly no ability to create
-- objects of its own in the schema, so it can never own anything.
ALTER ROLE payvero_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

GRANT CONNECT ON DATABASE payvero TO payvero_app;
GRANT USAGE ON SCHEMA public TO payvero_app;
REVOKE CREATE ON SCHEMA public FROM payvero_app;

-- Row level access to everything that exists today.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO payvero_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO payvero_app;

-- And to everything a future migration creates, so a new table is not silently
-- unreadable by the application until someone remembers to grant it.
ALTER DEFAULT PRIVILEGES FOR ROLE payvero IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO payvero_app;
ALTER DEFAULT PRIVILEGES FOR ROLE payvero IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO payvero_app;

-- Deliberately not granted: TRUNCATE, which would empty an append-only table
-- without firing its row triggers.
