-- "Free means free": a hosted-pool row that points at a model the operator
-- runs themselves (the Ollama container `--local-ai` starts, a vLLM or
-- llama.cpp server on the LAN, any OpenAI-compatible endpoint nobody bills
-- per token) was until now priced like a paid API. LlmPricingCatalog has no
-- rate for local tags such as qwen3.5:4b, so every call was charged at the
-- conservative unknown-model fallback ($0.50 / $2.00 per MTok): it debited
-- the per-user daily dollar budget, counted against the tenant's monthly
-- hosted-pool token quota, and sorted LAST in the cheapest-first failover
-- order because an unpriced model ranks after every priced one.
--
-- zero_cost marks such a row. The chat path prices its calls at $0, skips the
-- per-user dollar reservation, and - when every enabled row is zero-cost -
-- skips the monthly token quota too (app.free-tier.meter-zero-cost-rows=true
-- restores the quota for operators who share one small GPU). The failover
-- order puts zero-cost rows first.
--
-- NOT NULL DEFAULT FALSE: every existing row keeps today's behaviour. The
-- one backfill is rows registered from the OLLAMA provider, which are local
-- by construction (LocalLlmPoolBootstrap and the admin form's Ollama choice).
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN IF NOT EXISTS zero_cost BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE control_plane.platform_hosted_llm_provider
   SET zero_cost = TRUE
 WHERE provider_name = 'OLLAMA'
   AND zero_cost = FALSE;
