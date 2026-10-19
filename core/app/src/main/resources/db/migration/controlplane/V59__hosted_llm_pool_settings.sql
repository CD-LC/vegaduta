-- Direct feedback: "there should be a control in admin platform, to turn on
-- or off, the platform use for llm - it should not always be platform llm".
-- Before this, the only way to stop FREE-tier chat traffic from drawing on
-- the platform-hosted LLM pool was to individually disable/delete every row
-- in control_plane.platform_hosted_llm_provider - functionally possible, but
-- not one obvious switch. This is that switch: a single singleton row
-- (always id = 1, same convention as V42's translation_job_settings) a
-- platform-admin flips to pause the whole pool without touching the
-- provider rows themselves.
--
-- Schema-qualified as control_plane.*, same convention as every other
-- platform-wide (not per-tenant) table - see V39's header comment.
CREATE TABLE control_plane.hosted_llm_pool_settings (
    id         BIGINT      PRIMARY KEY,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255)
);
