package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.KnowledgeCollectionCreateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionResponse;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionUpdateRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge Stage F1 - CRUD for {@link KnowledgeCollection} plus the two
 * cross-cutting lookups other services need: "what's the fallback
 * collection for an upload with none specified" and "does this collection
 * still hold chunks" (needed for the delete guard, since {@code
 * knowledge_chunks} is raw JDBC, not a Hibernate relation this class could
 * otherwise query through {@link KnowledgeCollectionRepository} alone).
 */
@Service
@Profile("knowledge")
public class KnowledgeCollectionService {

    private final KnowledgeCollectionRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeDocumentService documentService;
    private final TenantLimits tenantLimits;

    /**
     * Per-collection access control (V710). Setter-injected so the constructor is unchanged; without it
     * (unit tests, non-Spring callers) nothing is enforced. In the application it always exists.
     */
    private KnowledgeAccessService access;

    @Autowired(required = false)
    void setAccess(KnowledgeAccessService access) {
        this.access = access;
    }

    public KnowledgeCollectionService(
        KnowledgeCollectionRepository repository,
        JdbcTemplate jdbcTemplate,
        KnowledgeDocumentService documentService,
        TenantLimits tenantLimits
    ) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
        this.tenantLimits = tenantLimits;
    }

    @Transactional
    public KnowledgeCollectionResponse create(KnowledgeCollectionCreateRequest request) {
        // Plan gate - knowledge-base collections are capped per plan
        // (control_plane.plan_limits.max_kb_collections, -1 == no cap).
        // Data-driven so a platform-admin's web-admin edit takes effect at
        // runtime. Standing ceiling, checked at creation against the live
        // count, same posture as the agent/workflow caps in AgentService.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null) {
            Optional<Integer> maxKbCollections = tenantLimits.maxKbCollections(tenantId);
            if (maxKbCollections.isPresent() && repository.count() >= maxKbCollections.get()) {
                throw new TenantLimitExceededException(
                    "Your plan allows up to " + maxKbCollections.get()
                        + " knowledge collection(s) - upgrade your plan to add more."
                );
            }
        }
        if (request.parentCollectionId() != null) {
            requireExists(request.parentCollectionId());
        }
        String embeddingModel = request.embeddingModel() == null || request.embeddingModel().isBlank()
            ? EmbeddingModelCatalog.DEFAULT_KEY
            : request.embeddingModel();
        if (!EmbeddingModelCatalog.isValid(embeddingModel)) {
            throw new IllegalArgumentException(
                "Unknown embedding model \"" + embeddingModel + "\" - see GET /api/knowledge/collections/embedding-models"
            );
        }
        // RAG audit fix (2026-07-29) - per-collection full-text-search
        // language, defaulting to the language-agnostic 'simple' (see
        // TextSearchConfigCatalog's javadoc for why that beats the old
        // hardcoded 'english' for this platform's Indic/mixed content).
        // Validated against a fixed catalog because the value is cast to
        // ::regconfig in KnowledgeIngestionService's SQL - fail loudly here
        // rather than at first ingest.
        String textSearchConfig = request.textSearchConfig() == null || request.textSearchConfig().isBlank()
            ? TextSearchConfigCatalog.DEFAULT_KEY
            : request.textSearchConfig();
        if (!TextSearchConfigCatalog.isValid(textSearchConfig)) {
            throw new IllegalArgumentException(
                "Unknown text search config \"" + textSearchConfig + "\" - see GET /api/knowledge/collections/text-search-configs"
            );
        }
        // The creator owns the collection. Without a resolvable user there is no owner, so only the
        // historic tenant-wide visibility is possible (a PRIVATE / SHARED collection nobody owns would
        // be unreachable except by an administrator).
        String owner = access == null ? null : access.currentSubject();
        KnowledgeVisibility visibility = KnowledgeVisibility.orDefault(request.visibility());
        if (owner == null && access != null && visibility != KnowledgeVisibility.TENANT) {
            throw new IllegalArgumentException(
                "A " + visibility + " collection needs an authenticated owner");
        }
        KnowledgeCollection collection = new KnowledgeCollection(
            request.name(), request.description(), request.parentCollectionId(), embeddingModel, textSearchConfig,
            owner, visibility
        );
        if (request.chunkSize() != null || request.chunkOverlap() != null) {
            // Validate the combination up front (size 10..200, 0 <= overlap < size)
            // so a bad value fails here, not at first ingest.
            Chunker.resolve(null, null, request.chunkSize(), request.chunkOverlap());
            collection.updateChunking(request.chunkSize(), request.chunkOverlap());
        }
        // A brand-new collection has no documents yet - 0/0 without a
        // database round trip, rather than calling documentService for
        // counts that can only ever be zero at creation time.
        return KnowledgeCollectionResponse.from(repository.save(collection), 0L, 0L);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeCollectionResponse> listAll() {
        List<KnowledgeCollection> all = repository.findAll();
        if (access != null) {
            // Collections the caller cannot read are not listed at all (name and description would leak).
            all = access.filterReadable(all, access.currentPrincipal(), false);
        }
        return all.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeCollectionResponse get(UUID collectionId) {
        if (access != null) {
            access.requireRead(collectionId);
        }
        return toResponse(getEntity(collectionId));
    }

    @Transactional
    public KnowledgeCollectionResponse update(UUID collectionId, KnowledgeCollectionUpdateRequest request) {
        KnowledgeCollection collection = getEntity(collectionId);
        if (access != null) {
            access.requireModify(collection);
            if (request.visibility() != null && request.visibility() != collection.getVisibility()) {
                access.requireManage(collection);
            }
        }
        if (request.parentCollectionId() != null) {
            if (request.parentCollectionId().equals(collectionId)) {
                throw new IllegalArgumentException("A collection cannot be its own parent");
            }
            requireExists(request.parentCollectionId());
        }
        collection.update(request.name(), request.description(), request.parentCollectionId());
        if (request.visibility() != null) {
            collection.changeVisibility(request.visibility());
        }
        if (request.chunkSize() != null || request.chunkOverlap() != null) {
            Chunker.resolve(
                null, null,
                request.chunkSize() != null ? request.chunkSize() : collection.getChunkSize(),
                request.chunkOverlap() != null ? request.chunkOverlap() : collection.getChunkOverlap()
            );
            collection.updateChunking(request.chunkSize(), request.chunkOverlap());
        }
        return toResponse(repository.save(collection));
    }

    private KnowledgeCollectionResponse toResponse(KnowledgeCollection collection) {
        long fileCount = documentService.fileCount(collection.getId());
        long chunkCount = documentService.chunkCount(collection.getId());
        return KnowledgeCollectionResponse.from(collection, fileCount, chunkCount);
    }

    @Transactional
    public void delete(UUID collectionId) {
        KnowledgeCollection collection = getEntity(collectionId);
        if (access != null) {
            access.requireModify(collection);
        }
        if (collection.isDefault()) {
            throw new KnowledgeCollectionNotEmptyException(
                collectionId, "it is this tenant's default collection and must always exist"
            );
        }
        // RAG audit fix (2026-07-29) - document guard BEFORE the chunk
        // guard, and now actually actionable: DELETE /api/knowledge/
        // documents/{documentId} exists (KnowledgeDocumentService#delete),
        // so "delete the documents first" is a real remedy, not a dead end.
        // The guard also matters independently of chunks: a FAILED upload
        // leaves a knowledge_document row with zero chunks, and deleting the
        // collection under it would violate knowledge_document's FK (V45) -
        // a raw 500 without this check.
        long documentCount = documentService.fileCount(collectionId);
        if (documentCount > 0) {
            throw new KnowledgeCollectionNotEmptyException(
                collectionId,
                documentCount + " file(s) are still in it - delete the files first"
            );
        }
        // Chunks with no owning document (raw text via POST /api/knowledge/
        // ingest) can still block deletion after every document is gone -
        // kept as its own guard with its own message rather than silently
        // cascading, since raw-ingested text has no per-item delete surface
        // yet (a deliberate, documented gap - see the audit notes).
        long chunkCount = countChunksIn(collectionId);
        if (chunkCount > 0) {
            throw new KnowledgeCollectionNotEmptyException(
                collectionId, chunkCount + " knowledge chunk(s) still reference it - move or delete them first"
            );
        }
        repository.delete(collection);
    }

    /**
     * The collection an upload lands in when none is explicitly specified -
     * see migration V15's header comment for why this is a dedicated {@code
     * is_default} flag rather than a name match.
     */
    @Transactional(readOnly = true)
    public UUID getDefaultCollectionId() {
        return repository.findByIsDefaultTrue()
            .map(KnowledgeCollection::getId)
            .orElseThrow(() -> new IllegalStateException(
                "No default knowledge collection exists for this tenant - migration V15 should have seeded one"
            ));
    }

    /** Real collection count for {@code GET /api/knowledge/stats}'s topbar total. */
    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    /**
     * The chunking budget an ingestion into {@code collectionId} should use:
     * explicit per-request values win, then the collection's own V361
     * columns, then {@link Chunker}'s platform defaults (150/30). Throws
     * {@link IllegalArgumentException} (400) for an invalid combination.
     */
    @Transactional(readOnly = true)
    public Chunker.Settings resolveChunking(UUID collectionId, Integer requestedSize, Integer requestedOverlap) {
        KnowledgeCollection collection = getEntity(collectionId);
        return Chunker.resolve(requestedSize, requestedOverlap, collection.getChunkSize(), collection.getChunkOverlap());
    }

    /** Exists AND the caller may read it; otherwise the same 404 as an unknown id. */
    @Transactional(readOnly = true)
    public void requireReadable(UUID collectionId) {
        requireExists(collectionId);
        if (access != null) {
            access.requireRead(collectionId);
        }
    }

    @Transactional(readOnly = true)
    public void requireExists(UUID collectionId) {
        if (!repository.existsById(collectionId)) {
            throw new KnowledgeCollectionNotFoundException(collectionId);
        }
    }

    private KnowledgeCollection getEntity(UUID collectionId) {
        return repository.findById(collectionId)
            .orElseThrow(() -> new KnowledgeCollectionNotFoundException(collectionId));
    }

    private long countChunksIn(UUID collectionId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE collection_id = ?", Long.class, collectionId
        );
        return count != null ? count : 0L;
    }
}
