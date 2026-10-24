package com.agenticai.core.providers;

import java.net.URI;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.llm.endpoints.<provider>.*}: where the platform reaches each model vendor. One base URL per vendor,
 * read by the key validators, the live model-list fetcher, the hosted pool's "pick a provider, paste a key" flow
 * and (through {@link #baseUrl(ProviderName)}) the chat-client factories, which used to carry their own copy of
 * the same literal. Pointing a vendor at a corporate proxy, a regional endpoint or a mock server is now one key
 * per vendor.
 *
 * <p>The defaults are the vendors' public endpoints, exactly the literals this class replaces; the same values
 * are in {@code application.yml}. A blank value falls back to the default (so an empty environment variable never
 * produces a request to nowhere). A base URL must be {@code http} or {@code https}; anything else fails startup.
 * Trailing slashes are removed. Paths always start with a slash.
 *
 * <p>A vendor's base URL is written the way its chat-completions client appends {@code /v1/chat/completions}
 * (Groq {@code https://api.groq.com/openai}, OpenRouter {@code https://openrouter.ai/api}), never with a trailing
 * {@code /v1}.
 */
@ConfigurationProperties(prefix = "app.llm.endpoints")
public class ProviderEndpoints {

    /** One vendor's endpoint. Every field falls back to its default when blank. */
    public static class Endpoint {

        private final String defaultBaseUrl;
        private final String defaultModelsPath;
        private final String defaultCompletionsPath;
        private String baseUrl;
        private String modelsPath;
        private String completionsPath;

        Endpoint(String baseUrl, String modelsPath, String completionsPath) {
            this.defaultBaseUrl = baseUrl;
            this.defaultModelsPath = modelsPath;
            this.defaultCompletionsPath = completionsPath;
            this.baseUrl = baseUrl;
            this.modelsPath = modelsPath;
            this.completionsPath = completionsPath;
        }

        /** Scheme and host (and path prefix, if the vendor needs one) every call to this vendor starts with. */
        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = normalizeBaseUrl(baseUrl, defaultBaseUrl);
        }

        /** Appended to the base URL to list the vendor's models (also what a key is validated against). */
        public String getModelsPath() {
            return modelsPath;
        }

        public void setModelsPath(String modelsPath) {
            this.modelsPath = normalizePath(modelsPath, defaultModelsPath);
        }

        /**
         * The chat-completions path when it is not the conventional {@code /v1/chat/completions}; null for the
         * conventional one.
         */
        public String getCompletionsPath() {
            return completionsPath;
        }

        public void setCompletionsPath(String completionsPath) {
            this.completionsPath = normalizePath(completionsPath, defaultCompletionsPath);
        }

        public String modelsUrl() {
            return baseUrl + modelsPath;
        }
    }

    private static final String CONVENTIONAL_MODELS_PATH = "/v1/models";

    private Endpoint anthropic = new Endpoint("https://api.anthropic.com", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint openai = new Endpoint("https://api.openai.com", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint grok = new Endpoint("https://api.x.ai", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint gemini = new Endpoint(
        "https://generativelanguage.googleapis.com", "/v1beta/models", "/v1beta/openai/chat/completions");
    private Endpoint mistral = new Endpoint("https://api.mistral.ai", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint deepseek = new Endpoint("https://api.deepseek.com", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint groq = new Endpoint("https://api.groq.com/openai", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint meta = new Endpoint("https://api.meta.ai", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint openrouter = new Endpoint("https://openrouter.ai/api", CONVENTIONAL_MODELS_PATH, null);
    private Endpoint sarvam = new Endpoint("https://api.sarvam.ai", CONVENTIONAL_MODELS_PATH, null);

    /** xAI's richer model list (modalities, context window); the plain model list is the fallback. */
    private String grokLanguageModelsPath = "/v1/language-models";

    /** OpenRouter's key check. */
    private String openrouterAuthKeyPath = "/v1/auth/key";

    /** The vendor's endpoint, or empty for a provider that has no public vendor endpoint (Ollama, Azure, ...). */
    public Optional<Endpoint> endpoint(ProviderName provider) {
        if (provider == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (provider) {
            case ANTHROPIC -> anthropic;
            case OPENAI -> openai;
            case GROK -> grok;
            case GEMINI -> gemini;
            case MISTRAL -> mistral;
            case DEEPSEEK -> deepseek;
            case GROQ -> groq;
            case META -> meta;
            case OPENROUTER -> openrouter;
            case SARVAM -> sarvam;
            default -> null;
        });
    }

    /** The base URL for {@code provider}; throws for a provider with no public vendor endpoint. */
    public String baseUrl(ProviderName provider) {
        return endpoint(provider)
            .orElseThrow(() -> new IllegalArgumentException("No vendor endpoint is configured for " + provider))
            .getBaseUrl();
    }

    /** Base URL plus models path, e.g. {@code https://api.openai.com/v1/models}. */
    public String modelsUrl(ProviderName provider) {
        return endpoint(provider)
            .orElseThrow(() -> new IllegalArgumentException("No vendor endpoint is configured for " + provider))
            .modelsUrl();
    }

    public String grokLanguageModelsUrl() {
        return grok.getBaseUrl() + grokLanguageModelsPath;
    }

    public String openrouterAuthKeyUrl() {
        return openrouter.getBaseUrl() + openrouterAuthKeyPath;
    }

    /** Every provider that has a vendor endpoint, for tests and the docs table. */
    public Map<ProviderName, Endpoint> all() {
        Map<ProviderName, Endpoint> all = new EnumMap<>(ProviderName.class);
        for (ProviderName provider : ProviderName.values()) {
            endpoint(provider).ifPresent(e -> all.put(provider, e));
        }
        return all;
    }

    static String normalizeBaseUrl(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid base URL: " + value, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new IllegalArgumentException("A provider base URL must be an http(s) URL with a host: " + value);
        }
        return trimmed;
    }

    static String normalizePath(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    public Endpoint getAnthropic() { return anthropic; }
    public void setAnthropic(Endpoint anthropic) { this.anthropic = anthropic; }
    public Endpoint getOpenai() { return openai; }
    public void setOpenai(Endpoint openai) { this.openai = openai; }
    public Endpoint getGrok() { return grok; }
    public void setGrok(Endpoint grok) { this.grok = grok; }
    public Endpoint getGemini() { return gemini; }
    public void setGemini(Endpoint gemini) { this.gemini = gemini; }
    public Endpoint getMistral() { return mistral; }
    public void setMistral(Endpoint mistral) { this.mistral = mistral; }
    public Endpoint getDeepseek() { return deepseek; }
    public void setDeepseek(Endpoint deepseek) { this.deepseek = deepseek; }
    public Endpoint getGroq() { return groq; }
    public void setGroq(Endpoint groq) { this.groq = groq; }
    public Endpoint getMeta() { return meta; }
    public void setMeta(Endpoint meta) { this.meta = meta; }
    public Endpoint getOpenrouter() { return openrouter; }
    public void setOpenrouter(Endpoint openrouter) { this.openrouter = openrouter; }
    public Endpoint getSarvam() { return sarvam; }
    public void setSarvam(Endpoint sarvam) { this.sarvam = sarvam; }

    public String getGrokLanguageModelsPath() { return grokLanguageModelsPath; }

    public void setGrokLanguageModelsPath(String path) {
        this.grokLanguageModelsPath = normalizePath(path, "/v1/language-models");
    }

    public String getOpenrouterAuthKeyPath() { return openrouterAuthKeyPath; }

    public void setOpenrouterAuthKeyPath(String path) {
        this.openrouterAuthKeyPath = normalizePath(path, "/v1/auth/key");
    }
}
