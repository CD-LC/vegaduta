-- Console-editable behaviour of the install's own local model (Providers -> Local model ->
-- "Speed and behaviour"). Per pool row, NULL = use the configured default
-- (app.llm.ollama.reasoning-effort, app.local-models.keep-alive):
--   reasoning_effort  none | low | medium | high - the thinking switch sent with every chat to an
--                     Ollama row. none turns a thinking model's reasoning off (seconds instead of
--                     tens of seconds on a laptop); a blank string leaves the model's own default.
--   keep_alive        how long Ollama keeps the model in memory after the warm-up call (30m, 2h, -1).
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN IF NOT EXISTS reasoning_effort VARCHAR(16),
    ADD COLUMN IF NOT EXISTS keep_alive VARCHAR(16);
