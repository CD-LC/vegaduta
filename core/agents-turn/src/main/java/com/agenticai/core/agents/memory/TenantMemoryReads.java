package com.agenticai.core.agents.memory;

import com.agenticai.core.security.logging.LogSafe;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subject-scoped recall, retention purge and bulk erasure for {@link TenantMemoryService},
 * extracted so the service stays under the file-size cap. Package-private; callers still go through
 * {@link TenantMemoryService}.
 */
final class TenantMemoryReads {

    private static final Logger log = LoggerFactory.getLogger(TenantMemoryReads.class);

    private final TenantMemoryService owner;

    TenantMemoryReads(TenantMemoryService owner) {
        this.owner = owner;
    }

    /**
     * {@link #recall(String, String, int)} with an embedding the caller may share with other
     * recalls of the same text: the sidecar is asked at most once for all of them.
     */
    List<TenantMemoryEntry> recall(
            String subjectKey, String query, int topK, QueryEmbedding embedding) {
        float[] queryVector = embedding.vector();
        String literal = TenantMemoryService.toVectorLiteral(queryVector);
        String table = TenantMemorySchemaSupport.qualifiedTableName();

        List<TenantMemoryEntry> results =
                owner.jdbcTemplate.query(
                        // codeql[java/sql-injection]
                        "SELECT id, subject_key, content, created_by_agent_id, created_at,"
                                + " last_used_at, use_count, trust, source_taint,"
                                + " embedding OPERATOR(public.<=>)"
                                + " ?::public.vector AS distance FROM "
                                + table
                                + " WHERE subject_key = ? AND trust = 'TRUSTED' AND superseded_by IS NULL"
                                + owner.tombstones.excludeSharedFacts(table)
                                + " ORDER BY distance ASC, updated_at DESC LIMIT ?",
                        TenantMemoryFactRows.entryMapper(),
                        literal,
                        subjectKey,
                        topK);

        if (!results.isEmpty()) {
            touchUsage(table, results.stream().map(TenantMemoryEntry::id).toList());
        }
        log.debug(
                "Recalled {} shared memories for subject '{}' (query='{}')",
                results.size(),
                LogSafe.clean(subjectKey),
                LogSafe.clean(query));
        return results;
    }

    /**
     * Convenience overload - default topK, used by {@link
     * com.agenticai.core.agents.turn.AgentChatService}.
     */
    List<TenantMemoryEntry> recall(String subjectKey, String query) {
        return owner.recall(subjectKey, query, owner.recallTopK(null));
    }

    /** Default-topK recall that shares {@code embedding}; see {@link QueryEmbedding}. */
    List<TenantMemoryEntry> recall(String subjectKey, String query, QueryEmbedding embedding) {
        return recall(subjectKey, query, embedding, null);
    }

    /**
     * As {@link #recall(String, String, QueryEmbedding)} for the shared recall made on behalf of
     * {@code agentId}: how many facts it returns follows that agent's (or its tenant's) {@code
     * memory.recall.tenantTopK}.
     */
    List<TenantMemoryEntry> recall(
            String subjectKey, String query, QueryEmbedding embedding, UUID agentId) {
        return recall(subjectKey, query, owner.recallTopK(agentId), embedding);
    }

    /**
     * Retention purge (DPDP-oriented controls): deletes up to {@code limit} rows created before
     * {@code cutoff} in the tenant's schema, oldest first, returning how many went; the caller
     * loops until fewer than {@code limit}. Block rows ({@value MemoryBlocks#KEY_MARKER}) are
     * spared as in {@link #pruneOverCap}; only erasure removes them.
     */
    int purgeCreatedBefore(java.time.Instant cutoff, int limit) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        int deleted =
                owner.jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM "
                                + table
                                + " WHERE id IN (SELECT id FROM "
                                + table
                                + " WHERE created_at < ? AND position('"
                                + MemoryBlocks.KEY_MARKER
                                + "' in subject_key) = 0 ORDER BY created_at LIMIT ?)",
                        java.sql.Timestamp.from(cutoff),
                        limit);
        log.info("Retention purge removed {} tenant-memory rows older than {}", deleted, cutoff);
        return deleted;
    }

    /**
     * #815 Governance follow-up parity with {@link AgentMemoryService#forgetAllForSubject} -
     * deletes every shared memory this tenant holds about one subject, for the same data-subject
     * erasure reasoning. Returns the number of rows deleted.
     */
    int forgetAllForSubject(String subjectKey) {
        String table = TenantMemorySchemaSupport.qualifiedTableName();
        // Also sweeps this subject's user-visible memory blocks (subject_key =
        // '<subject>#mem:<label>').
        int deleted =
                owner.jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM "
                                + table
                                + " WHERE subject_key = ? OR starts_with(subject_key, ?)",
                        subjectKey,
                        MemoryBlocks.storagePrefix(subjectKey));
        log.info(
                "Erased {} shared tenant-memory rows for subject key (data-subject erasure"
                        + " request)",
                deleted);
        return deleted;
    }

    void touchUsage(String table, List<UUID> ids) {
        TenantMemoryFactRows.touchUsage(
                owner.jdbcTemplate, table, Timestamp.from(owner.clock.instant()), ids);
    }
}
