-- Constrained tool router for hosted-pool rows on small models (docs/local-models.md,
-- "Tool calls from small models"). Measured alongside tool_call_grade (V499):
--   tool_router_mode     what the server can enforce: FORMAT (Ollama, llama.cpp: a JSON schema on
--                        the reply), REQUIRED (vLLM: tool_choice=required), NULL when unknown.
--   tool_router_enabled  admin override: TRUE / FALSE, NULL = derive from the grade - on for TEXT
--                        and NONE rows whose server has a mode, off for STRUCTURED rows.
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN IF NOT EXISTS tool_router_mode VARCHAR(16),
    ADD COLUMN IF NOT EXISTS tool_router_enabled BOOLEAN;
