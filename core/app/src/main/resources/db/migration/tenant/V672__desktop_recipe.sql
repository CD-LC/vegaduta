-- V672 (number assigned for the desktop-recipes change): desktop recipes - the
-- personal tier's equivalent of browser_recipe (V444). A successful run of the
-- local_desktop_* tools (open / activate / press / click / type / key / wait)
-- is captured as compact replayable JSON, so run_desktop_recipe can replay it
-- deterministically through the desktop-agent sidecar with per-step
-- verification and fall back to live desktop actions only when a step fails
-- (app.assistant.desktop.recipes.enabled, default OFF - with the flag off
-- nothing reads or writes this table).
--
-- Tenant-schema table (unqualified, resolved via search_path). recipe_json
-- holds ONLY placeholders for anything variable or secret ({{param}},
-- {{secret.NAME}}) - never a credential or a user-supplied value; the
-- application refuses to store a recipe that would contain one.
-- agent_id NULL = usable by every agent of the tenant. allowed_apps is a CSV
-- of lowercased application names the recipe may act in (the desktop analogue
-- of browser_recipe.allowed_domains). default_mode is the replay posture:
-- AUTO runs steps back-to-back, MONITOR pauses between them so the person can
-- watch. Not encrypted at rest on purpose: it holds labels, coordinates and
-- placeholders, no secrets.
CREATE TABLE IF NOT EXISTS desktop_recipe (
    id              UUID PRIMARY KEY,
    agent_id        UUID,
    name            VARCHAR(120)  NOT NULL,
    description     TEXT,
    allowed_apps    TEXT          NOT NULL,
    default_mode    VARCHAR(16)   NOT NULL DEFAULT 'AUTO' CHECK (default_mode IN ('AUTO', 'MONITOR')),
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

CREATE UNIQUE INDEX IF NOT EXISTS ux_desktop_recipe_agent_name
    ON desktop_recipe ((COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid)), lower(name));

CREATE INDEX IF NOT EXISTS ix_desktop_recipe_agent
    ON desktop_recipe (agent_id);
