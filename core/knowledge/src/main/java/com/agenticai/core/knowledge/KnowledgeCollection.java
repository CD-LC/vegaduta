package com.agenticai.core.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Knowledge Stage F1 - a named, described, optionally-nested group of
 * {@code knowledge_chunks} rows. Unlike {@code knowledge_chunks} itself
 * (raw JDBC, pgvector has no Hibernate mapping - see
 * {@code VectorSchemaInitializer}'s javadoc), this entity is plain
 * relational data with no vector column, so it's Hibernate-mapped like
 * every other tenant-scoped entity ({@code Agent}, etc.) - schema routing
 * comes for free from {@code TenantSchemaResolver}.
 *
 * <p>{@code description} is not cosmetic - Stage F2's
 * {@code list_knowledge_collections} tool returns it verbatim so a model
 * can decide which collection is relevant to a scenario before searching.
 * Collection creation/edit UX should treat it as effectively required.
 *
 * <p>{@code parentCollectionId} models nesting (a collection can live under
 * another) without a full filesystem-style per-document folder tree - see
 * the Stage F Build Plan's "Decisions" section for why. Deliberately not a
 * JPA {@code @ManyToOne} self-reference - kept as a plain UUID column so
 * this entity never needs a full parent object graph loaded just to know
 * its own ancestry; callers that need the parent's name/description look
 * it up via {@link KnowledgeCollectionRepository} explicitly.
 *
 * <p>{@code isDefault} is true for exactly one row per tenant (seeded by
 * migration V15) - where an upload lands when no collection is specified,
 * and the one collection {@link KnowledgeCollectionService} refuses to
 * delete.
 */
@Entity
@Table(name = "knowledge_collection")
public class KnowledgeCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "parent_collection_id")
    private UUID parentCollectionId;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    // Multi-model ingestion - one of EmbeddingModelCatalog's keys, chosen at
    // creation. `update()` below still never touches it: the ONLY way it
    // changes is KnowledgeReembedService's flip, a raw-JDBC UPDATE that swaps
    // every chunk's vector and this column in one transaction, so a
    // collection never holds two models' vectors as its live set (see
    // EmbeddingModelCatalog's javadoc for why mixing models isn't allowed).
    // updatable = false: a JPA save of a collection loaded before a flip must
    // never write the stale model back over it (lost update) - both this and
    // embeddingStatus are written only by KnowledgeReembedService's JDBC.
    @Column(name = "embedding_model", nullable = false, length = 64, updatable = false)
    private String embeddingModel;

    // Migration V362 - READY, or REEMBEDDING while a KnowledgeReembedJob is
    // building shadow vectors for a new model. Not nullable=false in JPA for
    // the same mid-rollout reason as textSearchConfig below; null reads as READY.
    @Column(name = "embedding_status", length = 32, updatable = false)
    private String embeddingStatus;

    // Migration V550 - the embedding SOURCE that produced this collection's vectors ("sidecar" or
    // "provider:model:width", see SidecarClient#embeddingFingerprint); null = not bound yet. Written
    // only by KnowledgeEmbeddingBinding / KnowledgeReembedService's JDBC, never by JPA.
    @Column(name = "embedding_fingerprint", length = 190, insertable = false, updatable = false)
    private String embeddingFingerprint;

    // Migration V361 - per-collection chunking budget in words, null = the
    // platform default (Chunker.DEFAULT_CHUNK_SIZE / DEFAULT_CHUNK_OVERLAP).
    // Only affects content ingested after it's set; existing chunks keep the
    // boundaries they were cut with.
    @Column(name = "chunk_size")
    private Integer chunkSize;

    @Column(name = "chunk_overlap")
    private Integer chunkOverlap;

    // RAG audit fix (2026-07-29, migration V154) - the Postgres text-search
    // configuration this collection's keyword/full-text side uses (one of
    // TextSearchConfigCatalog's keys). Immutable after creation for the same
    // reason embeddingModel is: every stored chunk's content_tsv was built
    // with it. Not marked nullable=false in JPA despite the column's NOT
    // NULL DEFAULT - a tenant schema that hasn't applied V154 yet reads
    // null here, which every consumer coalesces to 'simple' (see
    // KnowledgeIngestionService/KnowledgeSearchService), keeping the module
    // functional mid-rollout instead of failing entity validation.
    @Column(name = "text_search_config", length = 64)
    private String textSearchConfig;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeCollection() {
        // JPA
    }

    public KnowledgeCollection(
        String name, String description, UUID parentCollectionId, String embeddingModel, String textSearchConfig
    ) {
        this.name = name;
        this.description = description;
        this.parentCollectionId = parentCollectionId;
        this.isDefault = false;
        this.embeddingModel = embeddingModel;
        this.textSearchConfig = textSearchConfig;
        this.embeddingStatus = STATUS_READY;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static final String STATUS_READY = "READY";
    public static final String STATUS_REEMBEDDING = "REEMBEDDING";

    public void update(String name, String description, UUID parentCollectionId) {
        this.name = name;
        this.description = description;
        this.parentCollectionId = parentCollectionId;
        this.updatedAt = Instant.now();
    }

    /** Null arguments leave the stored value unchanged; validated by the caller via {@link Chunker#resolve}. */
    public void updateChunking(Integer chunkSize, Integer chunkOverlap) {
        if (chunkSize != null) {
            this.chunkSize = chunkSize;
        }
        if (chunkOverlap != null) {
            this.chunkOverlap = chunkOverlap;
        }
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getParentCollectionId() {
        return parentCollectionId;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public String getTextSearchConfig() {
        return textSearchConfig;
    }

    /** Null (pre-V362 schema) reads as {@link #STATUS_READY}. */
    public String getEmbeddingStatus() {
        return embeddingStatus != null ? embeddingStatus : STATUS_READY;
    }

    /** The embedding source bound to this collection's vectors, or null when none has been recorded yet. */
    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public Integer getChunkOverlap() {
        return chunkOverlap;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
