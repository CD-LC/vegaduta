package com.agenticai.core.knowledge.embedding;

import com.agenticai.core.knowledge.EmbeddingModelCatalog;
import com.agenticai.core.knowledge.SidecarCommunicationException;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link EmbeddingClient} for a local Ollama ({@code POST /api/embed}): the free path, so RAG works
 * with no paid key and no Python sidecar. Models such as {@code nomic-embed-text}, {@code bge-m3}
 * or {@code qwen3-embedding} are pulled with {@code ollama pull <model>}.
 *
 * <p>Active when {@code app.embeddings.provider=ollama}, or {@code auto} with an Ollama configured and
 * no cloud key (see {@link EmbeddingProviderSelection}). Width handling never mixes dimensions
 * silently: the model's native width is taken from {@link OllamaEmbeddingModels} (or
 * {@code app.embeddings.ollama.dimensions}, or learned from the first response) and every later
 * vector is checked against it. Vectors wider than their pgvector column are cut to a prefix and
 * re-normalised only for Matryoshka-trained models (or when explicitly allowed); otherwise the call is
 * refused with a message naming the fix - see {@link com.agenticai.core.knowledge.SidecarClient}.
 */
@Component
@Conditional(EmbeddingProviderSelection.OllamaSelected.class)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class OllamaEmbeddingClient implements EmbeddingClient {

    public static final String PROVIDER = EmbeddingProviderSelection.OLLAMA;

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final EmbeddingProperties properties;
    private final EmbeddingProperties.Ollama cfg;
    private final String baseUrl;
    private final RestClient restClient;
    private final String queryPrefix;
    private final String documentPrefix;
    private final boolean truncationAllowed;

    /** Native width: configured or from the model table, else 0 until the first response teaches it. */
    private volatile int dimensions;

    @Autowired
    public OllamaEmbeddingClient(EmbeddingProperties properties, Environment environment) {
        this(properties, EmbeddingProviderSelection.ollamaBaseUrl(environment), defaultRestClientBuilder(properties));
    }

    /** Visible for tests (inject a mock-server-bound builder). */
    OllamaEmbeddingClient(EmbeddingProperties properties, String baseUrl, RestClient.Builder builder) {
        this.properties = properties;
        this.cfg = properties.getOllama();
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        OllamaEmbeddingModels.Spec spec = OllamaEmbeddingModels.lookup(cfg.getModel()).orElse(null);
        this.dimensions = cfg.getDimensions() > 0 ? cfg.getDimensions() : spec != null ? spec.dimensions() : 0;
        this.truncationAllowed = cfg.getAllowTruncation() != null
            ? cfg.getAllowTruncation()
            : spec != null && spec.matryoshka();
        this.queryPrefix = cfg.getQueryPrefix() != null ? cfg.getQueryPrefix() : spec != null ? spec.queryPrefix() : "";
        this.documentPrefix = cfg.getDocumentPrefix() != null ? cfg.getDocumentPrefix() : spec != null ? spec.documentPrefix() : "";
        validate();
        this.restClient = builder.baseUrl(this.baseUrl).build();
        log.info("Embeddings: Ollama at {} (model '{}', {} dims{}) - the Python sidecar is NOT used for embeddings",
            this.baseUrl, cfg.getModel(), dimensions == 0 ? "width learned on first call" : dimensions,
            truncationAllowed ? ", truncation to column width allowed" : "");
    }

    /** For a base URL known only at run time (the registered Ollama), see {@link RegisteredOllamaEmbeddingResolver}. */
    static OllamaEmbeddingClient forBaseUrl(EmbeddingProperties properties, String baseUrl) {
        return new OllamaEmbeddingClient(properties, baseUrl, defaultRestClientBuilder(properties));
    }

    private static RestClient.Builder defaultRestClientBuilder(EmbeddingProperties properties) {
        // First call may load the model into memory, so allow longer than a warm request.
        Duration read = properties.getTimeout() == null ? Duration.ofSeconds(30) : properties.getTimeout();
        return RestClient.builder().requestFactory(new GuardedRequestFactory(read));
    }

    /**
     * Request factory over {@link SafeHttpClients}: every connection resolves through the process-wide
     * {@link SsrfGuard}, so a private or loopback Ollama must be listed in {@code
     * app.security.outbound.allowed-private-hosts} (the same switch as the local-models API and the
     * local-LLM bootstrap). The client is built on first use, after the guard has been installed, and
     * rebuilt if the guard is replaced.
     */
    private static final class GuardedRequestFactory implements ClientHttpRequestFactory {
        private final Duration readTimeout;
        private SsrfGuard guard;
        private ClientHttpRequestFactory delegate;

        GuardedRequestFactory(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            return delegate().createRequest(uri, httpMethod);
        }

        private synchronized ClientHttpRequestFactory delegate() {
            SsrfGuard current = SsrfGuard.defaultGuard();
            if (delegate == null || current != guard) {
                guard = current;
                delegate = SafeHttpClients.requestFactory(current, Duration.ofSeconds(5), readTimeout, false);
            }
            return delegate;
        }
    }

    /** Fail-fast startup validation; messages name the exact property to fix. */
    private void validate() {
        List<String> problems = new ArrayList<>();
        if (baseUrl.isBlank()) {
            problems.add("app.embeddings.ollama.base-url (APP_EMBEDDINGS_OLLAMA_BASE_URL) is required, e.g. http://localhost:11434");
        }
        if (cfg.getModel() == null || cfg.getModel().isBlank()) {
            problems.add("app.embeddings.ollama.model (APP_EMBEDDINGS_OLLAMA_MODEL) is required, e.g. nomic-embed-text");
        }
        if (properties.getBatchSize() < 1) {
            problems.add("app.embeddings.batch-size must be >= 1");
        }
        if (dimensions > EmbeddingModelCatalog.STORAGE_DIMENSIONS && !truncationAllowed) {
            problems.add("model '" + cfg.getModel() + "' produces " + dimensions + "-dim vectors but knowledge_chunks stores vector("
                + EmbeddingModelCatalog.STORAGE_DIMENSIONS + ") and the model is not known to survive truncation. "
                + "Use a model of <= " + EmbeddingModelCatalog.STORAGE_DIMENSIONS + " dims (nomic-embed-text, embeddinggemma, "
                + "all-minilm), a Matryoshka model (qwen3-embedding, mxbai-embed-large), or set "
                + "app.embeddings.ollama.allow-truncation=true to cut the vector to its first "
                + EmbeddingModelCatalog.STORAGE_DIMENSIONS + " dims (lower recall for models not trained for it)");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                "app.embeddings.provider=" + PROVIDER + " is misconfigured: " + String.join("; ", problems));
        }
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String modelId() {
        return cfg.getModel().trim();
    }

    @Override
    public boolean supportsTruncation() {
        return truncationAllowed;
    }

    /** Native width. Unknown models are probed once with a one-word embedding. */
    @Override
    public int dimensions() {
        int known = dimensions;
        if (known > 0) {
            return known;
        }
        embed(List.of("dimension probe"));
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return run(texts, documentPrefix);
    }

    @Override
    public List<float[]> embedQuery(List<String> texts) {
        return run(texts, queryPrefix);
    }

    private List<float[]> run(List<String> texts, String prefix) {
        List<float[]> out = new ArrayList<>(texts.size());
        int batch = properties.getBatchSize();
        for (int from = 0; from < texts.size(); from += batch) {
            List<String> slice = texts.subList(from, Math.min(texts.size(), from + batch));
            out.addAll(embedBatch(prefix.isEmpty() ? slice : withPrefix(prefix, slice)));
        }
        return out;
    }

    private static List<String> withPrefix(String prefix, List<String> texts) {
        List<String> prefixed = new ArrayList<>(texts.size());
        for (String text : texts) {
            prefixed.add(prefix + text);
        }
        return prefixed;
    }

    private List<float[]> embedBatch(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel().trim());
        body.put("input", texts);
        body.put("truncate", true);
        if (cfg.getKeepAlive() != null && !cfg.getKeepAlive().isBlank()) {
            body.put("keep_alive", cfg.getKeepAlive().trim());
        }
        JsonNode response;
        try {
            response = restClient.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new SidecarCommunicationException("Ollama does not have embedding model '" + cfg.getModel()
                    + "'. Run: ollama pull " + cfg.getModel().trim(), e);
            }
            throw new SidecarCommunicationException("Ollama returned HTTP " + e.getStatusCode().value()
                + " for /api/embed: " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            String hint = String.valueOf(e.getMessage()).contains("refused outbound connection")
                ? " The outbound policy refused it: list the Ollama host (e.g. localhost:11434 or ollama:11434) in"
                    + " app.security.outbound.allowed-private-hosts (APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS)."
                : "";
            throw new SidecarCommunicationException("Failed to reach Ollama at " + baseUrl
                + " for embeddings (is it running? 'ollama serve'): " + e.getMessage() + hint, e);
        }
        JsonNode data = response == null ? null : response.get("embeddings");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw new SidecarCommunicationException("Ollama returned "
                + (data == null || !data.isArray() ? "no embeddings array" : data.size() + " embeddings")
                + " for " + texts.size() + " inputs", null);
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < data.size(); i++) {
            JsonNode embedding = data.get(i);
            if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                throw new SidecarCommunicationException("Ollama returned a malformed embedding at position " + i, null);
            }
            checkWidth(embedding.size());
            float[] vector = new float[embedding.size()];
            for (int d = 0; d < vector.length; d++) {
                vector[d] = (float) embedding.get(d).asDouble();
            }
            result.add(OpenAiCompatibleEmbeddingClient.normalize(vector));
        }
        return result;
    }

    /** First response teaches an unknown model's width; every later vector must match it. */
    private void checkWidth(int observed) {
        int expected = dimensions;
        if (expected == 0) {
            if (observed > EmbeddingModelCatalog.STORAGE_DIMENSIONS && !truncationAllowed) {
                // Not recorded: every call keeps refusing until the model or the property is fixed.
                throw new SidecarCommunicationException("Ollama model '" + cfg.getModel() + "' returned " + observed
                    + "-dim vectors, wider than the " + EmbeddingModelCatalog.STORAGE_DIMENSIONS
                    + "-dim knowledge column. Pick a narrower model or set app.embeddings.ollama.allow-truncation=true", null);
            }
            dimensions = observed;
        } else if (observed != expected) {
            throw new SidecarCommunicationException("Ollama returned a " + observed + "-dim vector for model '"
                + cfg.getModel() + "' but " + expected + " dims were expected (app.embeddings.ollama.dimensions or "
                + "the model table). Refusing to mix widths - fix the property to match the model", null);
        }
    }
}
