-- Agent Skills (core/agents, com.agenticai.core.skills) - Anthropic-style
-- SKILL.md skills with three-level progressive disclosure:
--   Level 1  name + description   -> always in the agent's system prompt
--   Level 2  SKILL.md body        -> loaded by the load_skill tool when triggered
--   Level 3  bundled files        -> read/run on demand (read_skill_file / run_skill_script)
--
-- BUILT-IN skills are NOT stored here. They ship on core's classpath
-- (core/agents/src/main/resources/skills/<name>/SKILL.md) and are available to
-- every tenant with zero seeding - see BuiltInSkillCatalog. These tables hold
-- only what is genuinely per-tenant: uploaded custom skills, a tenant's choice
-- to switch a built-in off, and per-agent skill scope.
--
-- TENANT-schema tables (no schema qualifier, no tenant_id column - same
-- convention as custom_code_tool, V179).
--
-- Version V235: controlplane/ and tenant/ share ONE Flyway version stream.
-- V234 (controlplane/V234__platform_setting_value_text.sql) was the highest
-- taken in either folder when this was written, checked against the
-- filesystem and reserved in docs/AGENT-COORDINATION.md (2026-09-20 claim).
-- If it collides at merge time, rename to the next free number.

CREATE TABLE IF NOT EXISTS skill (
    id            UUID PRIMARY KEY,
    -- The frontmatter `name`: <= 64 chars, lowercase letters/digits/hyphens.
    -- UNIQUE because it is the handle the model passes to load_skill.
    name          VARCHAR(64)   NOT NULL UNIQUE,
    -- The frontmatter `description`: <= 1024 chars. This is the ONLY text the
    -- model sees until the skill is triggered.
    description   VARCHAR(1024) NOT NULL,
    -- SKILL.md body (everything after the frontmatter).
    body          TEXT          NOT NULL,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Hex SHA-256 over body + every bundled file, so an audit can tell whether
    -- a skill changed since it was reviewed.
    content_sha256 VARCHAR(64),
    created_by    VARCHAR(128),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Level 3 bundled resources. Text only: a skill file is something the model
-- reads or the sandbox runs, so binaries are rejected at import.
CREATE TABLE IF NOT EXISTS skill_file (
    id         UUID PRIMARY KEY,
    skill_id   UUID         NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    -- Normalised relative path inside the skill folder, forward slashes,
    -- never absolute and never containing "..".
    path       VARCHAR(255) NOT NULL,
    content    TEXT         NOT NULL,
    size_bytes INTEGER      NOT NULL,
    CONSTRAINT uq_skill_file_path UNIQUE (skill_id, path)
);

-- A row here means "this tenant changed a built-in skill's default". Absent
-- row = built-in is enabled.
CREATE TABLE IF NOT EXISTS skill_builtin_override (
    name       VARCHAR(64) PRIMARY KEY,
    enabled    BOOLEAN     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-agent skill scope. Absent row = defaults: skills on, every enabled
-- skill available. Kept out of the agent table on purpose - the feature is
-- additive and a missing row must behave exactly like a default one.
CREATE TABLE IF NOT EXISTS agent_skill_config (
    agent_id       UUID PRIMARY KEY REFERENCES agent (id) ON DELETE CASCADE,
    skills_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Same "opt-in allow-list, empty means unrestricted" shape as
-- agent_allowed_tool.
CREATE TABLE IF NOT EXISTS agent_allowed_skill (
    agent_id   UUID        NOT NULL REFERENCES agent_skill_config (agent_id) ON DELETE CASCADE,
    skill_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (agent_id, skill_name)
);
