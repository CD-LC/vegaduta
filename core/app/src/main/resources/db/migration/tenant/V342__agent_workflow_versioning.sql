-- Agent & Workflow Versioning + Rollback (Workstream C1) - builds on the
-- snapshot tables that already exist: agent_version (V212, whole-config
-- bundle + STAGING/PRODUCTION label column) and workflow_version (V273,
-- graph snapshots). This migration adds the four pieces the C1 spec needs
-- that those tables lacked:
--
--   1. bundle_hash on both version tables - SHA-256 (hex) of the stored
--      document, so a "publish" that changed nothing is deduped by value,
--      not by ordinal. Backfilled with the built-in sha256(bytea) function
--      (core Postgres since v11 - NOT pgcrypto's digest(), so no CREATE
--      EXTENSION is needed; V175's header records the same assumption for
--      gen_random_uuid). The Java writers hash the exact same UTF-8 string
--      they store, so SQL-backfilled and Java-written hashes compare equal.
--
--   2. label on workflow_version - the same STAGING/PRODUCTION pointer
--      agent_version already has (V212 put it on the row rather than a
--      separate version_label table; see that migration's "the ONE mutable
--      cell" comment - this file follows that proven design instead of the
--      spec's separate table, and the pointer/history split below still
--      gives us audit).
--
--   3. version_label_event - append-only history of every label move
--      (assign/clear/rollback) across BOTH entity kinds. This is what makes
--      "rollback = move the PRODUCTION pointer back" auditable and what C6
--      insights will read for promote timestamps.
--
--   4. version pins on run rows - workflow_run.workflow_version already
--      exists (V273); this adds agent_version_id to quick_task_run,
--      llm_call_log and tool_call_log. Conversation turns themselves are
--      Redis-only (SessionMemoryService), so per the C1 spec's own fallback
--      the LLM-usage and tool-call rows are what get stamped - every chat
--      turn's LLM calls land in llm_call_log via LlmMetricsAdvisor.
--
--   5. BEFORE UPDATE/DELETE immutability triggers on both version tables -
--      the spec's hard acceptance ("direct UPDATE/DELETE on version tables
--      raises a DB exception"). UPDATE is refused unless ONLY the label
--      column changes (labels are pointers, they move by design - same rule
--      V212 documented). DELETE is refused UNLESS the parent row is already
--      gone in the same statement, which is exactly how an ON DELETE CASCADE
--      presents: deleting an agent/workflow must still take its snapshots
--      with it (V212's right-to-erasure reasoning), while a direct
--      DELETE FROM agent_version raises.
--
-- VERSION NUMBER: flyway.locations merges controlplane/ and tenant/ into one
-- version stream (see V212/V273's own warnings and docs/deploy/ZERO-DOWNTIME-
-- DEPLOY-RULES.md - a duplicate version is an outage, not a merge conflict).
-- Checked both directories at authoring time: tenant/'s highest is V340,
-- controlplane/'s highest is V339. V342 is the next slot reserved for this
-- track (V341 was skipped as possibly-claimed mid-flight).

-- =====================================================================
-- 1. bundle_hash
-- =====================================================================

-- VARCHAR not CHAR: Hibernate validate maps String to Types.VARCHAR and
-- rejects bpchar as "wrong column type" at boot. Values are 64-hex-char
-- sha256s either way.
ALTER TABLE agent_version ADD COLUMN IF NOT EXISTS bundle_hash VARCHAR(64);
ALTER TABLE workflow_version ADD COLUMN IF NOT EXISTS bundle_hash VARCHAR(64);

-- convert_to(col,'UTF8'), not col::bytea: a text->bytea cast runs the bytea
-- INPUT parser, which rejects any JSON containing backslash escapes
-- ('\uXXXX', '\n', a lone '\') with "invalid input syntax for type bytea"
-- (22P02) - the migration then fails on exactly the schemas that store real
-- user content. convert_to is byte-exact UTF-8 and parses nothing.
UPDATE agent_version SET bundle_hash = encode(sha256(convert_to(config_json, 'UTF8')), 'hex')
    WHERE bundle_hash IS NULL;
UPDATE workflow_version SET bundle_hash = encode(sha256(convert_to(graph_json, 'UTF8')), 'hex')
    WHERE bundle_hash IS NULL;

-- =====================================================================
-- 2. workflow_version.label - same shape as agent_version.label (V212):
--    the one mutable cell, at most one holder per (workflow, label).
-- =====================================================================

ALTER TABLE workflow_version ADD COLUMN IF NOT EXISTS label VARCHAR(32);

-- Everything the snapshot needs that isn't inside graph_json: schedule
-- config, execution limits and economy/tuning knobs from
-- workflow_definition (the trigger configuration itself lives in the
-- graph's trigger node, already captured by graph_json). TEXT like
-- graph_json, serialized by Java and by the backfill's jsonb_build_object.
ALTER TABLE workflow_version ADD COLUMN IF NOT EXISTS details_json TEXT;

ALTER TABLE workflow_version
    ADD CONSTRAINT workflow_version_label_chk
    CHECK (label IS NULL OR label IN ('STAGING','PRODUCTION'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_version_wf_label
    ON workflow_version (workflow_id, label) WHERE label IS NOT NULL;

-- =====================================================================
-- 3. version_label_event - label-move audit for both entity kinds.
--    No tenant_id: schema-per-tenant isolation, same convention as every
--    table above. version_id is deliberately NOT a foreign key - the event
--    must survive anything that ever happens to the version row (and the
--    discriminator column makes a single FK impossible anyway).
-- =====================================================================

CREATE TABLE IF NOT EXISTS version_label_event (
    id          UUID         PRIMARY KEY,
    entity_type VARCHAR(16)  NOT NULL,
    entity_id   UUID         NOT NULL,
    label       VARCHAR(32)  NOT NULL,
    version_id  UUID         NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    applied_by  VARCHAR(255),
    detail      VARCHAR(500),
    applied_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT version_label_event_entity_chk CHECK (entity_type IN ('AGENT','WORKFLOW')),
    CONSTRAINT version_label_event_label_chk  CHECK (label IN ('STAGING','PRODUCTION')),
    CONSTRAINT version_label_event_action_chk CHECK (action IN ('PROMOTE','ASSIGN','CLEAR','ROLLBACK','BACKFILL'))
);

CREATE INDEX IF NOT EXISTS idx_version_label_event_entity
    ON version_label_event (entity_type, entity_id, applied_at DESC);

-- =====================================================================
-- 4. Run-row version pins (nullable - rows that predate this column stay
--    an honest "unknown", the same convention V273 used for
--    workflow_run.workflow_version).
-- =====================================================================

ALTER TABLE quick_task_run ADD COLUMN IF NOT EXISTS agent_version_id UUID;
ALTER TABLE llm_call_log   ADD COLUMN IF NOT EXISTS agent_version_id UUID;
ALTER TABLE tool_call_log  ADD COLUMN IF NOT EXISTS agent_version_id UUID;

-- =====================================================================
-- 5. Immutability triggers.
--
--    UPDATE is legal only when every column EXCEPT label is unchanged
--    (IS NOT DISTINCT FROM treats nulls sanely, and Hibernate rewrites all
--    columns with identical values on a label move, so a column-by-column
--    comparison - not a dirty-column list - is the correct test).
--
--    DELETE is legal only when the parent entity row no longer exists in
--    this statement's view - i.e. the delete arrived via ON DELETE CASCADE
--    from agent/workflow_definition. A direct DELETE on the version table
--    while the parent still stands raises.
-- =====================================================================

CREATE OR REPLACE FUNCTION agent_version_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM agent WHERE id = OLD.agent_id) THEN
            RAISE EXCEPTION 'agent_version rows are immutable snapshots - direct DELETE is not allowed (id %)', OLD.id;
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.agent_id        IS DISTINCT FROM OLD.agent_id
        OR NEW.version_number IS DISTINCT FROM OLD.version_number
        OR NEW.config_json    IS DISTINCT FROM OLD.config_json
        OR NEW.bundle_hash    IS DISTINCT FROM OLD.bundle_hash
        OR NEW.agent_name     IS DISTINCT FROM OLD.agent_name
        OR NEW.provider_name  IS DISTINCT FROM OLD.provider_name
        OR NEW.model          IS DISTINCT FROM OLD.model
        OR NEW.change_summary IS DISTINCT FROM OLD.change_summary
        OR NEW.created_by     IS DISTINCT FROM OLD.created_by
        OR NEW.created_at     IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'agent_version rows are immutable - only the label pointer may move (id %)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_agent_version_immutable ON agent_version;
CREATE TRIGGER trg_agent_version_immutable
    BEFORE UPDATE OR DELETE ON agent_version
    FOR EACH ROW EXECUTE FUNCTION agent_version_immutable();

CREATE OR REPLACE FUNCTION workflow_version_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM workflow_definition WHERE id = OLD.workflow_id) THEN
            RAISE EXCEPTION 'workflow_version rows are immutable snapshots - direct DELETE is not allowed (id %)', OLD.id;
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.workflow_id     IS DISTINCT FROM OLD.workflow_id
        OR NEW.version_number IS DISTINCT FROM OLD.version_number
        OR NEW.graph_json     IS DISTINCT FROM OLD.graph_json
        OR NEW.details_json   IS DISTINCT FROM OLD.details_json
        OR NEW.bundle_hash    IS DISTINCT FROM OLD.bundle_hash
        OR NEW.workflow_name  IS DISTINCT FROM OLD.workflow_name
        OR NEW.description    IS DISTINCT FROM OLD.description
        OR NEW.status         IS DISTINCT FROM OLD.status
        OR NEW.change_summary IS DISTINCT FROM OLD.change_summary
        OR NEW.created_by     IS DISTINCT FROM OLD.created_by
        OR NEW.created_at     IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'workflow_version rows are immutable - only the label pointer may move (id %)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workflow_version_immutable ON workflow_version;
CREATE TRIGGER trg_workflow_version_immutable
    BEFORE UPDATE OR DELETE ON workflow_version
    FOR EACH ROW EXECUTE FUNCTION workflow_version_immutable();

-- =====================================================================
-- 6. Backfill.
--
--    Workflows are pure SQL - graph_json/name/description/status are all
--    verbatim columns on workflow_definition, so v1 is a faithful snapshot.
--    Agents CANNOT be backfilled in SQL: agent_version.config_json is the
--    AgentConfigSnapshotCodec serialization (~45 fields incl. sorted
--    collections) and hand-building it here would silently produce bundles
--    that restore wrong. Agents with no version rows are snapshot by the
--    guarded VersionSnapshotBackfillRunner at boot instead (same pattern
--    ExistingTenantSchemaMigrationRunner established - idempotent, one bad
--    tenant never blocks the rest).
--
--    Labels: the live row IS what production has been running, so the
--    newest version gets PRODUCTION - not both labels: the label column is
--    a single pointer (V212 design), and "what is running now" is the one
--    that must be right. DRAFT workflows get STAGING instead (a draft has
--    never served traffic). Idempotent: every statement is guarded by
--    NOT EXISTS / label IS NOT NULL checks.
-- =====================================================================

INSERT INTO workflow_version (
    id, workflow_id, version_number, graph_json, details_json, bundle_hash,
    workflow_name, description, status, change_summary, created_by, created_at
)
SELECT gen_random_uuid(), d.id, 1, d.graph_json,
       jsonb_build_object(
           'scheduleCron', d.schedule_cron,
           'scheduleEnabled', d.schedule_enabled,
           'reasoningDepth', d.reasoning_depth,
           'tuningMode', d.tuning_mode,
           'stepCachingEnabled', d.step_caching_enabled,
           'costRoutingEnabled', d.cost_routing_enabled,
           'economyProviderName', d.economy_provider_name,
           'economyModel', d.economy_model,
           'maxStepsPerRun', d.max_steps_per_run,
           'stepTimeoutSeconds', d.step_timeout_seconds,
           'maxRetryAttempts', d.max_retry_attempts
       )::text,
       encode(sha256(convert_to(d.graph_json, 'UTF8')), 'hex'),
       d.name, d.description, d.status,
       'v1 - snapshot of pre-versioning state', d.created_by, now()
FROM workflow_definition d
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_version v WHERE v.workflow_id = d.id
);

-- Newest version of each ACTIVE workflow that has no label at all -> PRODUCTION.
UPDATE workflow_version v
SET label = 'PRODUCTION'
WHERE v.id = (
        SELECT v2.id FROM workflow_version v2
        WHERE v2.workflow_id = v.workflow_id
        ORDER BY v2.version_number DESC LIMIT 1
    )
  AND EXISTS (
        SELECT 1 FROM workflow_definition d
        WHERE d.id = v.workflow_id AND d.status = 'ACTIVE'
    )
  AND NOT EXISTS (
        SELECT 1 FROM workflow_version l
        WHERE l.workflow_id = v.workflow_id AND l.label IS NOT NULL
    );

-- Newest version of each DRAFT workflow that has no label at all -> STAGING.
UPDATE workflow_version v
SET label = 'STAGING'
WHERE v.id = (
        SELECT v2.id FROM workflow_version v2
        WHERE v2.workflow_id = v.workflow_id
        ORDER BY v2.version_number DESC LIMIT 1
    )
  AND EXISTS (
        SELECT 1 FROM workflow_definition d
        WHERE d.id = v.workflow_id AND d.status = 'DRAFT'
    )
  AND NOT EXISTS (
        SELECT 1 FROM workflow_version l
        WHERE l.workflow_id = v.workflow_id AND l.label IS NOT NULL
    );

-- Newest version of each agent that has rows but no label -> PRODUCTION.
-- (Agents with NO rows get their v1 + PRODUCTION label from the boot
-- runner - see the backfill note above.)
UPDATE agent_version v
SET label = 'PRODUCTION'
WHERE v.id = (
        SELECT v2.id FROM agent_version v2
        WHERE v2.agent_id = v.agent_id
        ORDER BY v2.version_number DESC LIMIT 1
    )
  AND NOT EXISTS (
        SELECT 1 FROM agent_version l
        WHERE l.agent_id = v.agent_id AND l.label IS NOT NULL
    );
