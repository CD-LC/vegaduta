package com.agenticai.core.common.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Raw SQL for {@link PurposeService} ({@code privacy_purpose_tag}). */
@Repository
public class PurposeJdbcRepository {

    private final JdbcTemplate jdbc;

    public PurposeJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> purposes(String resourceType, UUID resourceId) {
        return jdbc.queryForList(
                "SELECT purpose FROM privacy_purpose_tag WHERE resource_type = ? AND resource_id = ?"
                        + " ORDER BY purpose",
                String.class,
                resourceType,
                resourceId);
    }

    /** {@code table} is one of two internal constants, never user input. */
    boolean resourceExists(String table, UUID resourceId) {
        Integer exists =
                jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, resourceId);
        return exists != null && exists != 0;
    }

    void replaceTags(String resourceType, UUID resourceId, Collection<String> purposes, String actor) {
        jdbc.update(
                "DELETE FROM privacy_purpose_tag WHERE resource_type = ? AND resource_id = ?",
                resourceType,
                resourceId);
        for (String purpose : purposes) {
            jdbc.update(
                    "INSERT INTO privacy_purpose_tag (resource_type, resource_id, purpose, tagged_by,"
                            + " tagged_at) VALUES (?, ?, ?, ?, ?)",
                    resourceType,
                    resourceId,
                    purpose,
                    actor,
                    Timestamp.from(Instant.now()));
        }
    }
}
