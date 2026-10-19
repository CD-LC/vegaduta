-- Direct feedback: "in model selection also, there should be a dropdown which
-- lists all available models in that provider, write a pipeline or job like
-- the translator to do this". Before this, Agent.model was a free-text field
-- with no dropdown at all for ANY provider (not just Sarvam) - the user was
-- expected to type a model id from memory.
--
-- This table is the cache a scheduled job (ProviderModelCatalogService,
-- mirroring the periodic-refresh shape of V42's translation_job_settings)
-- populates: live-fetched for providers with a public models-list endpoint
-- (OpenRouter), curated/seeded for providers that require an authenticated
-- call this platform-wide job has no tenant key to make (Anthropic, Grok,
-- Gemini, Sarvam). The web console's model dropdown just reads this cache -
-- instant, no per-request live API latency.
--
-- Schema-qualified as control_plane.*, same convention as every other
-- platform-wide (not per-tenant) table - see V39's header comment. Model
-- availability doesn't vary by tenant, so this doesn't belong in any tenant
-- schema.
CREATE TABLE control_plane.provider_model_catalog (
    id              BIGSERIAL    PRIMARY KEY,
    provider_name   VARCHAR(64)  NOT NULL,
    model_id        VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    context_window  INTEGER,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_provider_model_catalog UNIQUE (provider_name, model_id)
);

CREATE INDEX idx_provider_model_catalog_provider ON control_plane.provider_model_catalog (provider_name);
