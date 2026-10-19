package com.agenticai.core.knowledge;

import static com.agenticai.core.knowledge.SidecarHttpSupport.describe;
import static com.agenticai.core.knowledge.SidecarHttpSupport.withConnectRetry;

import com.agenticai.core.knowledge.dto.SidecarChunk;
import com.agenticai.core.knowledge.dto.SidecarEmbedRequest;
import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import com.agenticai.core.knowledge.embedding.EmbeddingClient;
import com.agenticai.core.knowledge.embedding.EmbeddingProperties;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.DependencyUnavailableException;
import com.agenticai.core.resilience.ResilienceGuard;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The embeddings path behind the {@link SidecarClient} facade: the sidecar {@code /embed} call
 * behind its circuit breaker, the pre-chunked loop, and the opt-in external {@link
 * EmbeddingClient}. The {@link RestClient} is passed per call (the facade owns the per-operation
 * clients and their read timeouts), so this class holds no transport of its own.
 */
final class SidecarEmbeddingClient {

    /**
     * Opt-in embeddings backend ({@code app.embeddings.provider}); null (the default) keeps every
     * embed call on the sidecar's {@code /embed} exactly as before. See {@link EmbeddingClient}.
     */
    private final EmbeddingClient embeddingOverride;

    private final Consumer<HttpHeaders> applyInternalToken;

    SidecarEmbeddingClient(EmbeddingClient embeddingOverride, Consumer<HttpHeaders> applyInternalToken) {
        this.embeddingOverride = embeddingOverride;
        this.applyInternalToken = applyInternalToken;
    }

    /**
     * Decided per call, for {@code app.embeddings.provider=auto} when no Ollama is configured in the
     * environment: an Ollama registered at run time (the console's local-model wizard writes it to the
     * database, which a start-up condition cannot see). Null result = use the sidecar. Never consulted
     * when an {@link #embeddingOverride} is set.
     */
    private volatile java.util.function.Supplier<EmbeddingClient> dynamicEmbedder;

    void setDynamicEmbedder(java.util.function.Supplier<EmbeddingClient> dynamicEmbedder) {
        this.dynamicEmbedder = dynamicEmbedder;
    }

    /** The external embedder for this operation, or null for the sidecar. Read once per operation. */
    private EmbeddingClient active() {
        if (embeddingOverride != null) {
            return embeddingOverride;
        }
        java.util.function.Supplier<EmbeddingClient> dynamic = dynamicEmbedder;
        return dynamic == null ? null : dynamic.get();
    }

    /** {@code sidecar} (default) or the opted-in {@link EmbeddingClient#provider()}. */
    String provider() {
        EmbeddingClient external = active();
        return external == null ? "sidecar" : external.provider();
    }

    /**
     * What produced (and will produce) the vectors: {@code sidecar} (its model is the collection's
     * catalog key), or {@code provider:model:nativeWidth} for an external embedder. Stored on each
     * knowledge collection so a provider/model/width change is refused instead of silently mixing
     * vectors that cannot be compared.
     */
    String fingerprint() {
        EmbeddingClient external = active();
        if (external == null) {
            return "sidecar";
        }
        String model = external.modelId();
        return external.provider()
                + ":"
                + (model == null || model.isBlank() ? "unknown" : model.trim())
                + ":"
                + external.dimensions();
    }

    /** False when the deployment runs without the Python sidecar ({@code app.sidecar.enabled=false}). */
    private volatile boolean sidecarEnabled = true;

    void setSidecarEnabled(boolean enabled) {
        this.sidecarEnabled = enabled;
    }

    private void requireSidecar() {
        if (!sidecarEnabled) {
            throw new FeatureUnavailableException(
                    "Embeddings are unavailable: the Python sidecar is disabled (app.sidecar.enabled=false) and no"
                            + " embeddings provider is configured. Pick a local model in the console (an Ollama this install"
                            + " registered is used automatically), or set app.embeddings.provider=ollama (free, local;"
                            + " run 'ollama pull nomic-embed-text') or auto.");
        }
    }

    boolean hasExternalEmbedder() {
        return active() != null;
    }

    /**
     * One query vector straight from the external embedder as {@code float[]}, padded (or, for a
     * Matryoshka model, truncated) to the column width. Skips the boxed {@code List<Double>} the
     * sidecar-shaped response needs; only valid when an external embedder is configured.
     */
    float[] embedQueryVector(String text, String embeddingModel, int queryChunkSize) {
        EmbeddingClient external = active();
        if (external == null) {
            throw new SidecarCommunicationException("no external embedder is configured", null);
        }
        int target = targetWidth(embeddingModel);
        // Same shape as the sidecar-shaped path: the query is the first chunk of its text, and a text
        // with nothing in it is an error rather than an embedding of whitespace.
        List<String> pieces = Chunker.chunk(text, new Chunker.Settings(queryChunkSize, 0));
        if (pieces.isEmpty()) {
            throw new SidecarCommunicationException("text produced no chunks after stripping whitespace", null);
        }
        List<float[]> vectors = external.embedQuery(List.of(pieces.get(0)));
        if (vectors.size() != 1) {
            throw new SidecarCommunicationException(
                    "Embeddings provider returned " + vectors.size() + " vectors for 1 query", null);
        }
        return fit(external, vectors.get(0), target);
    }

    /** See {@link SidecarClient#embedPreChunked}. */
    SidecarEmbedResponse embedPreChunked(RestClient ingestRestClient, List<String> chunks, String embeddingModel) {
        EmbeddingClient external = active();
        if (external != null) {
            return embedExternally(external, chunks, embeddingModel, Chunker.MAX_CHUNK_SIZE, 0);
        }
        requireSidecar();
        List<SidecarChunk> embedded = new java.util.ArrayList<>(chunks.size());
        String resolvedModel = embeddingModel;
        int dimensions = EmbeddingModelCatalog.STORAGE_DIMENSIONS;
        for (int i = 0; i < chunks.size(); i++) {
            SidecarEmbedResponse response =
                    embed(
                            ingestRestClient,
                            chunks.get(i),
                            Chunker.MAX_CHUNK_SIZE,
                            0,
                            embeddingModel);
            if (response.chunks() == null || response.chunks().size() != 1) {
                throw new SidecarCommunicationException(
                        "Sidecar /embed returned "
                                + (response.chunks() == null ? 0 : response.chunks().size())
                                + " chunks for pre-chunked input "
                                + i
                                + " (expected exactly 1)",
                        null);
            }
            resolvedModel = response.model();
            dimensions = response.dimensions();
            embedded.add(new SidecarChunk(i, chunks.get(i), response.chunks().get(0).embedding()));
        }
        return new SidecarEmbedResponse(
                resolvedModel, dimensions, Chunker.MAX_CHUNK_SIZE, 0, embedded);
    }

    /**
     * Optional circuit breaker for the embeddings path ({@code
     * app.resilience.circuit-breaker.enabled}, default true; false gives {@link
     * ResilienceGuard#NOOP}, so nothing changes). Setter injection keeps the constructor - and
     * every test that builds SidecarClient by hand - untouched.
     */
    private volatile CircuitBreakerRegistry breakerRegistry;

    void setCircuitBreakerRegistry(CircuitBreakerRegistry registry) {
        this.breakerRegistry = registry;
    }

    private ResilienceGuard embeddingsGuard() {
        CircuitBreakerRegistry registry = breakerRegistry;
        return registry == null ? ResilienceGuard.NOOP : registry.guard("sidecar");
    }

    /**
     * {@link #embedUnguarded} behind the sidecar circuit breaker. An OPEN circuit answers with the
     * same {@link SidecarCommunicationException} a dead sidecar already produces (callers and
     * {@code GlobalExceptionHandler} already map it), just without waiting for a timeout. A 4xx
     * means the sidecar answered, so it counts as healthy; connect failures, timeouts, 5xx and
     * empty replies count as failures.
     */
    SidecarEmbedResponse embed(
            RestClient restClient,
            String text,
            int chunkSize,
            int chunkOverlap,
            String embeddingModel) {
        // An operator-configured external embedder bypasses the sidecar entirely, so it is not
        // counted against (or shed by) the sidecar's circuit breaker.
        EmbeddingClient external = active();
        if (external != null) {
            List<String> pieces;
            try {
                pieces = Chunker.chunk(text, new Chunker.Settings(chunkSize, chunkOverlap));
            } catch (IllegalArgumentException e) {
                throw new SidecarCommunicationException(
                        "Invalid chunking parameters: " + e.getMessage(), e);
            }
            if (pieces.isEmpty()) {
                throw new SidecarCommunicationException(
                        "text produced no chunks after stripping whitespace", null);
            }
            return embedExternally(external, pieces, embeddingModel, chunkSize, chunkOverlap);
        }
        requireSidecar();
        ResilienceGuard.Call call;
        try {
            call = embeddingsGuard().begin();
        } catch (DependencyUnavailableException shed) {
            throw new SidecarCommunicationException(
                    "Sidecar /embed is temporarily unavailable (circuit open after repeated"
                            + " failures) - try again shortly",
                    shed);
        }
        try {
            SidecarEmbedResponse response =
                    embedUnguarded(restClient, text, chunkSize, chunkOverlap, embeddingModel);
            call.success();
            return response;
        } catch (SidecarCommunicationException e) {
            if (e.getCause() instanceof RestClientResponseException responseError
                    && responseError.getStatusCode().is4xxClientError()) {
                call.success();
            } else {
                call.failure(e);
            }
            throw e;
        } finally {
            call.close();
        }
    }

    private SidecarEmbedResponse embedUnguarded(
            RestClient restClient,
            String text,
            int chunkSize,
            int chunkOverlap,
            String embeddingModel) {
        try {
            // contentType(APPLICATION_JSON) is required, not decorative: without
            // it, RestClient picks a request-body HttpMessageConverter by
            // whatever's registered and willing to write a plain POJO, and on
            // this classpath that resolved to an XML converter instead of
            // Jackson's JSON one - the sidecar received a literal
            // "<SidecarEmbedRequest><text>..." body and FastAPI's Pydantic
            // model correctly rejected it (422, "Input should be a valid
            // dictionary or object"). Reproduced testing real .md upload
            // end-to-end; KeycloakAdminClient's POSTs already set this
            // explicitly for the same reason, this call just missed it.
            SidecarEmbedResponse response =
                    withConnectRetry(
                            "/embed",
                            () ->
                                    restClient
                                            .post()
                                            .uri("/embed")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .headers(applyInternalToken)
                                            .body(
                                                    new SidecarEmbedRequest(
                                                            text,
                                                            chunkSize,
                                                            chunkOverlap,
                                                            embeddingModel))
                                            .retrieve()
                                            .body(SidecarEmbedResponse.class));
            if (response == null) {
                throw new SidecarCommunicationException(
                        "Sidecar /embed returned an empty response", null);
            }
            return response;
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(describe("/embed", e), e);
        }
    }

    /**
     * Opt-in path ({@code app.embeddings.provider}): embeds already-chunked texts with the external
     * {@link EmbeddingClient} and shapes the result exactly like the sidecar's {@code /embed}
     * response, including its padding rule - zero-pad to {@link
     * EmbeddingModelCatalog#STORAGE_DIMENSIONS} when a collection model key is given
     * (knowledge_chunks vector(768)), otherwise to {@link
     * EmbeddingProperties#UNPADDED_COLUMN_DIMENSIONS} (memory/cache tables, vector(384)). The
     * echoed model key is the requested catalog key (or the default key) so collection/index
     * bookkeeping is unchanged; switching providers on a populated database therefore requires
     * re-embedding every collection and clearing memory and response-cache rows, since vectors from
     * different models are not comparable.
     */
    private SidecarEmbedResponse embedExternally(
            EmbeddingClient external,
            List<String> texts,
            String embeddingModel,
            int chunkSize,
            int chunkOverlap) {
        int target = targetWidth(embeddingModel);
        List<float[]> vectors = external.embed(texts);
        if (vectors.size() != texts.size()) {
            throw new SidecarCommunicationException(
                    "Embeddings provider returned "
                            + vectors.size()
                            + " vectors for "
                            + texts.size()
                            + " inputs",
                    null);
        }
        List<SidecarChunk> chunks = new java.util.ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            float[] fitted = fit(external, vectors.get(i), target);
            List<Double> padded = new java.util.ArrayList<>(fitted.length);
            for (float v : fitted) {
                padded.add((double) v);
            }
            chunks.add(new SidecarChunk(i, texts.get(i), padded));
        }
        String model = embeddingModel != null ? embeddingModel : EmbeddingModelCatalog.DEFAULT_KEY;
        return new SidecarEmbedResponse(
                model, external.dimensions(), chunkSize, chunkOverlap, chunks);
    }

    /** Knowledge columns are vector(768) (a collection model key is given), memory/cache vector(384). */
    private static int targetWidth(String embeddingModel) {
        return embeddingModel != null
                ? EmbeddingModelCatalog.STORAGE_DIMENSIONS
                : EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS;
    }

    /**
     * Makes a native vector exactly {@code target} wide without changing what it means: shorter is
     * zero-padded (cosine-preserving); longer is cut to a prefix and re-normalised only when the
     * model is Matryoshka-trained (or the operator allowed it), otherwise it is refused, because a
     * silently truncated vector of an ordinary model would corrupt retrieval.
     */
    private float[] fit(EmbeddingClient external, float[] nativeVector, int target) {
        if (nativeVector.length == target) {
            return nativeVector;
        }
        if (nativeVector.length < target) {
            return java.util.Arrays.copyOf(nativeVector, target);
        }
        if (!external.supportsTruncation()) {
            throw new SidecarCommunicationException(
                    "Embedding width "
                            + nativeVector.length
                            + " exceeds the target column width "
                            + target
                            + " and model '"
                            + external.modelId()
                            + "' is not configured for truncation. Use a model of <= "
                            + target
                            + " dims, or one trained for truncation (Matryoshka)",
                    null);
        }
        float[] cut = java.util.Arrays.copyOf(nativeVector, target);
        double sum = 0;
        for (float x : cut) {
            sum += (double) x * x;
        }
        if (sum > 0) {
            double norm = Math.sqrt(sum);
            for (int i = 0; i < cut.length; i++) {
                cut[i] = (float) (cut[i] / norm);
            }
        }
        return cut;
    }
}
