package com.agenticai.core.knowledge.embedding;

import com.agenticai.core.knowledge.SidecarCommunicationException;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link EmbeddingClient} for any server implementing the OpenAI {@code POST /v1/embeddings}
 * contract (OpenAI, Ollama, vLLM, LM Studio, LiteLLM, text-embeddings-inference with the OpenAI
 * route, ...).
 *
 * <p>Opt-in only: exists solely when {@code app.embeddings.provider=openai-compatible} (or {@code
 * auto} with a cloud key, see {@link EmbeddingProviderSelection}). Configuration is validated in
 * the constructor so a misconfigured deployment fails at startup with a clear message instead of at
 * the first ingest.
 */
@Component
@Conditional(EmbeddingProviderSelection.OpenAiCompatibleSelected.class)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    public static final String PROVIDER = "openai-compatible";

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final EmbeddingProperties properties;
    private final RestClient restClient;

    @Autowired
    public OpenAiCompatibleEmbeddingClient(
            EmbeddingProperties properties, ObjectProvider<SsrfGuard> ssrfGuard) {
        // Injecting the guard bean orders this component after SsrfGuardConfiguration,
        // so the operator's allowed-private-hosts list is already installed.
        this(
                properties,
                defaultRestClientBuilder(
                        properties, ssrfGuard.getIfAvailable(SsrfGuard::defaultGuard)));
    }

    /** Visible for tests (inject a mock-server-bound builder). */
    OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties, RestClient.Builder builder) {
        validate(properties);
        this.properties = properties;
        String baseUrl = properties.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        RestClient.Builder configured = builder.baseUrl(baseUrl);
        String apiKey = properties.getApiKey() == null ? "" : properties.getApiKey().trim();
        if (!apiKey.isEmpty()) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.restClient = configured.build();
        log.info(
                "Embeddings: OpenAI-compatible provider at {} (model '{}', {} dims) - the Python sidecar is NOT "
                        + "used for embeddings",
                baseUrl,
                properties.getModel(),
                properties.getDimensions());
    }

    private static RestClient.Builder defaultRestClientBuilder(
            EmbeddingProperties properties, SsrfGuard ssrfGuard) {
        // Guarded transport: the base URL is operator configuration, but an SSRF
        // check at connect time closes the DNS-rebinding window and makes the
        // allowed-private-hosts requirement (a private Ollama must be listed)
        // enforceable at the socket, not just documented.
        ClientHttpRequestFactory factory =
                SafeHttpClients.requestFactory(
                        ssrfGuard,
                        Duration.ofSeconds(5),
                        properties.getTimeout() == null
                                ? Duration.ofSeconds(30)
                                : properties.getTimeout(),
                        false);
        return RestClient.builder().requestFactory(factory);
    }

    /** Fail-fast startup validation; messages name the exact property to fix. */
    static void validate(EmbeddingProperties p) {
        List<String> problems = new ArrayList<>();
        if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()) {
            problems.add(
                    "app.embeddings.base-url (APP_EMBEDDINGS_BASE_URL) is required, e.g. http://localhost:11434/v1");
        }
        if (p.getModel() == null || p.getModel().isBlank()) {
            problems.add("app.embeddings.model (APP_EMBEDDINGS_MODEL) is required");
        }
        if (p.getDimensions() < 1
                || p.getDimensions() > EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS) {
            problems.add(
                    "app.embeddings.dimensions (APP_EMBEDDINGS_DIMENSIONS) is "
                            + p.getDimensions()
                            + " but must be between 1 and "
                            + EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS
                            + ": agent_memory/tenant_memory/llm_response_cache store vector("
                            + EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS
                            + ") and knowledge_chunks vector("
                            + com.agenticai.core.knowledge.EmbeddingModelCatalog.STORAGE_DIMENSIONS
                            + "). Pick a model with <= "
                            + EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS
                            + " native dims (e.g. all-minilm, bge-small), or one that supports server-side truncation "
                            + "(e.g. text-embedding-3-small with dimensions=384 and send-dimensions=true)");
        }
        if (p.getBatchSize() < 1) {
            problems.add("app.embeddings.batch-size must be >= 1");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "app.embeddings.provider=openai-compatible is misconfigured: "
                            + String.join("; ", problems));
        }
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String modelId() {
        return properties.getModel();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        int batch = properties.getBatchSize();
        for (int from = 0; from < texts.size(); from += batch) {
            out.addAll(embedBatch(texts.subList(from, Math.min(texts.size(), from + batch))));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("input", texts);
        if (properties.isSendDimensions()) {
            body.put("dimensions", properties.getDimensions());
        }
        JsonNode response;
        try {
            response =
                    restClient
                            .post()
                            .uri("/embeddings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new SidecarCommunicationException(
                    "Embeddings provider returned HTTP "
                            + e.getStatusCode().value()
                            + ": "
                            + e.getResponseBodyAsString(),
                    e);
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(
                    "Failed to reach embeddings provider at "
                            + properties.getBaseUrl()
                            + ": "
                            + e.getMessage(),
                    e);
        }
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw new SidecarCommunicationException(
                    "Embeddings provider returned "
                            + (data == null || !data.isArray()
                                    ? "no data array"
                                    : data.size() + " embeddings")
                            + " for "
                            + texts.size()
                            + " inputs",
                    null);
        }
        float[][] ordered = new float[texts.size()][];
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            int index = item.has("index") ? item.get("index").asInt() : i;
            JsonNode embedding = item.get("embedding");
            if (index < 0 || index >= ordered.length || embedding == null || !embedding.isArray()) {
                throw new SidecarCommunicationException(
                        "Embeddings provider returned a malformed item at position " + i, null);
            }
            if (embedding.size() != properties.getDimensions()) {
                throw new SidecarCommunicationException(
                        "Embeddings provider returned a "
                                + embedding.size()
                                + "-dim vector but app.embeddings.dimensions="
                                + properties.getDimensions()
                                + " - fix the property to match model '"
                                + properties.getModel()
                                + "'",
                        null);
            }
            float[] vector = new float[embedding.size()];
            for (int d = 0; d < vector.length; d++) {
                vector[d] = (float) embedding.get(d).asDouble();
            }
            ordered[index] = normalize(vector);
        }
        List<float[]> result = new ArrayList<>(ordered.length);
        for (float[] v : ordered) {
            if (v == null) {
                throw new SidecarCommunicationException(
                        "Embeddings provider response is missing an index", null);
            }
            result.add(v);
        }
        return result;
    }

    /** L2-normalize, matching the sidecar's normalize_embeddings=True. */
    static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0) {
            return v;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
