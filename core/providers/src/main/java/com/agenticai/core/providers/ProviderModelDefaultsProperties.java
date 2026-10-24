package com.agenticai.core.providers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.models.*}: which model a new agent starts on per provider, which retired model ids are replaced by
 * which current ones, and the keyword order used when no hand-picked default exists. These were three maps and two
 * keyword lists in {@link ProviderModelCatalogService} and {@link HostedPoolRequestResolver}; a vendor
 * retirement or a new fast model is now a configuration change instead of a release.
 *
 * <p>The code defaults equal the values the code carried (and {@code application.yml} repeats them). Maps are
 * merged with the defaults: setting {@code app.models.defaults.grok} replaces only the Grok pick; setting a key to
 * blank removes the entry (no hand-picked default for that provider, or no replacement for that retired id).
 * A retired id that contains a dot must be written in bracket form in YAML ({@code "[grok-4.20]": ...}).
 */
@ConfigurationProperties(prefix = "app.models")
public class ProviderModelDefaultsProperties {

    /**
     * Hand-picked "sensible starting model" per provider (lower-case provider name), used only when present in
     * the provider's current catalog; a stale pick degrades to the keyword scan, never to a dead id.
     */
    private Map<String, String> defaults = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("anthropic", "claude-haiku-4-5-20251001"),
        Map.entry("openai", "gpt-4o-mini"),
        Map.entry("gemini", "gemini-2.5-flash"),
        Map.entry("grok", "grok-4-1-fast-non-reasoning"),
        Map.entry("sarvam", "sarvam-30b"),
        Map.entry("mistral", "mistral-small-latest"),
        Map.entry("deepseek", "deepseek-v4-flash"),
        Map.entry("groq", "llama-3.1-8b-instant"),
        Map.entry("meta", "muse-spark-1.3"),
        Map.entry("ollama", "qwen3.5:4b")));

    /**
     * Vendor-retired model id to its replacement. An agent whose stored model is a key here is run on the value
     * (with a warning, without rewriting the agent).
     */
    private Map<String, String> retired = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("grok-3", "grok-4.3"),
        Map.entry("grok-3-mini", "grok-4.20-0309-non-reasoning"),
        Map.entry("grok-4", "grok-4.5"),
        Map.entry("grok-4-fast", "grok-4.3"),
        Map.entry("deepseek-chat", "deepseek-v4-flash"),
        Map.entry("deepseek-reasoner", "deepseek-v4-pro"),
        Map.entry("gemma2-9b-it", "llama-3.1-8b-instant")));

    /**
     * Fallback when a provider has no (present) hand-picked default: the first keyword (a case-insensitive
     * substring of the model id) that matches any model wins; an earlier keyword scans the whole list before a
     * later one is tried.
     */
    private List<String> defaultPreferenceKeywords =
        new ArrayList<>(List.of("flash-lite", "flash", "mini", "haiku", "lite", "8b", "instant", "non-reasoning"));

    /**
     * The hosted pool's auto-selection order (FREE-tier traffic favours fast and cheap), same mechanics as above.
     */
    private List<String> hostedPoolAutoKeywords =
        new ArrayList<>(List.of("flash-lite", "flash", "mini", "haiku", "lite", "8b", "instant"));

    /**
     * Operator-set context window (tokens) per model id, replacing what the catalog (seed list or cached vendor
     * data) says. Empty by default: the catalog value stands. For a model whose real window differs from the seed
     * (a vendor raised it, a deployment limits it, an Ollama model run with a larger {@code num_ctx}). A model
     * id with a dot must use bracket form in YAML ({@code "[gpt-5.1]": 400000}).
     */
    private Map<String, Integer> contextWindows = new LinkedHashMap<>();

    /** The configured context window for {@code modelId}, if one is set and positive. */
    public Optional<Integer> contextWindowFor(String modelId) {
        if (modelId == null) {
            return Optional.empty();
        }
        Integer window = contextWindows.get(modelId);
        if (window == null) {
            window = contextWindows.get(modelId.toLowerCase(Locale.ROOT));
        }
        return window == null || window <= 0 ? Optional.empty() : Optional.of(window);
    }

    /** The hand-picked default model for {@code provider}, if one is configured. */
    public Optional<String> defaultFor(ProviderName provider) {
        return lookup(defaults, provider == null ? null : provider.name());
    }

    /** The current replacement for a retired model id, if one is configured. */
    public Optional<String> replacementFor(String modelId) {
        return lookup(retired, modelId);
    }

    private static Optional<String> lookup(Map<String, String> map, String key) {
        if (key == null) {
            return Optional.empty();
        }
        String exact = map.get(key);
        if (exact == null) {
            exact = map.get(key.toLowerCase(Locale.ROOT));
        }
        return exact == null || exact.isBlank() ? Optional.empty() : Optional.of(exact.trim());
    }

    public Map<String, String> getDefaults() {
        return defaults;
    }

    public void setDefaults(Map<String, String> defaults) {
        this.defaults = defaults == null ? new LinkedHashMap<>() : defaults;
    }

    public Map<String, String> getRetired() {
        return retired;
    }

    public void setRetired(Map<String, String> retired) {
        this.retired = retired == null ? new LinkedHashMap<>() : retired;
    }

    public Map<String, Integer> getContextWindows() {
        return contextWindows;
    }

    public void setContextWindows(Map<String, Integer> contextWindows) {
        this.contextWindows = contextWindows == null ? new LinkedHashMap<>() : contextWindows;
    }

    public List<String> getDefaultPreferenceKeywords() {
        return defaultPreferenceKeywords;
    }

    public void setDefaultPreferenceKeywords(List<String> keywords) {
        this.defaultPreferenceKeywords = keywords == null ? new ArrayList<>() : keywords;
    }

    public List<String> getHostedPoolAutoKeywords() {
        return hostedPoolAutoKeywords;
    }

    public void setHostedPoolAutoKeywords(List<String> keywords) {
        this.hostedPoolAutoKeywords = keywords == null ? new ArrayList<>() : keywords;
    }
}
