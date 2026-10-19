-- Small-model mode for hosted-pool rows (docs/local-models.md, "Tool calls from small
-- models"). A turn served by a small model offers at most six tools (by relevance), uses
-- the compact prompt directives (short tool-trust rule, skills listed by name only until
-- load_skill) and runs at FAST reasoning depth.
--   parameter_billions  the model's size as the server reports it (Ollama /api/show), NULL
--                       when unknown; the save-time probe fills it (V499).
--   small_model         admin override: TRUE / FALSE, NULL = derive: parameter_billions <= 8,
--                       or a size in the model tag (":3b", "-8b") when the server reports none.
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN IF NOT EXISTS parameter_billions DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS small_model BOOLEAN;
