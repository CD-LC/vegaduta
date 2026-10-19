package com.agenticai.core.agents.importer;

import com.agenticai.core.providers.ProviderName;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a source platform's provider words to the platform's {@link ProviderName}. Pure. Only the
 * provider is mapped here; whether a given MODEL exists is decided against the live catalog by
 * {@code AgentImportService} - a model name is never invented.
 */
public final class AgentImportProviders {

    private AgentImportProviders() {
    }

    /** Provider hint strings used by the mappers (lower-case, normalised). */
    private static final Map<String, ProviderName> BY_HINT = Map.ofEntries(
        Map.entry("openai", ProviderName.OPENAI),
        Map.entry("azure-openai", ProviderName.AZURE_OPENAI),
        Map.entry("anthropic", ProviderName.ANTHROPIC),
        Map.entry("gemini", ProviderName.GEMINI),
        Map.entry("google", ProviderName.GEMINI),
        Map.entry("google-genai", ProviderName.GEMINI),
        Map.entry("google-vertex", ProviderName.GOOGLE_VERTEX),
        Map.entry("vertex", ProviderName.GOOGLE_VERTEX),
        Map.entry("aws-bedrock", ProviderName.AWS_BEDROCK),
        Map.entry("bedrock", ProviderName.AWS_BEDROCK),
        Map.entry("mistral", ProviderName.MISTRAL),
        Map.entry("deepseek", ProviderName.DEEPSEEK),
        Map.entry("groq", ProviderName.GROQ),
        Map.entry("ollama", ProviderName.OLLAMA),
        Map.entry("openrouter", ProviderName.OPENROUTER),
        Map.entry("grok", ProviderName.GROK),
        Map.entry("xai", ProviderName.GROK),
        Map.entry("meta", ProviderName.META),
        Map.entry("sarvam", ProviderName.SARVAM)
    );

    /**
     * Flowise chat-model node {@code data.name} values -> provider hint. The first six are confirmed
     * against Flowise's own marketplace templates / component sources
     * (packages/components/nodes/chatmodels/*); the rest follow the same naming and were read from
     * the same component sources.
     */
    private static final Map<String, String> FLOWISE_CHAT_MODEL_PROVIDER = Map.ofEntries(
        Map.entry("chatopenai", "openai"),
        Map.entry("chatanthropic", "anthropic"),
        Map.entry("chatgooglegenerativeai", "gemini"),
        Map.entry("chatmistralai", "mistral"),
        Map.entry("groqchat", "groq"),
        Map.entry("chatgooglevertexai", "google-vertex"),
        Map.entry("azurechatopenai", "azure-openai"),
        Map.entry("awschatbedrock", "aws-bedrock"),
        Map.entry("chatollama", "ollama"),
        Map.entry("chatopenrouter", "openrouter"),
        Map.entry("chatdeepseek", "deepseek"),
        Map.entry("chatxai", "grok")
    );

    /** The provider for a hint like {@code "openai"} (case/spacing tolerant); empty when unknown. */
    public static Optional<ProviderName> fromHint(String hint) {
        if (hint == null) {
            return Optional.empty();
        }
        String key = hint.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return Optional.ofNullable(BY_HINT.get(key));
    }

    /** Provider hint for a Flowise chat-model node name, or null when the node isn't a known chat model. */
    public static String flowiseProviderHint(String nodeName) {
        if (nodeName == null) {
            return null;
        }
        return FLOWISE_CHAT_MODEL_PROVIDER.get(nodeName.trim().toLowerCase(Locale.ROOT));
    }
}
