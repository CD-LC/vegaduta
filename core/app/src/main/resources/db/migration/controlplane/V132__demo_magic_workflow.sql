-- "Magic Studio" demo workflows (core/tenancy/demo + core/agents/demo/magic) -
-- the landing-page flow where an anonymous visitor types an outcome ("I want
-- a poster for my bakery"), the platform plans the workflow (intent -> model
-- selection -> MCP tool discovery -> a dynamically created agent), shows the
-- behind-the-scenes plan (mermaid diagram, agent prompt, model, tools), and
-- can then PUBLISH the workflow as a public mobile-friendly run page
-- (/w/{shareId}) that carries ONLY the input form.
--
-- Version note: this repo's Flyway locations merge controlplane/ and tenant/
-- into one shared version sequence (see application.yml's flyway comment).
-- Controlplane tops out at V131 and tenant at V121, so this is V132 -
-- verified against BOTH folders, per the version-collision that happened
-- before V115.
--
-- Design calls, following the demo_session precedents deliberately:
--
-- 1. Lives in control_plane, keyed to demo_session with ON DELETE CASCADE -
--    a magic workflow is an artifact OF a sandbox session and must die with
--    it (the expiry job deletes sessions; workflows must not orphan).
--
-- 2. share_id in the CLEAR, same posture as demo_session.share_id (V131):
--    the run-page URL is meant to be handed around; the value is an
--    unguessable public handle (144 bits SecureRandom, URL-safe Base64),
--    not a secret. NULL until the owner explicitly publishes.
--
-- 3. plan_json / form_spec_json as TEXT, not JSONB - nothing queries INTO
--    these server-side; they are opaque payloads the API echoes to the
--    frontend. TEXT keeps them portable and avoids implying queryability
--    that nothing needs.
--
-- 4. run_cap / runs_used - the durable half of the public run limits. The
--    product rule is "one run per IP, at most N distinct runners" (N = 3 by
--    default, admin-tunable via platform setting demo.magic.run-cap, the
--    same PlatformSettingsService pattern every other demo knob uses). The
--    per-IP-once rule lives in Redis (set of runner IPs per share, the
--    DemoShareService#admitViewer pattern); runs_used is the durable
--    belt-and-braces ceiling that survives a Redis flush, incremented
--    atomically (UPDATE ... WHERE runs_used < run_cap).
--
-- 5. agent_id - the workflow's dynamically created agent inside the demo
--    tenant's own schema (created through AgentService#create, the same
--    honest path DemoAgentSeeder takes). Nullable: creation is best-effort;
--    a plan whose agent create failed falls back to the session's seeded
--    agent at run time.

CREATE TABLE control_plane.demo_magic_workflow (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES control_plane.demo_session (id) ON DELETE CASCADE,
    share_id        VARCHAR(64),
    title           VARCHAR(120) NOT NULL,
    intent          VARCHAR(24)  NOT NULL,
    agent_id        UUID,
    plan_json       TEXT NOT NULL,
    form_spec_json  TEXT NOT NULL,
    run_cap         INT  NOT NULL DEFAULT 3,
    runs_used       INT  NOT NULL DEFAULT 0,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

-- Most rows never publish, so the uniqueness is a partial index, exactly
-- like demo_session's share_id.
CREATE UNIQUE INDEX ux_demo_magic_workflow_share_id
    ON control_plane.demo_magic_workflow (share_id)
    WHERE share_id IS NOT NULL;

CREATE INDEX ix_demo_magic_workflow_session
    ON control_plane.demo_magic_workflow (session_id);
