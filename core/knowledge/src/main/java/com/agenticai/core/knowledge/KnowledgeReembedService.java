package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.KnowledgeReembedJobResponse;
import com.agenticai.core.knowledge.dto.SidecarChunk;
import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Switches a knowledge collection to a different embedding model without a
 * window where its search is broken or mixes two models' vectors.
 *
 * <p>Before this, a collection's model was fixed at creation (no setter, no
 * job) - changing it meant deleting and re-uploading everything. The job
 * (state machine in {@link KnowledgeReembedJobStatus}):
 *
 * <ol>
 *   <li><b>{@link #start}</b> - validates the target against
 *       {@link EmbeddingModelCatalog}, creates a QUEUED job (at most one active
 *       per collection - V362's partial unique index backs the Java check),
 *       and marks the collection {@code REEMBEDDING}.</li>
 *   <li><b>EMBEDDING</b> - in batches, re-embeds each chunk's stored text with
 *       the target model through the same sidecar path ingestion uses
 *       ({@link SidecarClient#embedPreChunked}) and writes the vector into the
 *       shadow table (V363) keyed by (chunk, target model). Live search is
 *       untouched: it still reads {@code knowledge_chunks} with the old model.
 *       A batch selects only chunks with no shadow row yet, so a restart or a
 *       retry after FAILED resumes where it stopped (idempotent upserts).
 *       Progress = total - remaining, recomputed from the table each batch.</li>
 *   <li><b>Flip</b> ({@link #flip}) - ONE transaction: lock the collection
 *       row, copy every shadow vector onto its chunk (vector + model), set the
 *       collection's {@code embedding_model} and status {@code READY}, delete
 *       the collection's shadow rows. Old vectors are overwritten in place, so
 *       nothing stale remains; a reader sees either all-old or all-new.</li>
 *   <li><b>SWEEPING</b> - ingestion is not blocked while a job runs, and an
 *       ingest that read the collection's model just before the flip writes
 *       old-model chunks just after it. The sweep re-embeds, in place, any
 *       chunk in the collection whose model still isn't the target.</li>
 * </ol>
 *
 * <p>Like {@link KnowledgeIngestionJobService#processJob}, {@link #processJob}
 * is deliberately NOT one transaction: it spans many sidecar calls. Only the
 * flip needs atomicity, and it gets its own short transaction.
 *
 * <p>Known residual gap (documented, not hidden): an ingest that reads the
 * old model, then spends longer in its sidecar call than the whole sweep
 * takes, can land an old-model chunk after the sweep finished. That chunk is
 * invisible to vector search (the model filter excludes it) but still found
 * by keyword search; re-running {@code POST .../reembed} with the same target
 * repairs it (a start with stray chunks is allowed even when the collection
 * is already on the target).
 */
@Service
@Profile("knowledge")
public class KnowledgeReembedService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReembedService.class);

    private final KnowledgeReembedJobRepository jobRepository;
    private final KnowledgeCollectionRepository collectionRepository;
    private final SidecarClient sidecarClient;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public KnowledgeReembedService(
        KnowledgeReembedJobRepository jobRepository,
        KnowledgeCollectionRepository collectionRepository,
        SidecarClient sidecarClient,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        @Value("${app.knowledge.reembed.batch-size:64}") int batchSize
    ) {
        this.jobRepository = jobRepository;
        this.collectionRepository = collectionRepository;
        this.sidecarClient = sidecarClient;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
    }

    /** A chunk as the job reads it - id plus the stored text to re-embed. */
    record ChunkText(UUID id, String content) {}

    // --- API ----------------------------------------------------------------------------------

    @Transactional
    public KnowledgeReembedJobResponse start(UUID collectionId, String targetModel) {
        String target = targetModel == null ? null : targetModel.trim();
        if (target == null || !EmbeddingModelCatalog.isValid(target)) {
            throw new IllegalArgumentException(
                "Unknown embedding model \"" + targetModel + "\" - see GET /api/knowledge/collections/embedding-models"
            );
        }
        KnowledgeCollection collection = collectionRepository.findById(collectionId)
            .orElseThrow(() -> new KnowledgeCollectionNotFoundException(collectionId));

        Optional<KnowledgeReembedJob> active =
            jobRepository.findFirstByCollectionIdAndStatusIn(collectionId, KnowledgeReembedJobStatus.ACTIVE);
        if (active.isPresent()) {
            if (active.get().getTargetModel().equals(target)) {
                return KnowledgeReembedJobResponse.from(active.get()); // idempotent re-submit
            }
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A re-embed to \"" + active.get().getTargetModel() + "\" is already running for this collection (job "
                    + active.get().getId() + ") - wait for it to finish"
            );
        }

        String chunks = KnowledgeSchemaSupport.qualifiedTableName();
        long strays = count("SELECT COUNT(*) FROM " + chunks + " WHERE collection_id = ? AND embedding_model <> ?", collectionId, target);
        boolean sourceChanged = embeddingSourceChanged(collection);
        if (target.equals(collection.getEmbeddingModel()) && strays == 0 && !sourceChanged) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "This collection already uses \"" + target + "\" for every chunk - nothing to re-embed"
            );
        }
        long total = count("SELECT COUNT(*) FROM " + chunks + " WHERE collection_id = ?", collectionId);

        KnowledgeReembedJob job;
        try {
            job = jobRepository.saveAndFlush(new KnowledgeReembedJob(collectionId, collection.getEmbeddingModel(), target, total));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent start - V362's uq_knowledge_reembed_job_active.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A re-embed is already running for this collection");
        }
        if (sourceChanged) {
            // Shadow rows left by an abandoned job hold vectors from the OLD embedding source; reusing them
            // as "already done" would put exactly the mixed vectors this job exists to remove.
            jdbcTemplate.update(
                "DELETE FROM " + KnowledgeSchemaSupport.qualifiedTableName("knowledge_chunk_reembed_shadow")
                    + " WHERE collection_id = ?", collectionId);
        }
        setCollectionStatus(collectionId, KnowledgeCollection.STATUS_REEMBEDDING);
        log.info("Queued re-embed job {} for collection {}: {} -> {} ({} chunks{})",
            job.getId(), collectionId, collection.getEmbeddingModel(), target, total,
            sourceChanged ? ", embedding source changed from [" + collection.getEmbeddingFingerprint() + "]" : "");
        return KnowledgeReembedJobResponse.from(job);
    }

    /**
     * True when the collection's vectors were built by a different embedding source than the live one
     * (V550 fingerprint), i.e. every chunk needs a fresh vector even if the catalog model key is unchanged.
     */
    private boolean embeddingSourceChanged(KnowledgeCollection collection) {
        String bound = collection.getEmbeddingFingerprint();
        if (bound == null || bound.isBlank()) {
            return false;
        }
        String live = sidecarClient.embeddingFingerprint();
        return live != null && !live.isBlank() && !bound.equals(live);
    }

    /**
     * A "refresh" job re-embeds with a new embedding SOURCE (provider/model/width changed, with or without
     * a catalog key change): every chunk is pending, not just the ones whose key differs, and the flip
     * rewrites chunks whose key already matches. Decided from the collection's still-old fingerprint, so
     * a job resumed before its flip decides the same way; after the flip the collection is rebound, the
     * answer becomes false, and the (then empty) key-based predicates take over.
     */
    private boolean isRefresh(KnowledgeReembedJob job) {
        // Not limited to an unchanged key: a job that changes the key AND the source must also rebuild
        // chunks that already carry the target key (left by an earlier abandoned job to that key), or
        // the rebind below would label old-source vectors as the new source's.
        return collectionRepository.findById(job.getCollectionId())
            .map(this::embeddingSourceChanged)
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public KnowledgeReembedJobResponse get(UUID jobId) {
        return KnowledgeReembedJobResponse.from(getEntity(jobId));
    }

    @Transactional(readOnly = true)
    public Optional<KnowledgeReembedJobResponse> latestForCollection(UUID collectionId) {
        return jobRepository.findFirstByCollectionIdOrderByCreatedAtDesc(collectionId).map(KnowledgeReembedJobResponse::from);
    }

    // --- worker -------------------------------------------------------------------------------

    /**
     * Runs (or resumes) one job to a terminal state. Called by
     * {@link KnowledgeReembedJobRunner} with {@code TenantContext} already
     * bound to the job's tenant.
     */
    public void processJob(UUID jobId) {
        KnowledgeReembedJob job = getEntity(jobId);
        if (job.getStatus().isTerminal()) {
            return;
        }
        boolean flipped = job.getStatus() == KnowledgeReembedJobStatus.SWEEPING;
        try {
            if (!flipped) {
                job.markEmbedding();
                job = jobRepository.save(job);
                boolean refresh = isRefresh(job);
                job = embedIntoShadow(job, refresh);
                flip(job.getCollectionId(), job.getTargetModel(), refresh);
                flipped = true;
                job.markSweeping();
                job = jobRepository.save(job);
            }
            job = sweep(job);
            job.markCompleted();
            jobRepository.save(job);
            log.info("Re-embed job {} completed: collection {} now on {}", job.getId(), job.getCollectionId(), job.getTargetModel());
        } catch (RuntimeException e) {
            log.warn("Re-embed job {} failed ({}): {}", jobId, flipped ? "after flip" : "before flip", e.getMessage());
            if (!flipped) {
                // Old model's vectors are still the live, complete set - just
                // leave REEMBEDDING. Shadow rows are kept for a retry to reuse.
                setCollectionStatus(job.getCollectionId(), KnowledgeCollection.STATUS_READY);
            }
            job.markFailed(e.getMessage());
            jobRepository.save(job);
        }
    }

    /** EMBEDDING phase: batches until no chunk lacks a target-model vector. */
    KnowledgeReembedJob embedIntoShadow(KnowledgeReembedJob job, boolean refresh) {
        String chunks = KnowledgeSchemaSupport.qualifiedTableName();
        String shadow = KnowledgeSchemaSupport.qualifiedTableName("knowledge_chunk_reembed_shadow");
        String target = job.getTargetModel();
        UUID collectionId = job.getCollectionId();
        // Refresh: the catalog key already matches on every chunk, so "pending" means "no shadow row yet".
        String pendingWhere = " FROM " + chunks + " c WHERE c.collection_id = ?" + (refresh ? "" : " AND c.embedding_model <> ?")
            + " AND NOT EXISTS (SELECT 1 FROM " + shadow + " s WHERE s.chunk_id = c.id AND s.embedding_model = ?)";
        Object[] pendingArgs = refresh ? new Object[] {collectionId, target} : new Object[] {collectionId, target, target};
        String insertSql = "INSERT INTO " + shadow + " (chunk_id, embedding_model, collection_id, embedding) "
            + "SELECT ?, ?, ?, ?::public.vector WHERE EXISTS (SELECT 1 FROM " + chunks + " WHERE id = ?) "
            + "ON CONFLICT (chunk_id, embedding_model) DO UPDATE SET embedding = EXCLUDED.embedding, created_at = now()";

        while (true) {
            List<ChunkText> batch = jdbcTemplate.query(
                "SELECT c.id, c.content" + pendingWhere + " ORDER BY c.id LIMIT ?",
                (rs, rowNum) -> new ChunkText(UUID.fromString(rs.getString("id")), rs.getString("content")),
                concat(pendingArgs, batchSize)
            );
            if (batch.isEmpty()) {
                break;
            }
            List<List<Double>> vectors = embed(batch, target);
            List<Object[]> rows = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                UUID chunkId = batch.get(i).id();
                rows.add(new Object[] {
                    chunkId, target, collectionId, KnowledgeIngestionService.toVectorLiteral(vectors.get(i)), chunkId
                });
            }
            jdbcTemplate.batchUpdate(insertSql, rows);

            long total = count("SELECT COUNT(*) FROM " + chunks + " WHERE collection_id = ?", collectionId);
            long remaining = count("SELECT COUNT(*)" + pendingWhere, pendingArgs);
            job.updateProgress(total - remaining, total);
            job = jobRepository.save(job);
        }
        return job;
    }

    /**
     * The atomic switch: after this commits, the collection's live vectors and
     * its {@code embedding_model} are both the target's. Idempotent - re-running
     * it after a crash between commit and the job's own status save copies
     * nothing (no shadow rows left) and re-sets the same values.
     */
    void flip(UUID collectionId, String target) {
        flip(collectionId, target, false);
    }

    /** {@code refresh}: copy every shadow vector, including onto chunks whose model key already equals the target. */
    void flip(UUID collectionId, String target, boolean refresh) {
        String chunks = KnowledgeSchemaSupport.qualifiedTableName();
        String shadow = KnowledgeSchemaSupport.qualifiedTableName("knowledge_chunk_reembed_shadow");
        String collections = KnowledgeSchemaSupport.qualifiedTableName("knowledge_collection");
        transactionTemplate.executeWithoutResult(status -> {
            // Serialises against a concurrent flip/start on the same collection.
            jdbcTemplate.queryForList("SELECT id FROM " + collections + " WHERE id = ? FOR UPDATE", collectionId);
            int copied = refresh
                ? jdbcTemplate.update(
                    "UPDATE " + chunks + " c SET embedding = s.embedding, embedding_model = s.embedding_model FROM " + shadow + " s"
                        + " WHERE s.chunk_id = c.id AND s.embedding_model = ? AND c.collection_id = ?",
                    target, collectionId)
                : jdbcTemplate.update(
                    "UPDATE " + chunks + " c SET embedding = s.embedding, embedding_model = s.embedding_model FROM " + shadow + " s"
                        + " WHERE s.chunk_id = c.id AND s.embedding_model = ? AND c.collection_id = ? AND c.embedding_model <> ?",
                    target, collectionId, target);
            jdbcTemplate.update(
                "UPDATE " + collections + " SET embedding_model = ?, embedding_status = ?, updated_at = now() WHERE id = ?",
                target, KnowledgeCollection.STATUS_READY, collectionId
            );
            // Same transaction: the vectors and the record of which embedding source built them switch together.
            String liveSource = sidecarClient.embeddingFingerprint();
            if (liveSource != null && !liveSource.isBlank()) {
                jdbcTemplate.update(
                    "UPDATE " + collections + " SET embedding_fingerprint = ? WHERE id = ?", liveSource, collectionId);
            }
            // Every model's shadow rows for this collection - the ones just
            // copied, plus any left by an abandoned job to a different model.
            jdbcTemplate.update("DELETE FROM " + shadow + " WHERE collection_id = ?", collectionId);
            log.info("Re-embed flip for collection {}: {} chunk vectors switched to {}", collectionId, copied, target);
        });
    }

    /** SWEEPING phase: in-place re-embed of chunks that arrived with the old model around the flip. */
    KnowledgeReembedJob sweep(KnowledgeReembedJob job) {
        String chunks = KnowledgeSchemaSupport.qualifiedTableName();
        String target = job.getTargetModel();
        UUID collectionId = job.getCollectionId();
        while (true) {
            List<ChunkText> batch = jdbcTemplate.query(
                "SELECT id, content FROM " + chunks + " WHERE collection_id = ? AND embedding_model <> ? ORDER BY id LIMIT ?",
                (rs, rowNum) -> new ChunkText(UUID.fromString(rs.getString("id")), rs.getString("content")),
                collectionId, target, batchSize
            );
            if (batch.isEmpty()) {
                break;
            }
            List<List<Double>> vectors = embed(batch, target);
            List<Object[]> rows = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                rows.add(new Object[] {
                    KnowledgeIngestionService.toVectorLiteral(vectors.get(i)), target, batch.get(i).id(), target
                });
            }
            jdbcTemplate.batchUpdate(
                "UPDATE " + chunks + " SET embedding = ?::public.vector, embedding_model = ? WHERE id = ? AND embedding_model <> ?", rows
            );
        }
        long total = count("SELECT COUNT(*) FROM " + chunks + " WHERE collection_id = ?", collectionId);
        job.updateProgress(total, total);
        return job;
    }

    private List<List<Double>> embed(List<ChunkText> batch, String model) {
        List<String> texts = batch.stream()
            // Stored chunks can't be re-cut (their ids/text are what search
            // returns); cap at the single-embedding limit instead. Chunks the
            // sidecar or Chunker produced never exceed it - this only guards
            // hand-inserted legacy rows.
            .map(c -> Chunker.firstWords(c.content() == null ? "" : c.content(), Chunker.MAX_CHUNK_SIZE))
            .map(t -> t.isBlank() ? "(empty)" : t)
            .toList();
        SidecarEmbedResponse response = sidecarClient.embedPreChunked(texts, model);
        if (response.chunks().size() != batch.size()) {
            throw new SidecarCommunicationException(
                "Sidecar returned " + response.chunks().size() + " embeddings for a re-embed batch of " + batch.size(), null
            );
        }
        return response.chunks().stream().map(SidecarChunk::embedding).toList();
    }

    private void setCollectionStatus(UUID collectionId, String status) {
        jdbcTemplate.update(
            "UPDATE " + KnowledgeSchemaSupport.qualifiedTableName("knowledge_collection")
                + " SET embedding_status = ?, updated_at = now() WHERE id = ?",
            status, collectionId
        );
    }

    private static Object[] concat(Object[] head, Object tail) {
        Object[] all = java.util.Arrays.copyOf(head, head.length + 1);
        all[head.length] = tail;
        return all;
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n != null ? n : 0L;
    }

    private KnowledgeReembedJob getEntity(UUID jobId) {
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Re-embed job " + jobId + " not found"));
    }
}
