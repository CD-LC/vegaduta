package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeCollectionRepository;
import com.agenticai.core.knowledge.KnowledgeSchemaSupport;
import com.agenticai.core.knowledge.rag.RagDtos.RagSettingsResponse;
import com.agenticai.core.knowledge.rag.RagDtos.RagSettingsUpdateRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A collection's retrieval-quality settings - today just the parent-child opt-in, stored in
 * {@code knowledge_collection.parent_child_enabled} (migration V432).
 *
 * <p>Raw JDBC on purpose: the {@code KnowledgeCollection} entity (and its response DTO) are untouched,
 * so a tenant schema that has not applied V432 cannot break the existing collection queries - every
 * read here fails open to "off" and only the new endpoints report the missing schema.
 *
 * <p>The ingestion path asks {@link #parentChildIngestSettings}; it answers non-empty only when the
 * deployment switch ({@code app.knowledge.parent-child.enabled}) AND the collection's own flag are on.
 */
@Service
@Profile("knowledge")
public class KnowledgeRagSettingsService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRagSettingsService.class);

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeCollectionRepository collectionRepository;
    private final RagRetrievalConfig config;

    public KnowledgeRagSettingsService(
        JdbcTemplate jdbcTemplate, KnowledgeCollectionRepository collectionRepository, RagRetrievalConfig config
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.collectionRepository = collectionRepository;
        this.config = config;
    }

    /** Parent-child sizing to ingest {@code collectionId} with, or empty for the normal flat chunking. Never throws. */
    public Optional<ParentChildChunker.Settings> parentChildIngestSettings(UUID collectionId) {
        if (!config.isParentChildEnabled() || collectionId == null) {
            return Optional.empty();
        }
        return readCollectionFlag(collectionId) ? Optional.of(config.parentChildSettings()) : Optional.empty();
    }

    public RagSettingsResponse get(UUID collectionId) {
        requireCollection(collectionId);
        return response(collectionId);
    }

    public RagSettingsResponse update(UUID collectionId, RagSettingsUpdateRequest request) {
        requireCollection(collectionId);
        if (request != null && request.parentChildEnabled() != null) {
            boolean enable = request.parentChildEnabled();
            if (enable && !config.isParentChildEnabled()) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Parent-child chunking is not enabled on this deployment (app.knowledge.parent-child.enabled)"
                );
            }
            String table = KnowledgeSchemaSupport.qualifiedTableName("knowledge_collection");
            try {
                int updated = jdbcTemplate.update(
                    "UPDATE " + table + " SET parent_child_enabled = ?, updated_at = now() WHERE id = ?", enable, collectionId
                );
                if (updated == 0) {
                    throw new KnowledgeCollectionNotFoundException(collectionId);
                }
            } catch (DataAccessException e) {
                log.warn("Could not store parent_child_enabled for collection {} (schema likely pending V432): {}",
                    collectionId, e.getMessage());
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Retrieval settings are not available yet - the database migration has not been applied"
                );
            }
        }
        return response(collectionId);
    }

    /** Count of stored parent passages in a collection (0 when the table is missing). */
    public long parentChunkCount(UUID collectionId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName("knowledge_parent_chunk");
        try {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE collection_id = ?", Long.class, collectionId);
            return n == null ? 0L : n;
        } catch (DataAccessException e) {
            return 0L;
        }
    }

    private RagSettingsResponse response(UUID collectionId) {
        boolean schemaReady = true;
        boolean enabled = false;
        String table = KnowledgeSchemaSupport.qualifiedTableName("knowledge_collection");
        try {
            List<Boolean> rows = jdbcTemplate.query(
                "SELECT parent_child_enabled FROM " + table + " WHERE id = ?", (rs, i) -> rs.getBoolean(1), collectionId
            );
            enabled = !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
        } catch (DataAccessException e) {
            schemaReady = false;
        }
        RetrievalOptions deployment = config.deploymentOptions();
        ParentChildChunker.Settings sizes = config.parentChildSettings();
        return new RagSettingsResponse(
            config.isParentChildEnabled(),
            enabled,
            sizes.childWords(),
            sizes.parentWords(),
            schemaReady ? parentChunkCount(collectionId) : 0L,
            deployment.fusion(),
            deployment.rewriteMode().name().toLowerCase(java.util.Locale.ROOT),
            schemaReady
        );
    }

    private boolean readCollectionFlag(UUID collectionId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName("knowledge_collection");
        try {
            List<Boolean> rows = jdbcTemplate.query(
                "SELECT parent_child_enabled FROM " + table + " WHERE id = ?", (rs, i) -> rs.getBoolean(1), collectionId
            );
            return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
        } catch (DataAccessException e) {
            log.debug("parent_child_enabled unreadable (schema likely pending V432) - ingesting flat: {}", e.getMessage());
            return false;
        }
    }

    private void requireCollection(UUID collectionId) {
        if (collectionId == null || collectionRepository.findById(collectionId).isEmpty()) {
            throw new KnowledgeCollectionNotFoundException(collectionId);
        }
    }
}
