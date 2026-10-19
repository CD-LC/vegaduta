-- Workflow version history + run-to-run diff (Tier-3) - the workflow
-- sibling of agent_version (V212), and the answer to the audit gap "only
-- DRAFT/ACTIVE - agents got versioning, workflows didn't."
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream, so a number is free only when it is free in BOTH
-- folders (see V207/V175/V155 for the same paragraph - and
-- docs/deploy/ZERO-DOWNTIME-DEPLOY-RULES.md for the 2026-07-31 production
-- outage a duplicate V156 caused; a collision here is an OUTAGE, not a
-- merge conflict). V273 was reserved for this feature by the Tier-3 work
-- stream in docs/AGENT-COORDINATION.md; V270 (procedures) and V271
-- (workflow_input_request) are its siblings in the same stream.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Same reasoning V212 records for agent_version: every row is a verbatim
-- copy of customer configuration (the whole graph document). A
-- control-plane copy would survive TenantDataErasureService's DROP SCHEMA
-- still holding customer graph definitions - a right-to-erasure defect.
-- It lives beside `workflow_definition`, and dies with it.
--
-- WHY ONE JSON DOCUMENT AND NOT NORMALIZED node/edge TABLES
-- ---------------------------------------------------------
-- Restore must be a single-row read, nothing queries INTO a historical
-- graph (the diff endpoint parses it in Java), and a column-per-field
-- shape would have to migrate in lockstep with every future node type
-- forever - exactly the three reasons V212's own header gives for
-- agent_version.config_json, applied verbatim.

CREATE TABLE workflow_version (
    id                 UUID          PRIMARY KEY,

    -- ON DELETE CASCADE, matching agent_version (V212): a graph snapshot of
    -- a deleted workflow is unrestorable by construction - there is nothing
    -- left to restore it onto - so keeping the rows would only leave
    -- customer configuration behind after a delete the tenant asked for.
    workflow_id        UUID          NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,

    -- A human-facing ORDINAL ("v7"), not an identity and not a lock.
    -- Assigned as (latest for this workflow) + 1 at snapshot time and
    -- deliberately NOT UNIQUE: workflow_definition carries no @Version
    -- column, so two genuinely concurrent edits are already
    -- last-writer-wins on the definition row, and a unique constraint here
    -- would convert that pre-existing race from "one edit silently
    -- overwrote the other" into "a tenant's save returns 500". A
    -- duplicated ordinal is cosmetic; a failed save is not.
    version_number     INTEGER       NOT NULL,

    -- The whole {nodes, edges} document, verbatim. TEXT, not JSONB - see
    -- this file's header.
    graph_json         TEXT          NOT NULL,

    -- Denormalized display copies so a version LIST renders without
    -- parsing N graph documents. If these ever disagreed with the
    -- snapshot, graph_json wins - restore reads only that.
    workflow_name      VARCHAR(200)  NOT NULL,
    description        TEXT,

    -- The workflow's own status at snapshot time (DRAFT/ACTIVE/ARCHIVED).
    -- No separate label column: this IS the "which of these is live"
    -- pointer, and a second pointer would only invite the two to disagree.
    status             VARCHAR(32)   NOT NULL,

    -- "Workflow created", "Updated", "Restored from v3" - written by the
    -- WorkflowService call site that caused the snapshot, so a version
    -- list reads as a changelog rather than a wall of timestamps.
    change_summary     VARCHAR(500),

    -- Who caused this version (Keycloak subject), or NULL for a caller
    -- with no request context - same nullable-and-meaningful convention as
    -- workflow_definition.created_by (V151). NOT a foreign key.
    created_by         VARCHAR(255),

    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT workflow_version_status_chk CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED'))
);

-- The list read: "every version of this workflow, newest first."
CREATE INDEX idx_workflow_version_wf_created_at ON workflow_version (workflow_id, created_at DESC);

-- Assigning the next version_number is a "latest for this workflow" lookup.
CREATE INDEX idx_workflow_version_wf_number ON workflow_version (workflow_id, version_number DESC);

-- Run-to-run diff's join key: which version snapshot each run executed.
-- Stamped by WorkflowExecutionEngine#prepareRun at run start; NULL forever
-- for runs that predate this column - an honest "unknown", never a
-- fabricated 1.
ALTER TABLE workflow_run ADD COLUMN workflow_version INTEGER;
