-- V431 (assigned to the workflow Data Tables lane). Rows of a data_table (V430).
--
-- `data` is one JSON object keyed by column name; the service validates every value
-- against the table's declared column types before it is written, and enforces the
-- per-table row cap (app.data-tables.max-rows-per-table) - the database only
-- guarantees the parent exists and that deleting a table deletes its rows.
-- No GIN index on `data`: the service filters with ->> / CAST expressions, which a
-- jsonb_path_ops GIN cannot serve, so it would only add write cost.

CREATE TABLE IF NOT EXISTS data_table_row (
    id          UUID PRIMARY KEY,
    table_id    UUID         NOT NULL REFERENCES data_table (id) ON DELETE CASCADE,
    data        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_data_table_row_table ON data_table_row (table_id, created_at, id);
