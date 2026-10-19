-- /try Agent Studio (core/agents/demo/studio) - the no-signup surface where a
-- visitor designs REAL agents in the shared demo tenant (mission, reasoning
-- depth, autonomy, tool allow-list, guardrails, sampling), runs missions with
-- a live tool trace, and chains agents into a small workflow with a genuine
-- human-approval pause.
--
-- Version note: controlplane and tenant migrations share ONE Flyway version
-- sequence. V335-V338 are reserved by the sales-automation claim in
-- docs/AGENT-COORDINATION.md; this is V339.
--
-- Design calls:
--
-- 1. Agents themselves are NOT stored here - they are ordinary rows in the
--    demo tenant's own schema (created through AgentService, exactly like
--    the seeded agent and the Magic Studio agents). Visibility is derived:
--    an agent is listed for a session when its created_by subject
--    ("demo:<session id>") belongs to a live demo_session with the same
--    created_ip. That is what "only agents created from your IP address"
--    means operationally, and it needs no new column.
--
-- 2. Workflows live in control_plane keyed to demo_session with ON DELETE
--    CASCADE, same as demo_magic_workflow (V132): a studio workflow is an
--    artifact OF a sandbox and must die with it. created_ip is denormalised
--    onto the row so the IP-scoped listing is one indexed query rather than
--    a join through every live session.
--
-- 3. definition_json / trace_json as TEXT, not JSONB - opaque payloads the
--    API echoes to the page; nothing queries into them server-side.
--
-- 4. demo_studio_run keeps the FINAL trace of each run (status + step
--    log). The live stream is SSE straight from the runner; the row exists
--    so "your recent runs" survives a reload and so an approval that was
--    never answered is visible as such rather than vanishing.

CREATE TABLE control_plane.demo_studio_workflow (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL REFERENCES control_plane.demo_session (id) ON DELETE CASCADE,
    created_ip       VARCHAR(45),
    name             VARCHAR(120) NOT NULL,
    definition_json  TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_demo_studio_workflow_session ON control_plane.demo_studio_workflow (session_id);
CREATE INDEX idx_demo_studio_workflow_ip ON control_plane.demo_studio_workflow (created_ip);

CREATE TABLE control_plane.demo_studio_run (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID NOT NULL REFERENCES control_plane.demo_studio_workflow (id) ON DELETE CASCADE,
    session_id    UUID NOT NULL REFERENCES control_plane.demo_session (id) ON DELETE CASCADE,
    status        VARCHAR(24) NOT NULL,
    input_text    TEXT,
    output_text   TEXT,
    trace_json    TEXT NOT NULL DEFAULT '[]',
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);

CREATE INDEX idx_demo_studio_run_workflow ON control_plane.demo_studio_run (workflow_id, started_at DESC);

-- Live demo sessions by IP - the visibility query behind the studio's
-- agent list (DemoSessionRepository#findLiveForIp).
CREATE INDEX IF NOT EXISTS idx_demo_session_created_ip_live
    ON control_plane.demo_session (created_ip)
    WHERE expired_at IS NULL;
