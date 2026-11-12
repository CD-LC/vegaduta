-- V683: memory_consolidation_runs + memory_tombstone - the nightly memory consolidation sweep
-- (phase 1 design 03, sections 4 and 6; ADR-0028 WP5b).
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace
-- with controlplane/.
--
-- memory_consolidation_runs claims (subject, run_date) so one person is consolidated once a day
-- across replicas, and records the run's report: what the planner would forget, merge or
-- summarise, in which mode. Holds counts only, never memory text.
--
-- memory_tombstone is the only state change the consolidation apply path may make: a "forgotten"
-- marker with the content's SHA-256 and metadata, never the text. The sweep marks rows forgotten
-- through tombstones; it never deletes a memory row and can never set one TRUSTED or ACTIVE.
-- restorable_until/restore-blob undo (design 03 section 6) and recall-side filtering land with a
-- later work package.

CREATE TABLE IF NOT EXISTS memory_consolidation_runs (
    subject      VARCHAR(255) NOT NULL,
    run_date     DATE         NOT NULL,
    mode         VARCHAR(16)  NOT NULL DEFAULT 'dry_run',
    status       VARCHAR(16)  NOT NULL DEFAULT 'running',
    rows_seen    INTEGER      NOT NULL DEFAULT 0,
    forgets      INTEGER      NOT NULL DEFAULT 0,
    merges       INTEGER      NOT NULL DEFAULT 0,
    summaries    INTEGER      NOT NULL DEFAULT 0,
    deferred     INTEGER      NOT NULL DEFAULT 0,
    tombstoned   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (subject, run_date)
);

CREATE TABLE IF NOT EXISTS memory_tombstone (
    id                UUID         PRIMARY KEY,
    subject_key       VARCHAR(255) NOT NULL,
    memory_kind       VARCHAR(16)  NOT NULL,   -- agent | shared
    agent_id          UUID,
    original_id       UUID,
    content_sha256    VARCHAR(64),
    reason            VARCHAR(64)  NOT NULL,   -- DECAY | QUARANTINE_TTL | MERGED_INTO:<id>
    actor             VARCHAR(64)  NOT NULL,   -- system:memory-consolidation
    score_at_delete   NUMERIC(4,3),
    trust_at_delete   VARCHAR(16),
    derived_from      UUID[],
    deleted_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    restorable_until  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_memory_tombstone_subject
    ON memory_tombstone (subject_key, deleted_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_tombstone_lookup
    ON memory_tombstone (subject_key, content_sha256);
