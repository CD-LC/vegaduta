package com.agenticai.core.knowledge.dto;

import com.agenticai.core.knowledge.KnowledgeVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Knowledge Stage F1. {@code description} isn't {@code @NotBlank} (some
 * users will skip it), but a blank description means Stage F2's
 * {@code list_knowledge_collections} tool has nothing meaningful to show a
 * model deciding whether this collection is relevant - worth surfacing in
 * the console UI as a strongly-encouraged field, not just an optional one.
 */
public record KnowledgeCollectionCreateRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    UUID parentCollectionId,
    // Multi-model ingestion - one of EmbeddingModelCatalog's keys, or null/
    // blank to use the catalog default. Changeable afterward only through a
    // re-embed job (POST /api/knowledge/collections/{id}/reembed).
    String embeddingModel,
    // RAG audit fix (2026-07-29) - one of TextSearchConfigCatalog's keys, or
    // null/blank for its default ('simple', language-agnostic). Chosen once
    // and immutable (every stored chunk's tsvector is built with it).
    String textSearchConfig,
    // Migration V361 - per-collection chunking budget in words; null = the
    // platform default (Chunker.DEFAULT_CHUNK_SIZE / DEFAULT_CHUNK_OVERLAP).
    @Min(10) @Max(200) Integer chunkSize,
    @Min(0) @Max(199) Integer chunkOverlap,
    // Migration V710 - who may read it; null = TENANT (every member), the historic behaviour.
    // The creator becomes the owner either way.
    KnowledgeVisibility visibility
) {
    /** Pre-V710 shape (chunking, no visibility) - kept so existing callers compile unchanged. */
    public KnowledgeCollectionCreateRequest(
        String name, String description, UUID parentCollectionId, String embeddingModel, String textSearchConfig,
        Integer chunkSize, Integer chunkOverlap
    ) {
        this(name, description, parentCollectionId, embeddingModel, textSearchConfig, chunkSize, chunkOverlap, null);
    }

    /** Pre-V361 shape - kept so existing callers (brief, demo studio) compile unchanged. */
    public KnowledgeCollectionCreateRequest(
        String name, String description, UUID parentCollectionId, String embeddingModel, String textSearchConfig
    ) {
        this(name, description, parentCollectionId, embeddingModel, textSearchConfig, null, null, null);
    }
}
