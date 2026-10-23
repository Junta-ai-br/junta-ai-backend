-- Persistent completion marker; never infer initialization from category count.
-- Existing accounts stay pending until their first controlled initialization.
-- Constant DEFAULT uses PostgreSQL's additive column default; no category backfill.
ALTER TABLE users
    ADD COLUMN initial_categories_initialized BOOLEAN NOT NULL DEFAULT FALSE;
