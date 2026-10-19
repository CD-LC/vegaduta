package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.KnowledgeSchemaSupport;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Raw-JDBC access to {@code knowledge_parent_chunk} (migration V432), the table holding the larger
 * parent passages of parent-child collections. Schema-qualified through {@link KnowledgeSchemaSupport}
 * for the same reason {@code knowledge_chunks} is (this module's raw JDBC is not routed by Hibernate's
 * tenant connection provider).
 *
 * <p>The read path fails open: on a tenant schema that has not applied V432 the lookup logs at debug
 * and returns nothing, so search keeps returning the child chunks it always did.
 */
@Service
@Profile("knowledge")
public class ParentChunkStore {

    private static final Logger log = LoggerFactory.getLogger(ParentChunkStore.class);

    /** One parent row to persist. */
    public record ParentRow(UUID id, int index, String content) {}

    private final JdbcTemplate jdbcTemplate;

    public ParentChunkStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Persists parents for one ingestion. {@code documentId} may be null (raw text ingest). */
    public void insertAll(UUID collectionId, UUID documentId, List<ParentRow> parents) {
        if (parents.isEmpty()) {
            return;
        }
        String table = KnowledgeSchemaSupport.qualifiedTableName("knowledge_parent_chunk");
        jdbcTemplate.batchUpdate(
            "INSERT INTO " + table + " (id, collection_id, document_id, parent_index, content) VALUES (?, ?, ?, ?, ?)",
            parents.stream()
                .map(p -> new Object[] {p.id(), collectionId, documentId, p.index(), p.content()})
                .collect(Collectors.toList())
        );
    }

    /**
     * For each given chunk id that is a child of a stored parent, its {@link ParentExpander.ParentLink}.
     * Chunks without a parent (flat chunks) are simply absent from the map.
     */
    public Map<UUID, ParentExpander.ParentLink> linksFor(Collection<UUID> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        String chunks = KnowledgeSchemaSupport.qualifiedTableName("knowledge_chunks");
        String parents = KnowledgeSchemaSupport.qualifiedTableName("knowledge_parent_chunk");
        String idArray = chunkIds.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        String sql = "SELECT c.id AS chunk_id, p.id AS parent_id, p.content AS parent_content, "
            + "(c.metadata->>'child_start')::int AS child_start, (c.metadata->>'child_end')::int AS child_end "
            + "FROM " + chunks + " c JOIN " + parents + " p ON p.id = (c.metadata->>'parent_id')::uuid "
            + "WHERE c.id = ANY(?::uuid[])";
        try {
            Map<UUID, ParentExpander.ParentLink> out = new HashMap<>();
            jdbcTemplate.query(sql, rs -> {
                out.put(
                    UUID.fromString(rs.getString("chunk_id")),
                    new ParentExpander.ParentLink(
                        UUID.fromString(rs.getString("parent_id")),
                        rs.getString("parent_content"),
                        rs.getInt("child_start"),
                        rs.getInt("child_end")
                    )
                );
            }, idArray);
            return out;
        } catch (DataAccessException e) {
            log.debug("Parent lookup skipped (schema likely pending V432): {}", e.getMessage());
            return Map.of();
        }
    }
}
