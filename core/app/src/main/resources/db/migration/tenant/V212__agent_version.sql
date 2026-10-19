-- Agent config versioning (Item 2) - an immutable, append-only snapshot of an
-- agent's ENTIRE configuration, plus the two label pointers ('staging',
-- 'production') that let a tenant name which snapshot is the blessed one.
--
-- WHY THIS EXISTS WHEN prompt_version (V90) ALREADY DOES
-- ------------------------------------------------------
-- prompt_version covers exactly ONE of an agent's ~41 configuration fields:
-- system_prompt. Everything else - provider/model, generation params, the
-- fallback and economy pairs, the five capability opt-ins, the six allow-list
-- collections, the four modality provider/model pairs, reasoning depth,
-- autonomy tier, tuning mode, experience mode, edge routing policy - has always
-- been a pure in-place overwrite with no history and no way back. "Roll back
-- this agent" was therefore a promise the platform could not keep: a tenant who
-- broke an agent by narrowing its tool allow-list, or by pointing it at a model
-- that turned out worse, had nothing to restore.
--
-- V90 is deliberately left exactly as it is. This migration is purely additive:
-- prompt_version keeps being written by AgentService#snapshotCurrentPrompt and
-- keeps serving GET/POST /api/agents/{id}/prompt/versions unchanged. The two
-- tables overlap on one field on purpose - migrating prompt history into this
-- table would mean rewriting rows a tenant can already see and revert to, which
-- is a strictly larger risk than storing system_prompt twice.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders (see
-- V207/V175/V155 for the same paragraph - and docs/deploy/ZERO-DOWNTIME-DEPLOY-
-- RULES.md for the 2026-07-31 production outage a duplicate V156 caused; a
-- collision here is an OUTAGE, not a merge conflict). Checked at authoring time
-- by numeric sort of both directories: tenant/'s highest is V211
-- (agent_job_approval_parking), controlplane/'s highest is V206
-- (moderation_admin_surface). V212 is the next slot free in both, and this file
-- is the only V212 in the repository.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Same reasoning V207 records for agent_job, and it applies here with more
-- force: every row of this table is a verbatim copy of customer configuration,
-- system_prompt included. A control-plane agent_version would survive
-- TenantDataErasureService's DROP SCHEMA still holding customer prompts - a
-- right-to-erasure defect, not a design preference - and could not reference
-- agent(id) at all. It lives beside `agent`, and dies with it.
--
-- NO tenant_id COLUMN
-- -------------------
-- Schema-per-tenant multi-tenancy already isolates this table, same as `agent`,
-- `prompt_version` (V90) and `agent_allowed_tool` (V12). agent_job (V207) is the
-- one documented exception in this schema and its own header explains why: a
-- background poller with no request context has to rebuild TenantContext FROM
-- the row before its first repository read. agent_version has no such worker -
-- it is only ever read inside a request that already carries tenant context.
--
-- WHY THE CONFIG IS ONE JSON DOCUMENT AND NOT ~41 COLUMNS
-- -------------------------------------------------------
-- Three reasons, in order of weight:
--   1. Restore must be a SINGLE-ROW read. Six of the snapshotted fields are
--      @ElementCollection sets on Agent (allowed tools, allowed knowledge
--      collections, allowed code-intel repos, allowed knowledge-write
--      collections, collaborators, enabled guardrails). Mirroring them as six
--      child tables would make every restore a seven-table read and every
--      snapshot a seven-table write, and would put the deny-all-vs-unrestricted
--      distinction (see AgentConfigSnapshot's javadoc) behind a join whose empty
--      result is indistinguishable from a missing one.
--   2. A column-per-field agent_version would have to be ALTERed in lockstep
--      with every future Agent field, forever, out of a shared and contended
--      Flyway version namespace that has already caused one production outage.
--      A field added to Agent without the matching ALTER here would silently
--      version-and-restore an incomplete config, which is precisely the class of
--      bug this item exists to close.
--   3. Nothing queries INTO this document. It is written whole and read back
--      whole. That is exactly the condition under which this repo already
--      chooses TEXT over JSONB - see workflow_run.trace_json and V207's
--      result_json/result_schema_json comments.
-- The cost is honest and accepted: no SQL-level query on a past config value.
--
-- WHAT IS *NOT* IN THE DOCUMENT
-- ------------------------------
-- Runtime and identity state is excluded on purpose and can never be restored:
-- id, created_at, updated_at, created_by, fallback_triggered_count,
-- fallback_failed_count, last_fallback_at, input_schema_json,
-- output_schema_json, schema_version. The three fallback counters matter most -
-- AgentRepository mutates them with atomic bulk UPDATEs specifically so
-- concurrent chat turns cannot lose an increment, and a restore that wrote them
-- back from a snapshot would reintroduce exactly the read-modify-write race
-- those statements exist to prevent. See AgentConfigSnapshot's javadoc for the
-- full field-by-field list and reasoning.

CREATE TABLE agent_version (
    id                 UUID          PRIMARY KEY,

    -- ON DELETE CASCADE, matching prompt_version (V90) rather than
    -- agent_job.agent_id (V207, deliberately FK-free). The reasoning differs by
    -- table: job RUN HISTORY must outlive the definition it ran under, but a
    -- CONFIG SNAPSHOT of a deleted agent is unrestorable by construction - there
    -- is nothing left to restore it onto - so keeping the rows would only leave
    -- customer prompts behind after a delete the tenant asked for.
    agent_id           UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    -- A human-facing ORDINAL ("v7"), not an identity and not a lock. id is the
    -- identity; created_at is the order. Assigned as (latest for this agent) + 1
    -- at snapshot time and deliberately NOT UNIQUE: `agent` carries no @Version
    -- column, so two genuinely concurrent config edits are already last-writer-
    -- wins on the agent row itself, and adding a unique constraint here would
    -- convert that pre-existing (and separately documented) race from "one edit
    -- silently overwrote the other" into "a tenant's config save returns 500".
    -- A duplicated ordinal is cosmetic; a failed save is not.
    version_number     INTEGER       NOT NULL,

    -- The whole config bundle. TEXT, not JSONB - see this file's header.
    config_json        TEXT          NOT NULL,

    -- Denormalized purely so a version LIST renders without parsing N JSON
    -- documents. These three are display copies of values that also live inside
    -- config_json, and config_json is the ONLY thing restore ever reads - if the
    -- two ever disagreed, the document wins. agent_name is here for a second
    -- reason as well: Agent has no setName(), so a version's name is the one
    -- snapshotted field that restore cannot write back (see
    -- AgentConfigSnapshotCodec#apply) and this column is where a UI reads it to
    -- say so.
    agent_name         VARCHAR(255)  NOT NULL,
    provider_name      VARCHAR(32),
    model              VARCHAR(128),

    -- "Changed reasoning depth", "Restored from v3", "Agent created". Written by
    -- the AgentService call site that caused the snapshot, so a version list
    -- reads as a changelog rather than a wall of timestamps.
    change_summary     VARCHAR(500),

    -- The label pointer. NULL for the overwhelming majority of rows; at most one
    -- 'STAGING' and one 'PRODUCTION' row per agent, enforced by the partial
    -- unique index below.
    --
    -- This is the ONE mutable cell in an otherwise immutable table, and the
    -- distinction is deliberate rather than sloppy: the SNAPSHOT is immutable
    -- (nothing ever rewrites config_json), while a label is by definition a
    -- pointer that moves. Modelling it as a separate agent_version_label table
    -- would be purer, at the cost of a second table and a join on every list
    -- read for a concern that is at most two rows per agent.
    --
    -- CHECK constraint for the same reason V207 constrains agent_job.status:
    -- this column is read back through @Enumerated(STRING), so ONE typo'd value
    -- written by a hand-run SQL fix makes every findAll() throw for the WHOLE
    -- table, not just the bad row.
    label              VARCHAR(32),

    -- Who caused this version to exist (Keycloak subject), or NULL for a caller
    -- with no request context - same nullable-and-meaningful convention as
    -- agent.created_by (V151). NOT a foreign key to anything; it is an audit
    -- copy, and a user being deleted must not delete their config history.
    created_by         VARCHAR(255),

    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT agent_version_label_chk CHECK (label IS NULL OR label IN ('STAGING','PRODUCTION'))
);

-- The list read: "every version of this agent, newest first."
CREATE INDEX idx_agent_version_agent_id_created_at ON agent_version (agent_id, created_at DESC);

-- Assigning the next version_number is a "latest for this agent" lookup.
CREATE INDEX idx_agent_version_agent_id_version_number ON agent_version (agent_id, version_number DESC);

-- The label invariant, and a partial index because labelled rows are a
-- vanishing fraction of the table (at most two per agent, versus one row per
-- config edit ever made) - same shape as V207's idx_agent_job_live. Postgres
-- treats NULLs as distinct in a unique index anyway; WHERE label IS NOT NULL
-- keeps the index itself small rather than changing its semantics.
CREATE UNIQUE INDEX uq_agent_version_agent_id_label ON agent_version (agent_id, label)
    WHERE label IS NOT NULL;
