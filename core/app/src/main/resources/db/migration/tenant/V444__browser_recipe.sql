-- Browser recipes: a successful browser task's action sequence (navigate /
-- click / type / select / wait_for) captured as compact replayable JSON, so
-- run_browser_recipe can replay it deterministically with per-step
-- verification and fall back to live AI browsing only when a step fails
-- (app.browser.recipes.enabled, default OFF - with the flag off nothing reads
-- or writes this table).
--
-- Tenant-schema table (unqualified, resolved via search_path). recipe_json
-- holds ONLY placeholders for anything variable or secret ({{param}},
-- {{secret.NAME}}, {{totp.NAME}}) - never a credential or a user-supplied
-- value; the application refuses to store a recipe that would contain one.
-- agent_id NULL = usable by every agent of the tenant. Not encrypted at rest on
-- purpose: it holds selectors, URLs and placeholders, no secrets.
CREATE TABLE IF NOT EXISTS browser_recipe (
    id              UUID PRIMARY KEY,
    agent_id        UUID,
    name            VARCHAR(120)  NOT NULL,
    description     TEXT,
    allowed_domains TEXT          NOT NULL,
    recipe_json     TEXT          NOT NULL,
    step_count      INTEGER       NOT NULL DEFAULT 0,
    run_count       INTEGER       NOT NULL DEFAULT 0,
    fail_count      INTEGER       NOT NULL DEFAULT 0,
    last_run_at     TIMESTAMPTZ,
    last_failure    VARCHAR(500),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_browser_recipe_agent_name
    ON browser_recipe ((COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid)), lower(name));

CREATE INDEX IF NOT EXISTS ix_browser_recipe_agent
    ON browser_recipe (agent_id);
