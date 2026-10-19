package com.agenticai.core.knowledge.embedding;

import com.agenticai.core.knowledge.SidecarCommunicationException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * {@code app.embeddings.provider=auto}, second half: when the environment names no Ollama (so the
 * start-up condition chose the sidecar), the Ollama registered at run time still wins. A personal install
 * whose owner picked a model in the console, with the local-AI bootstrap off, used to fall to the
 * sidecar: collections embedded by Ollama then lost vector search with only a log line, and without the
 * {@code rag} profile embeddings failed outright.
 *
 * <p>Order, unchanged from {@link EmbeddingProviderSelection}: a configured cloud key, then an Ollama
 * named in the environment (both decided at start-up, in which case this bean does not exist), then - new -
 * the registered Ollama, then the sidecar. The answer is cached briefly so a turn does not read the
 * database for every embedding; the vectors' source is recorded per collection (see {@code
 * KnowledgeEmbeddingBinding}), so a later change is refused rather than silently mixed.
 */
@Component
@Conditional(EmbeddingProviderSelection.AutoWithoutConfiguredOllama.class)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class RegisteredOllamaEmbeddingResolver {

    private static final Logger log = LoggerFactory.getLogger(RegisteredOllamaEmbeddingResolver.class);
    private static final long DEFAULT_TTL_NANOS = 30_000_000_000L;

    private final ObjectProvider<RegisteredOllamaLookup> lookup;
    private final EmbeddingProperties properties;
    private final long ttlNanos;
    private final Object lock = new Object();

    private long checkedAt;
    private String resolvedUrl;
    private OllamaEmbeddingClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public RegisteredOllamaEmbeddingResolver(ObjectProvider<RegisteredOllamaLookup> lookup, EmbeddingProperties properties) {
        this(lookup, properties, DEFAULT_TTL_NANOS);
    }

    /** Visible for tests: a zero TTL re-reads the lookup on every call. */
    RegisteredOllamaEmbeddingResolver(ObjectProvider<RegisteredOllamaLookup> lookup, EmbeddingProperties properties, long ttlNanos) {
        this.lookup = lookup;
        this.properties = properties;
        this.ttlNanos = ttlNanos;
    }

    /** The embedder for the registered Ollama, or null when none is registered (use the sidecar). */
    public EmbeddingClient resolve() {
        long now = System.nanoTime();
        synchronized (lock) {
            if (checkedAt != 0 && now - checkedAt < ttlNanos) {
                return client;
            }
            checkedAt = now == 0 ? 1 : now;
            String url = find();
            if (url == null) {
                client = null;
                resolvedUrl = null;
            } else if (!url.equals(resolvedUrl)) {
                try {
                    client = OllamaEmbeddingClient.forBaseUrl(properties, url);
                    resolvedUrl = url;
                    log.info("Embeddings: using the Ollama registered for this install at {} (app.embeddings.provider=auto)", url);
                } catch (RuntimeException e) {
                    // A misconfigured model/width must not break every call: keep the sidecar and say why.
                    log.warn("Embeddings: the registered Ollama at {} cannot be used ({}); using the sidecar", url, e.getMessage());
                    client = null;
                    resolvedUrl = null;
                }
            }
            return client;
        }
    }

    private String find() {
        RegisteredOllamaLookup source = lookup.getIfAvailable();
        if (source == null) {
            return null;
        }
        try {
            Optional<String> url = source.baseUrl();
            return url.map(EmbeddingProviderSelection::normalizeOllamaBaseUrl).filter(u -> !u.isBlank()).orElse(null);
        } catch (RuntimeException e) {
            // The database being briefly unavailable is not a reason to change where vectors come from.
            log.debug("Registered Ollama lookup failed: {}", e.toString());
            return resolvedUrl;
        }
    }
}
