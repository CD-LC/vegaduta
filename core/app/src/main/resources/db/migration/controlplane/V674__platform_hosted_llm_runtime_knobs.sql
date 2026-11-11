-- Console-editable runtime knobs of the install's own local model (Providers -> Local model ->
-- "Speed and behaviour"), extending the V611 settings. Per pool row, NULL = use the server or the
-- configured default (app.llm.ollama.num-batch, app.llm.http.stream-idle-timeout,
-- app.tools.search.enabled):
--   num_batch           tokens evaluated per prompt-processing step (llama.cpp n_batch); sent as
--                       the per-request Ollama option. A larger batch parallelises prompt eval on
--                       the host GPU - keep it <= the context the model runs with.
--   (num_parallel is deliberately NOT a column: Ollama's runner-level parallelism is not a
--    per-request option, so it is applied by deriving a {base}-par{N} model variant through
--    /api/create - the tag carries the value, exactly like the -ctx{N}k context variants.)
--   response_timeout_ms how long this model may stay silent mid-stream before the turn is
--                       declared idle (app.llm.http.stream-idle-timeout is 90 s, tuned for hosted
--                       APIs; a laptop CPU can need minutes for the first token of a long prompt).
--   tool_deferral       TRUE forces on-demand tool loading (search_tools/call_tool) for turns this
--                       row serves regardless of app.tools.search.enabled.
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN IF NOT EXISTS num_batch INTEGER,
    ADD COLUMN IF NOT EXISTS response_timeout_ms BIGINT,
    ADD COLUMN IF NOT EXISTS tool_deferral BOOLEAN;
