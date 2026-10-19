package com.agenticai.core.knowledge.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.embeddings.*} - opt-in replacement of the Python sidecar for
 * text embeddings. Unset / {@code provider=sidecar} = unchanged behaviour.
 *
 * <p>Column widths the external model must fit (read from the tenant
 * migrations, never changed by this feature):
 * <ul>
 *   <li>{@code knowledge_chunks.embedding} / re-embed shadow table:
 *       {@code vector(768)} ({@link com.agenticai.core.knowledge.EmbeddingModelCatalog#STORAGE_DIMENSIONS});
 *       vectors for a named collection model are zero-padded up to 768.</li>
 *   <li>{@code agent_memory}, {@code tenant_memory}, {@code llm_response_cache}:
 *       {@code vector(384)} - these callers pass no model key and the sidecar
 *       returns the default model's native 384-d vector unpadded. An external
 *       model's vectors are zero-padded to 384 for them.</li>
 * </ul>
 * Hence {@link #dimensions} must be between 1 and {@value #UNPADDED_COLUMN_DIMENSIONS};
 * startup fails fast otherwise. Zero-padding preserves cosine similarity.
 */
@ConfigurationProperties(prefix = "app.embeddings")
public class EmbeddingProperties {

    /** Width of the memory/cache vector columns (V18, V77, V116). */
    public static final int UNPADDED_COLUMN_DIMENSIONS = 384;

    /**
     * {@code sidecar} (default, unchanged), {@code openai-compatible}, {@code ollama}, or {@code auto}
     * (see {@link EmbeddingProviderSelection}: a cloud key wins, else a configured Ollama, else the
     * sidecar). Env: {@code APP_EMBEDDINGS_PROVIDER}.
     */
    private String provider = "sidecar";

    /** Base URL of an OpenAI-compatible API, e.g. {@code http://localhost:11434/v1}; {@code /embeddings} is appended. */
    private String baseUrl = "";

    /** Bearer token; blank sends no Authorization header (e.g. local Ollama). */
    private String apiKey = "";

    /** Model id sent in the request body, e.g. {@code all-minilm} or {@code text-embedding-3-small}. */
    private String model = "";

    /** Native output width of {@link #model}; every response vector is checked against it. */
    private int dimensions = UNPADDED_COLUMN_DIMENSIONS;

    /**
     * Also send {@code "dimensions": N} in the request (OpenAI text-embedding-3-*
     * support server-side truncation). Off by default because many compatible
     * servers reject unknown fields.
     */
    private boolean sendDimensions = false;

    /** Max inputs per request. */
    private int batchSize = 64;

    /** Per-request read timeout. */
    private Duration timeout = Duration.ofSeconds(30);

    /** {@code app.embeddings.ollama.*} - the free, local path (used by {@code ollama} and {@code auto}). */
    private final Ollama ollama = new Ollama();

    public Ollama getOllama() {
        return ollama;
    }

    /**
     * Ollama embeddings via its native {@code POST /api/embed}. Models are looked up in
     * {@link OllamaEmbeddingModels} for their width, Matryoshka support and query/document prefixes;
     * any property here overrides the table, and an unknown model works too (its width is learned
     * from the first response and then enforced).
     */
    public static class Ollama {

        /** Ollama root URL (no {@code /v1}), e.g. {@code http://localhost:11434}. Blank = Ollama not configured. */
        private String baseUrl = "";

        /** Embedding model tag, e.g. {@code nomic-embed-text}, {@code bge-m3}, {@code qwen3-embedding}. */
        private String model = "nomic-embed-text";

        /** Native output width; 0 = take it from the model table, else learn it from the first response. */
        private int dimensions = 0;

        /**
         * Allow a vector wider than its column to be cut to a prefix and re-normalised. {@code null}
         * = the model table decides (true only for Matryoshka-trained models).
         */
        private Boolean allowTruncation;

        /** Prefix put before each search query; {@code null} = the model's documented default, "" = none. */
        private String queryPrefix;

        /** Prefix put before each stored passage; {@code null} = the model's documented default, "" = none. */
        private String documentPrefix;

        /** How long Ollama keeps the model loaded after a call (Ollama duration string). */
        private String keepAlive = "10m";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public Boolean getAllowTruncation() {
            return allowTruncation;
        }

        public void setAllowTruncation(Boolean allowTruncation) {
            this.allowTruncation = allowTruncation;
        }

        public String getQueryPrefix() {
            return queryPrefix;
        }

        public void setQueryPrefix(String queryPrefix) {
            this.queryPrefix = queryPrefix;
        }

        public String getDocumentPrefix() {
            return documentPrefix;
        }

        public void setDocumentPrefix(String documentPrefix) {
            this.documentPrefix = documentPrefix;
        }

        public String getKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(String keepAlive) {
            this.keepAlive = keepAlive;
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public boolean isSendDimensions() {
        return sendDimensions;
    }

    public void setSendDimensions(boolean sendDimensions) {
        this.sendDimensions = sendDimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
