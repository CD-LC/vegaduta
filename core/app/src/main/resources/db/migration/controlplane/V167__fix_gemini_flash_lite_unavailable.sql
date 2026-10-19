-- gemini-2.0-flash-lite is not available on the Gemini OpenAI-compat path
-- (https://generativelanguage.googleapis.com/v1beta/openai/chat/completions) —
-- Google returns HTTP 404 for it there. V165 may have set pool rows to this
-- model if they were previously on a Gemini 3 model. This migration:
--   1. Updates any pool row using gemini-2.0-flash-lite to gemini-2.0-flash
--   2. Deletes the gemini-2.0-flash-lite catalog cache entry so the daily
--      refresh job and pickBestModel() never re-select it.
-- ProviderModelCatalogService seed list no longer includes gemini-2.0-flash-lite.

UPDATE control_plane.platform_hosted_llm_provider
SET    model = 'gemini-2.0-flash',
       model_auto_selected = TRUE
WHERE  provider_name = 'GEMINI'
AND    completions_path = '/v1beta/openai/chat/completions'
AND    model = 'gemini-2.0-flash-lite';

DELETE FROM control_plane.provider_model_catalog
WHERE  provider_name = 'GEMINI'
AND    model_id = 'gemini-2.0-flash-lite';
