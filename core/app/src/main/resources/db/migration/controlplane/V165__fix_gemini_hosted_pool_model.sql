-- Hotfix: Gemini 3 models on the OpenAI-compat hosted-pool path return HTTP 400
-- on any tool-using turn because Spring AI's OpenAiChatModel strips the
-- thought_signature field that Gemini 3 requires echoed back in tool-result
-- follow-up requests. Gemini 2.0 Flash-Lite does not use thought_signature.
--
-- This migration migrates any existing hosted-pool row that has a Gemini 3 model
-- configured on the OpenAI-compat completions path to gemini-2.0-flash-lite.
-- It is intentionally narrow — only rows where:
--   1. provider_name = 'GEMINI' (the platform's Gemini OpenAI-compat pool entry)
--   2. completions_path = '/v1beta/openai/chat/completions' (the OpenAI-compat path)
--   3. model is one of the known Gemini 3 models that trigger the 400
-- Rows with custom base URLs, non-Gemini providers, or already-safe model ids
-- are left untouched. The model_auto_selected flag is set to TRUE because this
-- is an automated correction, not an admin's explicit model choice — same
-- semantics as ProviderModelCatalogService#autoSelectModel.
--
-- Root cause documented in ProviderModelCatalogService (ORDER MATTERS comment)
-- and memory/prod-incident-2026-07-31-gemini-thought-signature.md.

UPDATE control_plane.platform_hosted_llm_provider
SET    model = 'gemini-2.0-flash-lite',
       model_auto_selected = TRUE
WHERE  provider_name = 'GEMINI'
AND    completions_path = '/v1beta/openai/chat/completions'
AND    model IN ('gemini-3.1-flash-lite', 'gemini-3.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.5-flash');
