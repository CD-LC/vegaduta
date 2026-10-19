-- V484 added zero_cost and backfilled only rows with provider_name = 'OLLAMA'.
-- That misses the row LocalLlmPoolBootstrap has been creating all along: it
-- passes no provider name (Ollama is not in the hosted pool's generic
-- provider list), so an instance started with `--local-ai` before V484 kept
-- a metered, fallback-priced "free" model. Mark as free every row that is
-- local by construction: the bootstrap's label, a base URL naming an Ollama
-- host, or Ollama's default port 11434. An admin can untick the flag in
-- Platform Admin if one of these is in fact a paid endpoint.
UPDATE control_plane.platform_hosted_llm_provider
   SET zero_cost = TRUE
 WHERE zero_cost = FALSE
   AND (label = 'Local model (Ollama)'
        OR base_url ILIKE '%ollama%'
        OR base_url ~ ':11434(/|$)');
