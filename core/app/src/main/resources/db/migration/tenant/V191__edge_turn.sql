-- Edge Wave 2, EDGE-B (docs/rewriting-story/04-design/EDGE-AI-STRATEGY.md; closes
-- the "edge turns are unmetered and unrecorded" half of finding E-4/F-25): one
-- row per reported on-device turn, written by EdgeTurnTelemetryController for
-- tenant-resolved (authenticated) callers only - the anonymous /try surface
-- stays log-only, exactly as before, because there is no tenant schema to
-- write into for it. This is what makes BG-2 (">=40% of FREE turns on-device")
-- measurable at all: before this table the only evidence an edge turn ever
-- happened was a Loki log line.
--
-- Version V191: unique across the merged controlplane/ + tenant/ version
-- stream (see V190's header); V190 is this wave's other migration.
--
-- TENANT-schema table (no schema qualifier), like every other per-tenant
-- operational log (tool_call_log et al.) - an edge turn belongs to exactly the
-- tenant whose agent answered it.
--
-- Column notes:
--   tier      - 0 (Chrome built-in) / 1 (WebLLM); SMALLINT leaves room for the
--               tier-2 WASM ladder without a migration. Nullable: a malformed
--               report is still worth the row (outcome/reason survive).
--   outcome   - "success" | "fallback" | "error", the client tierRouter's own
--               vocabulary (see web/app/lib/edge/telemetry.ts). Kept VARCHAR,
--               not an enum/CHECK, so a newer client vocabulary never makes
--               telemetry writes start failing - this is observability data,
--               fire-and-forget by contract.
--   reason    - typed failure reason / fallthrough detail; free text, bounded
--               at write time by the controller (512), not by a constraint.
--   agent_id  - NOT a foreign key on purpose: telemetry must survive an agent
--               being deleted (historical share-of-load analysis), and a
--               fire-and-forget write path should never fail on a referential
--               race with a concurrent delete.
CREATE TABLE edge_turn (
    id          UUID PRIMARY KEY,
    agent_id    UUID NOT NULL,
    session_key VARCHAR(255),
    tier        SMALLINT,
    model_id    VARCHAR(128),
    outcome     VARCHAR(16) NOT NULL,
    reason      VARCHAR(512),
    latency_ms  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The two read shapes an edge-share panel needs: per-agent over time, and
-- tenant-wide over time (created_at alone covers the latter's range scan).
CREATE INDEX idx_edge_turn_agent_created ON edge_turn (agent_id, created_at);
CREATE INDEX idx_edge_turn_created ON edge_turn (created_at);
