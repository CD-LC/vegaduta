package com.agenticai.core.providers;

/**
 * LLM providers the platform knows about. Not all are implemented yet —
 * see {@link com.agenticai.core.providers.validation.ProviderValidator} for
 * which ones currently have a working validator registered.
 *
 * <p>{@code OPENROUTER}, {@code GROK}, {@code GEMINI} added for "implement
 * openrouter, grok and gemini so that consumers can use them": OPENROUTER and
 * GROK are both OpenAI-chat-completions-compatible APIs, implemented by
 * reusing Spring AI's {@code spring-ai-starter-model-openai} module with a
 * fixed {@code baseUrl} per provider (see {@code OpenRouterAgentChatClientFactory}/
 * {@code GrokAgentChatClientFactory}) rather than a dedicated Spring AI
 * starter, since neither provider has one. GEMINI is the direct Google AI
 * Studio API-key path via {@code spring-ai-starter-model-google-genai} - see
 * {@code GeminiAgentChatClientFactory}.
 *
 * <p>{@code GOOGLE_VERTEX} (Vertex AI / Veo build-out): GCP service-account
 * auth, now implemented - see {@code VertexAiChatClientFactory} (chat),
 * {@code VertexImageGenerationClient} (image), {@code
 * VertexVeoVideoGenerationClient} (video), and {@code
 * GoogleVertexProviderValidator}. A tenant's BYOK credential here is not a
 * plain string key like every other provider - it's a project/location/
 * service-account blob, see {@code VertexCredential}'s javadoc for the
 * single-column encoding. The platform ALSO holds its own pool of up to 3
 * Vertex projects ({@code VertexPoolProperties}), used for the
 * platform-subsidized paths (anonymous demo, FREE-plan creative generation)
 * that must not depend on any tenant's own key.
 *
 * <p>{@code PLATFORM_HOSTED} is different in kind from every value above -
 * it is NOT a tenant-selectable, BYOK provider (no {@code
 * LlmProviderCredential} row, no {@code AgentChatClientFactory}, no {@code
 * ProviderValidator}, never listed in {@code ProvidersPanel.js}'s {@code
 * PROVIDERS}). It exists purely as the {@code providerName} value {@code
 * LlmMetricsAdvisor} attributes a {@code FREE}-plan tenant's chat call to
 * (see {@code HostedLlmChatClientFactory} in {@code core.chat}), so those
 * calls are visibly distinguishable in {@code LlmCallLog}/cost telemetry
 * from a real BYOK call - the actual upstream provider for any given call is
 * whichever {@code core.providers.PlatformHostedLlmProvider} row's {@code
 * label}/{@code baseUrl} was picked, which this enum value deliberately does
 * NOT try to encode (that pool is runtime-configurable data, not a fixed set
 * of code-level names).
 *
 * <p>{@code SARVAM}: Sarvam AI's
 * {@code sarvam-105b}/{@code sarvam-105b} chat models, reached the same
 * OpenAI-chat-completions-compatible way as {@code OPENROUTER}/{@code GROK}
 * (see {@code SarvamAgentChatClientFactory} in {@code core.chat}). The one
 * real difference from every value above: its BYOK credential does NOT live
 * in {@code LlmProviderCredential} — Sarvam's key is also needed by Stage 3's
 * non-chat clients (translate/STT/TTS/etc., {@code core.sarvam}), so it's
 * stored once in that module's own {@code SarvamCredential} table and {@link
 * com.agenticai.core.providers.ProviderCredentialResolver#resolveActiveKey}
 * branches to {@code SarvamCredentialService} for this one enum value rather
 * than reading {@code LlmProviderCredentialRepository} like every other
 * provider — see that method's javadoc.
 *
 * <p>{@code AZURE_OPENAI}, {@code AWS_BEDROCK}, {@code OLLAMA} implemented
 * (provider fan-out, July 2026): the three multi-field-credential providers.
 * {@code OLLAMA} ({@code OllamaCredential}/{@code OllamaProviderValidator}/
 * {@code OllamaAgentChatClientFactory}) is a tenant-hosted server (base URL +
 * optional key), reached OpenAI-chat-completions-compatible the same way as
 * {@code OPENROUTER}/{@code GROK}. {@code AZURE_OPENAI} ({@code
 * AzureOpenAiCredential}/{@code AzureOpenAIProviderValidator}/{@code
 * AzureOpenAIAgentChatClientFactory}) is also OpenAI-compatible but with a
 * non-standard {@code api-key} auth header and a per-tenant {@code
 * {endpoint}/openai/deployments/{deployment}} URL shape (same {@code
 * NoopApiKey}+custom-header trick {@code SarvamAgentChatClientFactory}
 * pioneered). {@code AWS_BEDROCK} ({@code AwsBedrockCredential}/{@code
 * AwsBedrockProviderValidator}/{@code AwsBedrockAgentChatClientFactory}) is
 * NOT OpenAI-compatible at all - it's a hand-rolled AWS SigV4-signed client
 * against the {@code software.amazon.awssdk:bedrockruntime} Converse API
 * (no Spring AI Bedrock starter dependency was added; see {@code
 * AwsBedrockAgentChatClientFactory}'s javadoc for why and what it does/
 * doesn't cover).
 *
 * <p>{@code MISTRAL}, {@code DEEPSEEK}, {@code GROQ} (bonus OpenAI-compatible
 * providers, same fan-out): each follows the exact {@code OPENROUTER}/{@code
 * GROK} simple-single-key-fixed-base-URL shape - see {@code
 * MistralAgentChatClientFactory}/{@code DeepSeekAgentChatClientFactory}/
 * {@code GroqAgentChatClientFactory} and their matching validators. {@code
 * GROQ} (GroqCloud, the fast-inference-cloud company) is deliberately a
 * separate value from {@code GROK} (xAI) above - same-sounding name,
 * unrelated company/API, do not confuse the two.
 *
 * <p>{@code META} (Meta Model API, {@code api.meta.ai} - the Muse Spark
 * family, added 2026-09-17): the same simple-single-key-fixed-base-URL
 * OpenAI-chat-completions-compatible shape as {@code GROK}/{@code DEEPSEEK} -
 * see {@code MetaAgentChatClientFactory} and {@code MetaProviderValidator}.
 * Chat model ids are {@code muse-spark-1.3} (recommended) / {@code
 * muse-spark-1.2} / {@code muse-spark-1.1}, plus the cheaper {@code
 * *-contributor} variants on which Meta TRAINS on the traffic (never a
 * default; labelled as such in the catalog). Muse Spark is also reachable
 * through {@code OPENROUTER} as {@code meta/muse-spark-1.3}, which matters
 * because Meta Model API sign-up is not open in every country yet.
 *
 * <p>{@code LOCAL_OPENAI} ("Local / OpenAI-compatible server"): first-class
 * support for a model server the operator or tenant runs themselves that
 * speaks the OpenAI chat-completions dialect - vLLM, llama.cpp's {@code
 * llama-server}, LM Studio, Jan, LocalAI, or anything else. Where {@code
 * OLLAMA} is the native-Ollama path, this is the generic one: base URL +
 * optional bearer key ({@code LocalOpenAiCredential}), validated and listed
 * through {@code GET {base}/v1/models} ({@code LocalOpenAiProviderValidator},
 * {@code LocalOpenAiServerClient}), chatted with by {@code
 * LocalOpenAiAgentChatClientFactory}. Private/loopback hosts are reachable
 * only when the operator lists them in {@code
 * app.security.outbound.allowed-private-hosts}; the SSRF guard is never
 * relaxed for it. Calls are metered at zero cost (a local server nobody bills
 * per token).
 */
public enum ProviderName {
    ANTHROPIC,
    OPENAI,
    AZURE_OPENAI,
    GOOGLE_VERTEX,
    AWS_BEDROCK,
    OLLAMA,
    OPENROUTER,
    GROK,
    GEMINI,
    SARVAM,
    MISTRAL,
    DEEPSEEK,
    GROQ,
    META,
    LOCAL_OPENAI,
    PLATFORM_HOSTED
}
