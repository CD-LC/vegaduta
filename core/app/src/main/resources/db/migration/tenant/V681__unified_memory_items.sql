-- Unified agent memory, WP3 (ADR-0028 section 5): the store for the three NEW kinds (EPISODIC, PROCEDURAL,
-- SELF_MODEL). SEMANTIC stays in agent_memory / tenant_memory (V18, V77, V676); nothing here is backfilled.
--
-- The invariants the threat model needs live in CHECK constraints, not only in code, so a bug in any writer
-- cannot create an ACTIVE item from untrusted material:
--   * ACTIVE requires TRUSTED;
--   * a TRUSTED item that carries any source taint requires a recorded human approver (approved_by);
--   * an ACTIVE PROCEDURAL item requires an approver, in every posture;
--   * generation (0 = human or raw event, 1 = model summarised events, 2 = model summarised model output, ...)
--     is bounded (the configurable cap, app.memory.unified.max-generation, is enforced in code below this).
-- New rows default to QUARANTINED + PENDING; only a human `approve` in the service moves one to TRUSTED + ACTIVE.
--
-- Raw JDBC tables, no Hibernate mapping (the vector type has no JPA column type; see V18). Tenant schema only.
-- No ANN index, the same call as V18: recall filters by (agent_id, kind) first. All three tables are new, so
-- nothing existing is touched; V681 and V682 are claimed in docs/architecture/ADR-0028.

CREATE TABLE memory_item (
    id               UUID PRIMARY KEY,
    agent_id         UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    kind             VARCHAR(16) NOT NULL,
    content          TEXT NOT NULL,
    summary          VARCHAR(280),
    embedding        vector(384) NOT NULL,
    attrs            JSONB NOT NULL DEFAULT '{}',
    trust            VARCHAR(16) NOT NULL DEFAULT 'QUARANTINED',
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    writer           VARCHAR(16) NOT NULL,
    source_type      VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    source_ref       VARCHAR(512),
    turn_id          VARCHAR(64),
    session_id       VARCHAR(128),
    source_taint     TEXT,
    model_generated  BOOLEAN NOT NULL DEFAULT TRUE,
    generation       SMALLINT NOT NULL DEFAULT 1,
    subject_keys     TEXT[] NOT NULL DEFAULT '{}',
    importance       REAL NOT NULL DEFAULT 0,
    salience         REAL NOT NULL DEFAULT 0,
    pinned           BOOLEAN NOT NULL DEFAULT FALSE,
    version          INT NOT NULL DEFAULT 1,
    supersedes_id    UUID REFERENCES memory_item(id) ON DELETE SET NULL,
    learn_run_id     UUID,
    use_count        INT NOT NULL DEFAULT 0,
    last_used_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    approved_by      VARCHAR(255),
    approved_at      TIMESTAMPTZ,
    CONSTRAINT memory_item_kind_chk   CHECK (kind IN ('EPISODIC', 'PROCEDURAL', 'SELF_MODEL')),
    CONSTRAINT memory_item_trust_chk  CHECK (trust IN ('TRUSTED', 'QUARANTINED')),
    CONSTRAINT memory_item_status_chk CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUPERSEDED', 'REVERTED')),
    CONSTRAINT memory_item_writer_chk CHECK (writer IN ('AGENT', 'HUMAN', 'IMPORT', 'DISTILLER', 'SYSTEM')),
    CONSTRAINT memory_item_active_trusted_chk CHECK (status <> 'ACTIVE' OR trust = 'TRUSTED'),
    CONSTRAINT memory_item_taint_approval_chk CHECK (
        trust <> 'TRUSTED' OR coalesce(source_taint, '[]') = '[]' OR approved_by IS NOT NULL),
    CONSTRAINT memory_item_procedural_chk CHECK (
        kind <> 'PROCEDURAL' OR status <> 'ACTIVE' OR approved_by IS NOT NULL),
    CONSTRAINT memory_item_generation_chk CHECK (generation BETWEEN 0 AND 8)
);

-- Recall reads only this set; the query repeats the predicate rather than trusting the index.
CREATE INDEX idx_memory_item_recall ON memory_item (agent_id, kind) WHERE status = 'ACTIVE' AND trust = 'TRUSTED';
-- The human review queue.
CREATE INDEX idx_memory_item_review ON memory_item (agent_id, created_at DESC) WHERE status = 'PENDING';
CREATE INDEX idx_memory_item_run ON memory_item (learn_run_id) WHERE learn_run_id IS NOT NULL;
CREATE INDEX idx_memory_item_subjects ON memory_item USING GIN (subject_keys);
CREATE INDEX idx_memory_item_expiry ON memory_item (expires_at) WHERE expires_at IS NOT NULL;

-- Lineage: which rows a derived item was built from. Erasure walks idx_memory_item_source_src.
CREATE TABLE memory_item_source (
    item_id       UUID NOT NULL REFERENCES memory_item(id) ON DELETE CASCADE,
    source_table  VARCHAR(24) NOT NULL,
    source_key    VARCHAR(128) NOT NULL,
    source_trust  VARCHAR(16) NOT NULL,
    PRIMARY KEY (item_id, source_table, source_key)
);
CREATE INDEX idx_memory_item_source_src ON memory_item_source (source_table, source_key);

-- Append-only record of every state change. It holds ids, actors and statuses, never memory text, and has no
-- foreign key so it outlives an erased item.
CREATE TABLE memory_event (
    id          UUID PRIMARY KEY,
    item_id     UUID,
    agent_id    UUID NOT NULL,
    event       VARCHAR(24) NOT NULL,
    actor_type  VARCHAR(16) NOT NULL,
    actor_ref   VARCHAR(255),
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    reason      VARCHAR(500),
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_memory_event_item ON memory_event (item_id, at);
CREATE INDEX idx_memory_event_agent ON memory_event (agent_id, at DESC);
