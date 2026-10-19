-- Live-verified 2026-07-31 against the Gemini OpenAI-compat endpoint
-- (generativelanguage.googleapis.com/v1beta/openai/chat/completions) with
-- the platform's real hosted-pool key: every gemini-2.0-flash* model (with
-- or without a version/-lite suffix) now returns HTTP 404 "This model
-- models/gemini-2.0-flash is no longer available." there, even though
-- GET /v1beta/openai/models still lists these ids (Google has not cleaned
-- up that listing to match - it is not a reliable signal of what actually
-- works). gemini-2.5-flash was verified with a real 200 OK, including a
-- full tool-call + tool-result follow-up round trip (no thought_signature
-- issue - that is a Gemini 3+-only requirement).
--
-- This migration:
--   1. Updates any pool row still on a retired gemini-2.0-flash* model to
--      gemini-2.5-flash (superseding V165/V167, which both landed on a
--      2.0.x model that turned out to also be retired).
--   2. Deletes stale gemini-2.0-flash* catalog cache entries so the daily
--      refresh job and pickBestModel() auto-selection never reintroduce them.
-- ProviderModelCatalogService's GEMINI seed list no longer contains any
-- gemini-2.0-flash* id, and no longer contains gemini-3.1-flash-lite either
-- (its "flash-lite" substring wins pickBestModel's keyword scan ahead of
-- "flash" regardless of list position - seed comment explains in full).

UPDATE control_plane.platform_hosted_llm_provider
SET    model = 'gemini-2.5-flash',
       model_auto_selected = TRUE
WHERE  provider_name = 'GEMINI'
AND    completions_path = '/v1beta/openai/chat/completions'
AND    model LIKE 'gemini-2.0-flash%';

DELETE FROM control_plane.provider_model_catalog
WHERE  provider_name = 'GEMINI'
AND    model_id LIKE 'gemini-2.0-flash%';

DELETE FROM control_plane.provider_model_catalog
WHERE  provider_name = 'GEMINI'
AND    model_id = 'gemini-3.1-flash-lite';
