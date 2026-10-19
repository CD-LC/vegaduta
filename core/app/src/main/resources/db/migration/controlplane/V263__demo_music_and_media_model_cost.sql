-- Two additive pieces, one migration (both belong to the same change and
-- neither touches an existing column):
--
-- 1. Music in the no-signup sandbox - same "counter on the session row +
--    capability-URL table" shape V127 (image) and V142 (video) established.
-- 2. control_plane.media_model_cost - the cost-ordered model list that
--    replaces "fixed provider order" (image) and gives text a cost-ordered
--    pick ahead of the keyword preference lists. Ordered config, editable
--    with plain SQL, no redeploy.

ALTER TABLE control_plane.demo_session
    ADD COLUMN IF NOT EXISTS music_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS control_plane.demo_generated_music (
    id                UUID PRIMARY KEY,
    session_id        UUID         NOT NULL,
    object_key        VARCHAR(512) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    prompt            TEXT         NOT NULL,
    model             VARCHAR(128) NOT NULL,
    duration_seconds  INTEGER      NOT NULL,
    created_ip        VARCHAR(45),
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_demo_generated_music_session ON control_plane.demo_generated_music (session_id);
-- Load-bearing for every request: the platform-wide daily budget check
-- (DemoMusicService#isGlobalBudgetAvailable) counts by created_at.
CREATE INDEX IF NOT EXISTS idx_demo_generated_music_created_at ON control_plane.demo_generated_music (created_at);

-- typical_call_cost_usd is THE ordering key: what one ordinary call of that
-- modality costs (one image; one 8s video clip; one 30s music clip; one
-- 3k-in/1k-out text turn - the same mix FreeTierChatClientService ranks the
-- hosted pool by). unit/unit_cost_usd record how the vendor actually bills,
-- so a row can be re-derived when a price moves. quality_rank breaks ties
-- (higher wins). A model that is not listed is never picked by cost - listing
-- a model IS the quality floor, which is why this is a curated table and not
-- "cheapest id the vendor returns".
CREATE TABLE IF NOT EXISTS control_plane.media_model_cost (
    id                     BIGSERIAL PRIMARY KEY,
    modality               VARCHAR(16)    NOT NULL,
    provider               VARCHAR(64)    NOT NULL,
    model_id               VARCHAR(255)   NOT NULL,
    unit                   VARCHAR(32)    NOT NULL,
    unit_cost_usd          NUMERIC(12, 6) NOT NULL,
    typical_call_cost_usd  NUMERIC(12, 6) NOT NULL,
    quality_rank           INTEGER        NOT NULL DEFAULT 0,
    enabled                BOOLEAN        NOT NULL DEFAULT TRUE,
    source_note            TEXT,
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_media_model_cost UNIQUE (modality, provider, model_id),
    CONSTRAINT chk_media_model_cost_modality CHECK (modality IN ('TEXT', 'IMAGE', 'VIDEO', 'MUSIC', 'SPEECH'))
);

-- IMAGE. OPENAI is priced at the FREE-tier forced quality (low). The
-- orchestrator ranks a tenant's own BYOK providers by these rows and only then
-- falls to the GOOGLE pool, so with this seed the effective order is OPENAI,
-- GOOGLE_VERTEX, pool - exactly the fixed order it replaces.
INSERT INTO control_plane.media_model_cost
    (modality, provider, model_id, unit, unit_cost_usd, typical_call_cost_usd, quality_rank, source_note)
VALUES
    ('IMAGE', 'OPENAI',        'gpt-image-1',             'PER_IMAGE', 0.011,  0.011,  2, 'gpt-image-1 low quality 1024x1024, OpenAI price list'),
    ('IMAGE', 'GOOGLE',        'gemini-2.5-flash-image',  'PER_IMAGE', 0.039,  0.039,  1, 'Gemini API pricing page, checked 2026-09-20'),
    ('IMAGE', 'GOOGLE_VERTEX', 'imagen-3.0-generate-002', 'PER_IMAGE', 0.040,  0.040,  1, 'Vertex AI Imagen 3 list price'),
    -- VIDEO is listed for reporting and ordering only; nothing here enables it.
    ('VIDEO', 'GOOGLE_VERTEX', 'veo-3.0-fast-generate-001', 'PER_SECOND', 0.15, 1.20, 1, 'repo docs/plans/VIDEO-PIPELINE-FIRST-RUN.md figure; not re-verified 2026-09-20'),
    ('VIDEO', 'GOOGLE_VERTEX', 'veo-3.1-fast-generate-001', 'PER_SECOND', 0.10, 0.80, 2, 'Gemini API pricing page 720p, checked 2026-09-20'),
    -- MUSIC: one list price across Eleven Music models, so quality_rank decides.
    ('MUSIC', 'ELEVENLABS', 'music_v1',   'PER_MINUTE', 0.15, 0.075, 1, 'elevenlabs.io/pricing/api, checked 2026-09-20'),
    ('MUSIC', 'ELEVENLABS', 'music_v2',   'PER_MINUTE', 0.15, 0.075, 2, 'elevenlabs.io/pricing/api, checked 2026-09-20'),
    ('MUSIC', 'ELEVENLABS', 'music_v2_5', 'PER_MINUTE', 0.15, 0.075, 3, 'elevenlabs.io/pricing/api, checked 2026-09-20'),
    -- TEXT: exactly the models ProviderModelCatalogService.PREFERRED_DEFAULT_MODELS
    -- already picks, priced from LlmPricingCatalog - so this seed changes no
    -- pick today; it moves the decision from code into an editable, costed row.
    ('TEXT', 'ANTHROPIC', 'claude-haiku-4-5-20251001', 'PER_TYPICAL_CALL', 0.008000, 0.008000, 1, 'LlmPricingCatalog $1/$5 per MTok, 3k in + 1k out'),
    ('TEXT', 'OPENAI',    'gpt-4o-mini',               'PER_TYPICAL_CALL', 0.001050, 0.001050, 1, 'LlmPricingCatalog $0.15/$0.60 per MTok'),
    ('TEXT', 'GEMINI',    'gemini-2.5-flash',          'PER_TYPICAL_CALL', 0.003400, 0.003400, 1, 'LlmPricingCatalog $0.30/$2.50 per MTok'),
    ('TEXT', 'MISTRAL',   'mistral-small-latest',      'PER_TYPICAL_CALL', 0.000600, 0.000600, 1, 'LlmPricingCatalog $0.10/$0.30 per MTok'),
    ('TEXT', 'GROQ',      'llama-3.1-8b-instant',      'PER_TYPICAL_CALL', 0.000230, 0.000230, 1, 'LlmPricingCatalog $0.05/$0.08 per MTok'),
    ('TEXT', 'META',      'muse-spark-1.3',            'PER_TYPICAL_CALL', 0.008000, 0.008000, 1, 'LlmPricingCatalog $1.25/$4.25 per MTok')
ON CONFLICT (modality, provider, model_id) DO NOTHING;
