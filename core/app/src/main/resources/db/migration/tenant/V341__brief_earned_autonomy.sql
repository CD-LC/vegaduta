-- V341 (tenant dir): Earned Autonomy foundation - the Brief manifest and its lifecycle.
--
-- VERSION CLAIM: V341. Flyway's controlplane/ and tenant/ dirs are ONE version namespace;
-- at authoring time the highest were tenant V340 and controlplane V339. Tenant schema: no
-- schema qualifier, no tenant_id column (schema-per-tenant does the scoping).
--
-- brief             - one row per solution; current_version points at brief_version.
-- brief_version     - immutable manifest snapshots (manifest_json = brief-schema.v1.json instance).
-- brief_apply_step  - one row per apply/compensate step, grouped by job_id (written by the apply engine).
-- brief_binding     - ref (a1, w1, ...) -> the real entity id it became at apply time.
-- brief_trust_event - every rung change/proposal with its evidence (written by the trust engine).

CREATE TABLE IF NOT EXISTS brief (
    id              UUID PRIMARY KEY,
    title           VARCHAR(120) NOT NULL,
    goal            TEXT         NOT NULL,
    locale          VARCHAR(16)  NOT NULL DEFAULT 'en',
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    current_version INTEGER      NOT NULL DEFAULT 1,
    rung            VARCHAR(16)  NOT NULL DEFAULT 'UNDER_WATCH',
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_brief_status CHECK (status IN ('DRAFT', 'APPLIED', 'FAILED', 'ARCHIVED')),
    CONSTRAINT chk_brief_rung CHECK (rung IN ('UNDER_WATCH', 'TRUSTED', 'AUTONOMOUS'))
);

CREATE INDEX IF NOT EXISTS idx_brief_updated_at ON brief (updated_at DESC);

CREATE TABLE IF NOT EXISTS brief_version (
    id             UUID PRIMARY KEY,
    brief_id       UUID         NOT NULL REFERENCES brief (id) ON DELETE CASCADE,
    version        INTEGER      NOT NULL,
    manifest_json  JSONB        NOT NULL,
    parent_version INTEGER,
    note           TEXT,
    created_by     VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_brief_version UNIQUE (brief_id, version)
);

CREATE TABLE IF NOT EXISTS brief_apply_step (
    id             UUID PRIMARY KEY,
    brief_id       UUID         NOT NULL REFERENCES brief (id) ON DELETE CASCADE,
    version        INTEGER      NOT NULL,
    job_id         UUID         NOT NULL,
    seq            INTEGER      NOT NULL,
    kind           VARCHAR(32)  NOT NULL,
    ref            VARCHAR(32),
    entity_id      VARCHAR(64),
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    error          TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,
    compensated_at TIMESTAMPTZ,
    CONSTRAINT chk_brief_apply_step_status CHECK (status IN ('PENDING', 'DONE', 'FAILED', 'COMPENSATED'))
);

CREATE INDEX IF NOT EXISTS idx_brief_apply_step_job ON brief_apply_step (brief_id, job_id, seq);

CREATE TABLE IF NOT EXISTS brief_binding (
    brief_id  UUID         NOT NULL REFERENCES brief (id) ON DELETE CASCADE,
    ref       VARCHAR(32)  NOT NULL,
    kind      VARCHAR(32)  NOT NULL,
    entity_id VARCHAR(64)  NOT NULL,
    PRIMARY KEY (brief_id, ref)
);

CREATE TABLE IF NOT EXISTS brief_trust_event (
    id            UUID PRIMARY KEY,
    brief_id      UUID         NOT NULL REFERENCES brief (id) ON DELETE CASCADE,
    from_rung     VARCHAR(16),
    to_rung       VARCHAR(16),
    kind          VARCHAR(16)  NOT NULL,
    evidence_json JSONB,
    version       INTEGER,
    actor         VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_brief_trust_event_kind CHECK (kind IN ('PROPOSED', 'PROMOTED', 'DEMOTED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_brief_trust_event_brief ON brief_trust_event (brief_id, created_at DESC);
