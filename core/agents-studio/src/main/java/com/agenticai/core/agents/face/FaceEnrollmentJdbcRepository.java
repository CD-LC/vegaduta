package com.agenticai.core.agents.face;

import com.agenticai.core.agents.face.FaceEnrollmentService.FaceEnrollmentRecord;
import com.agenticai.core.knowledge.KnowledgeSchemaSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Raw JDBC over {@code knowledge_face_enrollment} (migration V187), behind {@link
 * FaceEnrollmentService}. Raw JDBC rather than Hibernate because pgvector's {@code vector} column
 * type has no Hibernate mapping.
 */
@Repository
public class FaceEnrollmentJdbcRepository {

    private static final String TABLE_NAME = "knowledge_face_enrollment";

    private final JdbcTemplate jdbcTemplate;

    public FaceEnrollmentJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void insert(
            UUID id,
            UUID collectionId,
            UUID documentId,
            String personName,
            List<Double> embedding,
            Instant consentedAt) {
        String table = KnowledgeSchemaSupport.qualifiedTableName(TABLE_NAME);
        jdbcTemplate.update(
            "INSERT INTO " + table + " (id, collection_id, document_id, person_name, embedding, consented_at) "
                + "VALUES (?, ?, ?, ?, ?::public.vector, ?)",
            id, collectionId, documentId, personName, toVectorLiteral(embedding), consentedAt
        );
    }

    List<FaceEnrollmentRecord> listForCollection(UUID collectionId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName(TABLE_NAME);
        return jdbcTemplate.query(
            "SELECT id, person_name, embedding::text AS embedding_text, created_at FROM " + table
                + " WHERE collection_id = ? ORDER BY created_at",
            (rs, rowNum) -> new FaceEnrollmentRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("person_name"),
                parseVectorLiteral(rs.getString("embedding_text")),
                rs.getTimestamp("created_at").toInstant()
            ),
            collectionId
        );
    }

    void deleteById(UUID enrollmentId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName(TABLE_NAME);
        jdbcTemplate.update("DELETE FROM " + table + " WHERE id = ?", enrollmentId);
    }

    private static String toVectorLiteral(List<Double> embedding) {
        return embedding.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    /** Inverse of {@link #toVectorLiteral} - pgvector's {@code ::text} cast renders as {@code [0.1,0.2,...]}. */
    private static List<Double> parseVectorLiteral(String literal) {
        String trimmed = literal.substring(1, literal.length() - 1);
        if (trimmed.isBlank()) {
            return List.of();
        }
        return List.of(trimmed.split(",")).stream().map(s -> Double.parseDouble(s.trim())).collect(Collectors.toList());
    }
}
