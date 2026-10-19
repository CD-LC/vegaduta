package com.agenticai.core.knowledge.embedding;

import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Decides which {@link EmbeddingClient} (if any) backs the embedding path. One place, so the two
 * conditional beans can never both be active.
 *
 * <ul>
 *   <li>{@code sidecar} (also unset and any unrecognised value): no bean, the Python sidecar embeds
 *       exactly as before.</li>
 *   <li>{@code openai-compatible}, {@code ollama}: that provider, as configured.</li>
 *   <li>{@code auto}: the free path when it is available, never a surprise bill. A configured cloud
 *       key ({@code app.embeddings.api-key} with {@code app.embeddings.base-url}) wins and selects
 *       {@code openai-compatible}; otherwise an Ollama that is configured
 *       ({@code app.embeddings.ollama.base-url}, or the local-LLM bootstrap Ollama) selects
 *       {@code ollama}; otherwise {@code sidecar}, except that an Ollama registered at run time (the
 *       console's local-model wizard) is then used per call - see {@link RegisteredOllamaEmbeddingResolver}.</li>
 * </ul>
 */
public final class EmbeddingProviderSelection {

    public static final String SIDECAR = "sidecar";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String OLLAMA = "ollama";
    public static final String AUTO = "auto";

    private static final String DEFAULT_BOOTSTRAP_OLLAMA = "http://ollama:11434";

    private EmbeddingProviderSelection() {
    }

    /** The provider that is actually active for this environment: one of {@link #SIDECAR}, {@link #OPENAI_COMPATIBLE}, {@link #OLLAMA}. */
    public static String resolve(Environment env) {
        String configured = env.getProperty("app.embeddings.provider", SIDECAR).trim().toLowerCase(Locale.ROOT);
        switch (configured) {
            case OPENAI_COMPATIBLE:
                return OPENAI_COMPATIBLE;
            case OLLAMA:
                return OLLAMA;
            case AUTO:
                if (StringUtils.hasText(env.getProperty("app.embeddings.api-key"))
                    && StringUtils.hasText(env.getProperty("app.embeddings.base-url"))) {
                    return OPENAI_COMPATIBLE;
                }
                return StringUtils.hasText(ollamaBaseUrl(env)) ? OLLAMA : SIDECAR;
            default:
                return SIDECAR;
        }
    }

    /**
     * The Ollama root URL, or blank when none is configured. Falls back to the local-LLM bootstrap
     * Ollama (the one the chat pool is registered against) so one Ollama serves chat and embeddings.
     */
    public static String ollamaBaseUrl(Environment env) {
        String explicit = env.getProperty("app.embeddings.ollama.base-url", "");
        if (StringUtils.hasText(explicit)) {
            return normalizeOllamaBaseUrl(explicit);
        }
        if (Boolean.parseBoolean(env.getProperty("app.local-llm.bootstrap.enabled", "false"))) {
            return normalizeOllamaBaseUrl(env.getProperty("app.local-llm.bootstrap.base-url", DEFAULT_BOOTSTRAP_OLLAMA));
        }
        return "";
    }

    /** Trims, drops trailing slashes and a trailing {@code /v1} (the OpenAI-compatible suffix), which the native API does not use. */
    public static String normalizeOllamaBaseUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        return url;
    }

    /** Matches when {@link #resolve} is {@link #OPENAI_COMPATIBLE}. */
    public static class OpenAiCompatibleSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return OPENAI_COMPATIBLE.equals(resolve(context.getEnvironment()));
        }
    }

    /**
     * Matches for {@code auto} when the start-up decision was the sidecar (no cloud key, no Ollama in the
     * environment): the case where an Ollama registered at run time may still be used, see {@link
     * RegisteredOllamaEmbeddingResolver}.
     */
    public static class AutoWithoutConfiguredOllama implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            String configured = env.getProperty("app.embeddings.provider", SIDECAR).trim().toLowerCase(Locale.ROOT);
            return AUTO.equals(configured) && SIDECAR.equals(resolve(env));
        }
    }

    /** Matches when {@link #resolve} is {@link #OLLAMA}. */
    public static class OllamaSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return OLLAMA.equals(resolve(context.getEnvironment()));
        }
    }
}
