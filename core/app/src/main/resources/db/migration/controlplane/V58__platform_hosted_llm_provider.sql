-- "yes go ahead, apart from grok, i should be able to update multiple
-- providers, on run time" - a runtime-editable pool of PLATFORM-HELD (not
-- BYOK) LLM endpoints backing the FREE subscription tier's chat traffic
-- (see Agentic-AI-Platform-Subscription-Plan-and-GTM-Strategy.md). Unlike
-- every LlmProviderCredential row (one per tenant, per ProviderName, BYOK
-- key), this table holds the PLATFORM's own keys for open-source-model
-- inference providers (Groq, Together AI, Fireworks, etc.) - multiple rows
-- can exist and be added/edited/disabled by a platform-admin at runtime
-- without a code change or redeploy, since base_url/model/key are all data
-- here rather than one dedicated AgentChatClientFactory class per provider
-- (see HostedLlmChatClientFactory in core/chat, which builds a ChatClient
-- generically from a row of this table the same way
-- OpenRouterAgentChatClientFactory/GrokAgentChatClientFactory point
-- Spring AI's OpenAiApi at a fixed baseUrl, just runtime-configurable
-- instead of hardcoded).
--
-- Schema-qualified as control_plane.*, same convention as every other
-- platform-wide (not per-tenant) table - see V39's header comment.
--
-- priority: lower value = tried first. Multiple enabled rows let an admin
-- configure failover (e.g. Groq primary, Together AI as a fallback if Groq
-- has an outage) without any code change.
CREATE TABLE control_plane.platform_hosted_llm_provider (
    id                 BIGSERIAL PRIMARY KEY,
    label              VARCHAR(128) NOT NULL,
    base_url           VARCHAR(255) NOT NULL,
    model              VARCHAR(128) NOT NULL,
    encrypted_api_key  TEXT         NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    priority           INTEGER      NOT NULL DEFAULT 100,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    last_validated_at  TIMESTAMPTZ
);
