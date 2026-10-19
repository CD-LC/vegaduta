-- V337 (claimed for the built-in Leads CRM stream; global Flyway namespace is
-- shared across controlplane + tenant dirs - V335/V336/V338 belong to other
-- streams). Tenant schema: no schema qualifier, no tenant_id column.
--
-- lead          - the pipeline record (NEW -> ... -> WON/LOST) behind /admin/leads.
-- lead_activity - append-only timeline (notes, emails, stage changes, calls, tasks).

CREATE TABLE IF NOT EXISTS lead (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(255),
    email              VARCHAR(320),
    phone              VARCHAR(64),
    company            VARCHAR(255),
    title              VARCHAR(255),
    source             VARCHAR(64),
    stage              VARCHAR(16)  NOT NULL DEFAULT 'NEW',
    score              INTEGER      NOT NULL DEFAULT 0,
    owner              VARCHAR(255),
    value              NUMERIC(14, 2),
    tags               TEXT,
    notes              TEXT,
    last_contacted_at  TIMESTAMPTZ,
    last_replied_at    TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_email_lower ON lead (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_lead_stage ON lead (stage);
CREATE INDEX IF NOT EXISTS idx_lead_created_at ON lead (created_at DESC);

CREATE TABLE IF NOT EXISTS lead_activity (
    id          UUID PRIMARY KEY,
    lead_id     UUID         NOT NULL REFERENCES lead (id) ON DELETE CASCADE,
    type        VARCHAR(16)  NOT NULL,
    body        TEXT,
    meta        JSONB,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_lead_activity_lead ON lead_activity (lead_id, created_at DESC);
