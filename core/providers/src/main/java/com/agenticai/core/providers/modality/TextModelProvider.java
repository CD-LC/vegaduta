package com.agenticai.core.providers.modality;

/**
 * Text/LLM provider options for agents. Each represents a configurable LLM backend
 * that agents can route text-to-text completions through.
 *
 * <p>When an agent streams a response, the platform selects the LLM provider based
 * on this value and the associated model name. At agent-creation time or when
 * explicitly changed, the platform validates that the selected provider is available
 * on this deployment (see {@link TextModelProviderFactory}) and that the model exists
 * for that provider.
 *
 * <p>Unlike voice providers (which follow a mixed deployment-managed/BYOK model),
 * text providers are aligned with the platform's broader LLM access model:
 * <ul>
 *   <li>{@link #ANTHROPIC} - tenant BYOK (Claude family). Requires an active
 *       {@code AnthropicCredential} for this tenant.</li>
 *   <li>{@link #OPENAI} - tenant BYOK (GPT family). Requires an active
 *       {@code OpenAICredential}.</li>
 *   <li>{@link #GEMINI} - tenant BYOK (Gemini family). Requires an active
 *       {@code GeminiCredential}.</li>
 *   <li>{@link #GROK} - tenant BYOK (Grok family). Requires an active
 *       {@code GrokCredential}.</li>
 *   <li>{@link #SARVAM} - tenant BYOK (Sarvam family, India-optimized).
 *       Requires an active {@code SarvamCredential}.</li>
 *   <li>Others (MISTRAL, DEEPSEEK, GROQ, OLLAMA, etc.) - additional BYOK options.
 *       Each requires its corresponding credential to be active.</li>
 * </ul>
 *
 * <p>When null on an agent, the agent uses its primary {@code providerName} and
 * {@code model} fields as before - this enables backward compatibility and
 * zero-migration for existing agents.
 *
 * <p>Plan gating: Text provider selection is a Premium/Unlimited feature. FREE
 * tenants cannot select a custom text provider and always use the platform's
 * hosted LLM pool.
 */
public enum TextModelProvider {
    /**
     * Anthropic's Claude family (Claude Opus, Sonnet, Haiku). Tenant must
     * have an active {@code AnthropicCredential}.
     */
    ANTHROPIC("anthropic", "claude-haiku-4-5-20251001"),

    /**
     * OpenAI's GPT family (GPT-5.1, GPT-4o, etc.). Tenant must have an
     * active {@code OpenAICredential}.
     */
    OPENAI("openai", "gpt-4o-mini"),

    /**
     * Google's Gemini family. Tenant must have an active
     * {@code GeminiCredential}.
     */
    GEMINI("gemini", "gemini-3.1-flash-lite"),

    /**
     * xAI's Grok family. Tenant must have an active {@code GrokCredential}.
     * Default moved off {@code grok-3-mini} (retired by xAI 2026-05-15,
     * hard-400s now) to the current fast/cheap tier - see
     * {@code ProviderModelCatalogService}'s GROK seed comment.
     */
    GROK("grok", "grok-4.20-0309-non-reasoning"),

    /**
     * Sarvam's India-optimized LLM family. Tenant must have an active
     * {@code SarvamCredential}. Sarvam models support transliteration and
     * code-mixing in Indian languages alongside English.
     */
    SARVAM("sarvam", "sarvam-105b"),

    /**
     * Mistral's LLM family. Tenant must have an active
     * {@code MistralCredential}.
     */
    MISTRAL("mistral", "mistral-small-latest"),

    /**
     * DeepSeek's LLM family. Tenant must have an active
     * {@code DeepSeekCredential}. Default moved off {@code deepseek-chat}
     * (V3 endpoint, retired in favor of the V4 line - see
     * {@code ProviderModelCatalogService}'s DEEPSEEK seed comment).
     */
    DEEPSEEK("deepseek", "deepseek-v4-flash"),

    /**
     * Groq's LLM family. Tenant must have an active {@code GroqCredential}.
     */
    GROQ("groq", "llama-3.3-70b-versatile"),

    /**
     * Meta's Muse Spark family (Meta Model API, {@code api.meta.ai}). Tenant
     * must have an active {@code META} {@code LlmProviderCredential}. Default
     * is the recommended {@code muse-spark-1.3}; the cheaper
     * {@code *-contributor} variants (Meta trains on that traffic) are never
     * a default - see {@code ProviderModelCatalogService}'s META seed comment.
     */
    META("meta", "muse-spark-1.3"),

    /**
     * Ollama - tenant-hosted local/on-premise LLM server. Tenant must have
     * an active {@code OllamaCredential} pointing to their server.
     */
    OLLAMA("ollama", "llama3.1:8b"),

    /**
     * A self-hosted OpenAI-compatible server (vLLM, llama.cpp, LM Studio, Jan,
     * LocalAI, ...). Tenant must have an active {@code LocalOpenAiCredential}.
     * No default model: what a local server serves is whatever its owner
     * loaded, so the model is always chosen from its live {@code /v1/models}
     * list (empty string, like {@link #OPENROUTER}).
     */
    LOCAL_OPENAI("local-openai", ""),

    /**
     * OpenRouter - multi-provider proxy service. Tenant must have an active
     * {@code OpenRouterCredential}.
     */
    OPENROUTER("openrouter", "");

    private final String providerId;
    private final String defaultModel;

    TextModelProvider(String providerId, String defaultModel) {
        this.providerId = providerId;
        this.defaultModel = defaultModel;
    }

    public String getProviderId() {
        return providerId;
    }

    /**
     * Default model for this provider - used as a starting point in the UI
     * and as a fallback if the agent's configured model becomes unavailable.
     * Empty string for providers (like OPENROUTER) where there is no single
     * canonical default.
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * Whether this provider's model selection is available to the given plan.
     *
     * <ul>
     *   <li>FREE: text provider selection is not available (uses platform pool)</li>
     *   <li>PREMIUM: all BYOK text providers available</li>
     *   <li>UNLIMITED: all BYOK text providers available</li>
     * </ul>
     */
    public boolean isAvailableForPlan(com.agenticai.core.security.TenantPlan plan) {
        // FREE tier: text provider selection is Premium/Unlimited only
        return plan != com.agenticai.core.security.TenantPlan.FREE;
    }
}
