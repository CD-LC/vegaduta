-- Catalog repair (Aug 2026): xAI retired the entire Grok 3.x/4.0 line on
-- 2026-05-15 (grok-3 temporarily redirects to grok-4.3 until ~2026-08-15,
-- grok-3-mini/grok-4/grok-4-fast already hard-400), DeepSeek's V3/R1
-- endpoints (deepseek-chat/deepseek-reasoner) gave way to the V4 line, and
-- Groq decommissioned gemma2-9b-it. The daily refresh job
-- (ProviderModelCatalogService#refreshProvider) is replace-not-merge, so the
-- updated SEED_MODELS list would eventually converge on its own at the next
-- 03:00 UTC run - but "eventually" leaves a window where the dropdown still
-- offers dead models and resolveDefaultModelFor can still hand a new agent a
-- retired id, so this migration fixes the cached catalog immediately, same
-- posture as V168's Gemini cleanup.
--
-- Agents that already have a retired model PERSISTED live in tenant schemas
-- and cannot be fixed from a controlplane migration - that half is handled
-- at runtime instead: AgentService#resolveEffectiveModel substitutes the
-- replacement (ProviderModelCatalogService.RETIRED_MODEL_REPLACEMENTS) at
-- chat time with a WARN, without rewriting the row.
--
-- V182 because the Flyway version namespace is SHARED between the
-- controlplane/ and tenant/ migration directories (single Flyway bean scans
-- both - see V178's header comment and the V156 prod outage): the global max
-- across both directories at time of writing is V181 (controlplane), with
-- tenant/ at V179, so V182 is the next globally-free number.

-- 1) Drop the retired rows from the cached catalog.
DELETE FROM control_plane.provider_model_catalog
WHERE  provider_name = 'GROK'
AND    model_id IN ('grok-3', 'grok-3-mini', 'grok-4', 'grok-4-fast');

DELETE FROM control_plane.provider_model_catalog
WHERE  provider_name = 'DEEPSEEK'
AND    model_id IN ('deepseek-chat', 'deepseek-reasoner');

DELETE FROM control_plane.provider_model_catalog
WHERE  provider_name = 'GROQ'
AND    model_id = 'gemma2-9b-it';

-- 2) Insert the replacements (idempotent: ON CONFLICT on V66's
-- uq_provider_model_catalog UNIQUE (provider_name, model_id) updates in
-- place, so re-running - or racing the daily refresh job - never fails).
-- Values mirror ProviderModelCatalogService.SEED_MODELS exactly.
-- capability_summary is left NULL for the generation job to backfill (V178).
INSERT INTO control_plane.provider_model_catalog
    (provider_name, model_id, display_name, context_window,
     supports_image_input, supports_audio_input, supports_video_input, updated_at)
VALUES
    ('GROK', 'grok-4.5', 'Grok 4.5', 500000, TRUE, FALSE, FALSE, NOW()),
    ('GROK', 'grok-4.3', 'Grok 4.3', 1000000, TRUE, FALSE, FALSE, NOW()),
    ('GROK', 'grok-4.20-0309-reasoning', 'Grok 4.20 Reasoning (0309)', 256000, FALSE, FALSE, FALSE, NOW()),
    ('GROK', 'grok-4.20-0309-non-reasoning', 'Grok 4.20 Non-Reasoning (0309)', 256000, FALSE, FALSE, FALSE, NOW()),
    ('GROK', 'grok-build-0.1', 'Grok Build 0.1', 256000, FALSE, FALSE, FALSE, NOW()),
    ('DEEPSEEK', 'deepseek-v4-flash', 'DeepSeek V4 Flash', 1000000, FALSE, FALSE, FALSE, NOW()),
    ('DEEPSEEK', 'deepseek-v4-pro', 'DeepSeek V4 Pro', 1000000, FALSE, FALSE, FALSE, NOW()),
    ('GROQ', 'openai/gpt-oss-20b', 'GPT-OSS 20B (Groq)', 128000, FALSE, FALSE, FALSE, NOW()),
    ('GROQ', 'openai/gpt-oss-120b', 'GPT-OSS 120B (Groq)', 128000, FALSE, FALSE, FALSE, NOW())
ON CONFLICT (provider_name, model_id) DO UPDATE
SET display_name         = EXCLUDED.display_name,
    context_window       = EXCLUDED.context_window,
    supports_image_input = EXCLUDED.supports_image_input,
    supports_audio_input = EXCLUDED.supports_audio_input,
    supports_video_input = EXCLUDED.supports_video_input,
    updated_at           = EXCLUDED.updated_at;

-- 3) Hosted-pool rows are control_plane and CAN be repaired here (same shape
-- as V165/V168's Gemini pool fixes): any pool row still pointed at a retired
-- Grok/DeepSeek model would fail every FREE-tier call routed to it.
-- model_auto_selected = TRUE because this is an automated correction, not an
-- admin's explicit choice - same semantics as V165.
UPDATE control_plane.platform_hosted_llm_provider
SET    model = CASE model
                   WHEN 'grok-3'      THEN 'grok-4.3'
                   WHEN 'grok-3-mini' THEN 'grok-4.20-0309-non-reasoning'
                   WHEN 'grok-4'      THEN 'grok-4.5'
                   WHEN 'grok-4-fast' THEN 'grok-4.3'
               END,
       model_auto_selected = TRUE
WHERE  provider_name = 'GROK'
AND    model IN ('grok-3', 'grok-3-mini', 'grok-4', 'grok-4-fast');

UPDATE control_plane.platform_hosted_llm_provider
SET    model = CASE model
                   WHEN 'deepseek-chat'     THEN 'deepseek-v4-flash'
                   WHEN 'deepseek-reasoner' THEN 'deepseek-v4-pro'
               END,
       model_auto_selected = TRUE
WHERE  provider_name = 'DEEPSEEK'
AND    model IN ('deepseek-chat', 'deepseek-reasoner');
