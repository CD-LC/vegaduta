-- Ecosystem Maintenance (super-admin, cross-tenant) build - two new
-- control_plane tables backing the new "platform" module:
--
--   platform_llm_task_config: which LLM provider/model/key each strictly
--   internal PlatformTask (TRANSLATION_REFRESH, ASK_AI_HELPER - see that
--   enum's javadoc) should use. Unique on task - at most one config row per
--   task, upserted in place rather than accumulating history rows.
--
--   translation_job_settings: informational-only reference config an admin
--   maintains by hand describing what they believe
--   .github/workflows/translate-messages.yml's schedule/provider/model
--   currently are - this table is never read by that GitHub Action itself
--   (see TranslationJobSettings' javadoc). Singleton row, always id = 1.
--
-- Schema-qualified as control_plane.* throughout, same convention as every
-- other control-plane table in this project (see V2__control_plane_baseline.sql,
-- which also shows CREATE SCHEMA IF NOT EXISTS control_plane; - not repeated
-- here since that schema has existed since V2 and every migration since has
-- assumed it already exists, same as this one does).

CREATE TABLE control_plane.platform_llm_task_config (
    id                 BIGSERIAL PRIMARY KEY,
    task               VARCHAR(32)  NOT NULL,
    provider_name      VARCHAR(32)  NOT NULL,
    model              VARCHAR(128) NOT NULL,
    encrypted_api_key  TEXT,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    last_validated_at  TIMESTAMPTZ,
    CONSTRAINT uq_platform_llm_task_config_task UNIQUE (task)
);

CREATE TABLE control_plane.translation_job_settings (
    id                     BIGINT PRIMARY KEY,
    cron_schedule_display  VARCHAR(64)   NOT NULL,
    provider_name          VARCHAR(32)   NOT NULL,
    model                  VARCHAR(128)  NOT NULL,
    workflow_path_hint     VARCHAR(255)  NOT NULL,
    notes                  VARCHAR(1000),
    updated_at             TIMESTAMPTZ   NOT NULL,
    updated_by             VARCHAR(255)
);
