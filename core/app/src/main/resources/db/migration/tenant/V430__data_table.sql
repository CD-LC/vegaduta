-- V430 (assigned to the workflow Data Tables lane; one Flyway namespace is shared
-- across controlplane + tenant dirs). Tenant schema: unqualified, no tenant_id.
--
-- data_table - a tenant-scoped, spreadsheet-like table that workflows (the
-- data_table node) and agents (data_table_query / data_table_write) can read and
-- write so an automation keeps state without an external database. `columns` is
-- the ordered schema: [{"name": "...", "type": "text|number|boolean|date|json"}].
-- The feature is OFF by default (app.data-tables.enabled); this DDL is inert until
-- then. Row counts are computed on read (no cached counter to drift).

CREATE TABLE IF NOT EXISTS data_table (
    id           UUID PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    description  TEXT,
    columns      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

-- A table name is unique per tenant, case-insensitively ("Orders" and "orders" are one table).
CREATE UNIQUE INDEX IF NOT EXISTS uq_data_table_name_lower ON data_table (lower(name));
