package com.agenticai.core.agents.memory;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subject-facing operations of {@link AgentMemoryService} - subject-scoped lists, update/forget,
 * approval (with graph sync) and bulk erasure - extracted so the service stays under the file-size
 * cap. Package-private; callers still go through {@link AgentMemoryService}.
 */
final class AgentMemorySubjectOps {

    private static final Logger log = LoggerFactory.getLogger(AgentMemorySubjectOps.class);

    private final AgentMemoryService owner;

    AgentMemorySubjectOps(AgentMemoryService owner) {
        this.owner = owner;
    }

    List<AgentMemoryEntry> listAll(UUID agentId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return owner.jdbcTemplate.query(
                // codeql[java/sql-injection]
                "SELECT id, agent_id, subject_key, content, created_at, last_used_at, use_count,"
                        + " trust, source_taint, 0.0 AS distance FROM "
                        + table
                        + " WHERE agent_id = ? AND subject_key = ? AND superseded_by IS NULL ORDER BY created_at DESC",
                AgentMemoryRows.entryMapper(),
                agentId,
                subjectKey);
    }

    /**
     * Every private memory any agent holds about this subject, newest first - the signed-in user's
     * own view of what they have been remembered as (gap-analysis item 11). Not agent-scoped on
     * purpose: the owner sees all of it in one place; each row still carries its {@code agentId}.
     * Includes the facts a newer one superseded (V685), flagged by {@link
     * AgentMemoryEntry#supersededAt()}: this is the list behind the Memory page, the export and the
     * erasure, which must see everything that is stored.
     */
    List<AgentMemoryEntry> listAllForSubject(String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return owner.jdbcTemplate.query(
                // codeql[java/sql-injection]
                "SELECT id, agent_id, subject_key, content, created_at, last_used_at, use_count,"
                        + " trust, source_taint, superseded_at, 0.0 AS distance FROM "
                        + table
                        + " WHERE subject_key = ? ORDER BY created_at DESC",
                AgentMemoryRows.entryMapper(),
                subjectKey);
    }

    /**
     * Up to {@code limit} distinct people (subject keys) who have a private memory in the current
     * tenant. For background reviews that walk every person; not for request paths.
     */
    List<String> listSubjects(int limit) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return owner.jdbcTemplate.queryForList(
                // codeql[java/sql-injection]
                "SELECT DISTINCT subject_key FROM " + table + " LIMIT ?",
                String.class,
                Math.max(1, limit));
    }

    /**
     * Re-embeds and rewrites one memory owned by {@code subjectKey}; false when it is not theirs.
     */
    boolean updateForSubject(UUID memoryId, String subjectKey, String content) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        String literal =
                AgentMemoryService.toVectorLiteral(owner.sidecarClient.embedQuery(content));
        return owner.jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "UPDATE "
                                + table
                                + " SET content = ?, embedding = ?::public.vector WHERE id = ? AND"
                                + " subject_key = ?",
                        content,
                        literal,
                        memoryId,
                        subjectKey)
                > 0;
    }

    /** Deletes one memory only if it belongs to {@code subjectKey}; false when it is not theirs. */
    boolean forgetForSubject(UUID memoryId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return owner.jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM " + table + " WHERE id = ? AND subject_key = ?",
                        memoryId,
                        subjectKey)
                > 0;
    }

    /**
     * A person's approval of a QUARANTINED memory: it becomes TRUSTED (recallable) and keeps its
     * {@code source_taint} as provenance. False when it does not exist for this agent or was
     * already trusted.
     */
    boolean approve(UUID agentId, UUID memoryId) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        boolean approved =
                owner.jdbcTemplate.update(
                                // codeql[java/sql-injection]
                                "UPDATE "
                                        + table
                                        + " SET trust = 'TRUSTED' WHERE id = ? AND agent_id = ?"
                                        + " AND trust = 'QUARANTINED'",
                                memoryId,
                                agentId)
                        > 0;
        if (approved) {
            afterApproval(agentId, memoryId);
        }
        return approved;
    }

    /** {@link #approve(UUID, UUID)} restricted to a memory about {@code subjectKey}. */
    boolean approve(UUID agentId, UUID memoryId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        boolean approved =
                owner.jdbcTemplate.update(
                                // codeql[java/sql-injection]
                                "UPDATE "
                                        + table
                                        + " SET trust = 'TRUSTED' WHERE id = ? AND agent_id = ?"
                                        + " AND subject_key = ? AND trust = 'QUARANTINED'",
                                memoryId,
                                agentId,
                                subjectKey)
                        > 0;
        if (approved) {
            afterApproval(agentId, memoryId);
        }
        return approved;
    }

    /**
     * A person approved a fact: it now replaces the facts it was parked against (V685), then it is
     * offered to the graph.
     */
    void afterApproval(UUID agentId, UUID memoryId) {
        FactSupersession sup = owner.supersession;
        if (sup != null) {
            sup.applyPending(
                    AgentMemorySchemaSupport.qualifiedTableName(), memoryId, owner.clock.instant());
        }
        syncApprovedToGraph(agentId, memoryId);
    }

    volatile ApprovedFactGraphSync graphSync;

    /**
     * Mirrors an approved fact into the knowledge graph (G6); absent in hand-built instances, then
     * approval is vector-only.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGraphSync(ApprovedFactGraphSync graphSync) {
        this.graphSync = graphSync;
    }

    /**
     * After an approval has committed: offers the fact to the graph, unless writes are switched off
     * for the agent. Best-effort and fail-open (see {@link ApprovedFactGraphSync}); never changes
     * the approval's result.
     */
    void syncApprovedToGraph(UUID agentId, UUID memoryId) {
        ApprovedFactGraphSync sync = graphSync;
        if (sync == null || owner.writesSwitchedOff(agentId)) {
            return;
        }
        try {
            owner.jdbcTemplate.query(
                    // codeql[java/sql-injection]
                    "SELECT subject_key, content FROM "
                            + AgentMemorySchemaSupport.qualifiedTableName()
                            + " WHERE id = ?",
                    rs -> {
                        sync.agentFactApproved(
                                agentId,
                                rs.getString("subject_key"),
                                memoryId,
                                rs.getString("content"));
                    },
                    memoryId);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not offer approved memory {} to the graph; the approval stands",
                    memoryId,
                    e);
        }
    }

    void forget(UUID agentId, UUID memoryId) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        owner.jdbcTemplate.update(
                // codeql[java/sql-injection]
                "DELETE FROM " + table + " WHERE id = ? AND agent_id = ?", memoryId, agentId);
    }

    /** Deletes one of this agent's memories only if it is about {@code subjectKey}. */
    boolean forget(UUID agentId, UUID memoryId, String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        return owner.jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM "
                                + table
                                + " WHERE id = ? AND agent_id = ? AND subject_key = ?",
                        memoryId,
                        agentId,
                        subjectKey)
                > 0;
    }

    /**
     * Retention purge (DPDP-oriented controls): deletes up to {@code limit} rows created before
     * {@code cutoff} in the current tenant's schema, oldest first, and returns how many went. The
     * caller loops until it returns less than {@code limit}.
     */
    int purgeCreatedBefore(java.time.Instant cutoff, int limit) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        int deleted =
                owner.jdbcTemplate.update(
                        // codeql[java/sql-injection]
                        "DELETE FROM "
                                + table
                                + " WHERE id IN (SELECT id FROM "
                                + table
                                + " WHERE created_at < ? ORDER BY created_at LIMIT ?)",
                        java.sql.Timestamp.from(cutoff),
                        limit);
        log.info("Retention purge removed {} agent-memory rows older than {}", deleted, cutoff);
        return deleted;
    }

    /**
     * #815 Governance follow-up (PII handling review / data-subject erasure) - deletes every memory
     * this TENANT's agents hold about one subject, across ALL agents, not just whichever agent
     * currently handles that subject's conversations. Necessary because {@code subjectKey} scoping
     * is {@code (agentId, subjectKey)}, and {@code ChannelSessionService#rebindAgent} means the
     * same real person may have left memories under more than one agent over time - a single-agent
     * {@link #forget} sweep would silently leave those behind. Returns the number of rows deleted,
     * purely for the caller to log/report - callers should not branch on it.
     */
    int forgetAllForSubject(String subjectKey) {
        String table = AgentMemorySchemaSupport.qualifiedTableName();
        int deleted =
                // codeql[java/sql-injection]
                owner.jdbcTemplate.update(
                        "DELETE FROM " + table + " WHERE subject_key = ?", subjectKey);
        log.info(
                "Erased {} agent-memory rows for subject key (data-subject erasure request)",
                deleted);
        return deleted;
    }
}
