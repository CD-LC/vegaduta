package com.agenticai.core.knowledge;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps a knowledge collection's vectors from being mixed across embedding sources. Each collection
 * records the source that built its vectors (V550 {@code embedding_fingerprint}: {@code sidecar} or
 * {@code provider:model:width}). Ingestion refuses to add vectors from a different source
 * ({@link EmbeddingModelMismatchException}, 409); vector search skips such a collection (its keyword
 * side still answers) instead of comparing a query vector against vectors from another model; a
 * re-embed rebinds it ({@link KnowledgeReembedService}).
 *
 * <p>An unbound collection (null fingerprint: every pre-V550 collection, and a new one before its
 * first ingest) is never blocked - it is bound to the live source on first successful ingest, so
 * existing installs behave exactly as before. {@code app.knowledge.embedding-binding.enabled=false}
 * turns the whole guard off.
 */
@Component
@Profile("knowledge")
public class KnowledgeEmbeddingBinding {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingBinding.class);

    /** Bound on the warn-once memory so a long-running node cannot grow it without limit. */
    private static final int MAX_WARNED = 1_000;

    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public KnowledgeEmbeddingBinding(
        SidecarClient sidecarClient,
        JdbcTemplate jdbcTemplate,
        @Value("${app.knowledge.embedding-binding.enabled:true}") boolean enabled
    ) {
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    /** The live embedding source, or null when it cannot be determined (guard then stays out of the way). */
    private String liveSource() {
        try {
            String live = sidecarClient.embeddingFingerprint();
            return live == null || live.isBlank() ? null : live;
        } catch (RuntimeException e) {
            // e.g. an Ollama of unknown width that is not reachable: the embed call itself reports it.
            log.debug("Embedding source not determinable yet: {}", e.getMessage());
            return null;
        }
    }

    /** Throws {@link EmbeddingModelMismatchException} when {@code collection} is bound to another source. */
    public void requireWritable(KnowledgeCollection collection) {
        if (!enabled || collection == null) {
            return;
        }
        String live = liveSource();
        String bound = collection.getEmbeddingFingerprint();
        if (live != null && bound != null && !bound.isBlank() && !bound.equals(live)) {
            throw new EmbeddingModelMismatchException(collection, live);
        }
    }

    /** Records the live source on an unbound collection (no-op when already bound, so it can never overwrite). */
    public void bindIfUnbound(UUID collectionId) {
        if (!enabled) {
            return;
        }
        String live = liveSource();
        if (live == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                "UPDATE " + KnowledgeSchemaSupport.qualifiedTableName("knowledge_collection")
                    + " SET embedding_fingerprint = ? WHERE id = ? AND embedding_fingerprint IS NULL",
                live, collectionId
            );
        } catch (DataAccessException e) {
            // Schema pending V550: stay unbound, behave as before.
            log.debug("Could not record embedding source for collection {}: {}", collectionId, e.getMessage());
        }
    }

    /** True when vector search over {@code collection} compares like with like. */
    public boolean isSearchable(KnowledgeCollection collection) {
        return !enabled || isSearchable(collection, liveSource());
    }

    private boolean isSearchable(KnowledgeCollection collection, String live) {
        String bound = collection.getEmbeddingFingerprint();
        if (live == null || bound == null || bound.isBlank() || bound.equals(live)) {
            return true;
        }
        if (warned.size() < MAX_WARNED && warned.add(collection.getId() + "|" + bound + "|" + live)) {
            log.warn("Vector search skips knowledge collection {} ({}): it was embedded with [{}] but the embeddings "
                + "source is now [{}]. Keyword search still works; re-embed the collection to restore vector search.",
                collection.getId(), collection.getName(), bound, live);
        }
        return false;
    }

    /** {@code collections} without the ones {@link #isSearchable} rejects; the same list instance when none is rejected. */
    public List<KnowledgeCollection> searchable(List<KnowledgeCollection> collections) {
        if (!enabled || collections.isEmpty()) {
            return collections;
        }
        // Resolved once per search: an Ollama of not-yet-learned width probes over HTTP, and that must not
        // repeat per collection (or add a probe to every search when Ollama is down).
        String live = liveSource();
        List<KnowledgeCollection> kept = collections.stream().filter(c -> isSearchable(c, live)).toList();
        return kept.size() == collections.size() ? collections : kept;
    }
}
