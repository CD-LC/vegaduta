package com.agenticai.core.agents.memory;

import com.agenticai.core.knowledge.SidecarClient;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** The write side of the user-visible memory blocks, for {@link TenantMemoryService}. */
final class TenantMemoryBlockWrites {

    private TenantMemoryBlockWrites() {}

    static void putBlock(
            JdbcTemplate jdbcTemplate,
            SidecarClient sidecarClient,
            java.time.Clock clock,
            boolean activeProjectEnabled,
            int maxCharsPerBlock,
            int maxProjectBlocks,
            String subjectKey,
            String rawLabel,
            String content) {
        String label = MemoryBlocks.normalizeLabel(rawLabel);
        if (label == null || (!activeProjectEnabled && MemoryBlocks.ACTIVE_PROJECT.equals(label))) {
            throw new IllegalArgumentException("Unknown memory block label.");
        }
        if (content == null || content.isBlank()) {
            deleteBlock(jdbcTemplate, subjectKey, label);
            return;
        }
        if (MemoryBlocks.ACTIVE_PROJECT.equals(label)) {
            content = requireExistingProjectSlug(jdbcTemplate, subjectKey, content);
        }
        if (content.length() > maxCharsPerBlock) {
            throw new IllegalArgumentException(
                    "A memory block can hold at most " + maxCharsPerBlock + " characters.");
        }
        String key = MemoryBlocks.storageKey(subjectKey, label);
        if (key == null) {
            throw new IllegalArgumentException("Memory block cannot be stored for this user.");
        }
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        List<UUID> existing =
                jdbcTemplate.query(
                        // codeql[java/sql-injection]
                        "SELECT id FROM " + table + " WHERE subject_key = ?",
                        (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                        key);
        if (existing.isEmpty() && MemoryBlocks.isProjectLabel(label)) {
            Integer projects =
                    jdbcTemplate.queryForObject(
                            // codeql[java/sql-injection]
                            "SELECT COUNT(*) FROM " + table + " WHERE starts_with(subject_key, ?)",
                            Integer.class,
                            MemoryBlocks.storagePrefix(subjectKey) + MemoryBlocks.PROJECT_PREFIX);
            if (projects != null && projects >= maxProjectBlocks) {
                throw new IllegalArgumentException(
                        "You can keep at most " + maxProjectBlocks + " project notes.");
            }
        }
        // Embedding is NOT NULL on the table; blocks are always-in-context so it is never queried.
        String literal = TenantMemoryService.toVectorLiteral(sidecarClient.embedQuery(content));
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    // codeql[java/sql-injection]
                    "INSERT INTO "
                            + table
                            + " (id, subject_key, content, embedding, created_by_agent_id) "
                            + "VALUES (?, ?, ?, ?::public.vector, NULL)",
                    UUID.randomUUID(),
                    key,
                    content,
                    literal);
        } else {
            jdbcTemplate.update(
                    // codeql[java/sql-injection]
                    "UPDATE "
                            + table
                            + " SET content = ?, embedding = ?::public.vector, last_used_at = ?"
                            + " WHERE id = ?",
                    content,
                    literal,
                    Timestamp.from(clock.instant()),
                    existing.get(0));
        }
    }

    /**
     * Validates an {@code active_project} value: a legal slug whose {@code project:<slug>} note
     * exists. Returns the slug.
     */
    private static String requireExistingProjectSlug(
            JdbcTemplate jdbcTemplate, String subjectKey, String content) {
        String slug = MemoryBlocks.activeProjectSlug(content);
        String projectKey =
                slug == null
                        ? null
                        : MemoryBlocks.storageKey(subjectKey, MemoryBlocks.PROJECT_PREFIX + slug);
        if (projectKey == null) {
            throw new IllegalArgumentException("Choose one of your project notes to make active.");
        }
        Integer found =
                jdbcTemplate.queryForObject(
                        // codeql[java/sql-injection]
                        "SELECT COUNT(*) FROM "
                                + TenantMemorySchemaSupport.qualifiedTableName()
                                + " WHERE subject_key = ?",
                        Integer.class,
                        projectKey);
        if (found == null || found == 0) {
            throw new IllegalArgumentException(
                    "There is no project note named '" + slug + "'. Add the note first.");
        }
        return slug;
    }

    static void deleteBlock(JdbcTemplate jdbcTemplate, String subjectKey, String rawLabel) {
        String label = MemoryBlocks.normalizeLabel(rawLabel);
        String key = label == null ? null : MemoryBlocks.storageKey(subjectKey, label);
        if (key == null) {
            return;
        }
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        // codeql[java/sql-injection]
        jdbcTemplate.update("DELETE FROM " + table + " WHERE subject_key = ?", key);
        if (MemoryBlocks.isProjectLabel(label)) {
            // A deleted project cannot stay the active one: drop the pointer only if it points
            // here.
            String activeKey = MemoryBlocks.storageKey(subjectKey, MemoryBlocks.ACTIVE_PROJECT);
            if (activeKey != null) {
                jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM " + table + " WHERE subject_key = ? AND content = ?",
                        activeKey,
                        label.substring(MemoryBlocks.PROJECT_PREFIX.length()));
            }
        }
    }
}
