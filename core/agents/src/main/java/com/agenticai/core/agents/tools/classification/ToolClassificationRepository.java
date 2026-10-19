package com.agenticai.core.agents.tools.classification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-schema repository over {@code tool_classification} (V224).
 *
 * <p>Only three read shapes exist here, and the omissions are deliberate: there is
 * no "find by sensitivity" or "find all EGRESS tools" finder, because the
 * authoritative answer to those questions has to include
 * {@link BuiltInToolClassifications} as well as stored rows, so it belongs in
 * {@link ToolClassificationRegistry} where both sources are visible. A finder here
 * that answered half the question would be used as if it answered all of it.
 */
public interface ToolClassificationRepository extends JpaRepository<ToolClassificationEntity, UUID> {

    /** Exact-name lookup - the hot path, hitting the unique index on {@code tool_name}. */
    Optional<ToolClassificationEntity> findByToolName(String toolName);

    /**
     * Batched exact-name lookup, so resolving a whole turn's tool set is one query
     * rather than one per tool.
     *
     * <p>This matters more than it looks: an agent with a large MCP catalog resolves
     * dozens of names per turn, and the per-name version turns a classification
     * check into a visible latency cost - which is the first thing anyone proposes
     * removing. Making the batched shape available is how the gate stays cheap
     * enough to leave on.
     */
    List<ToolClassificationEntity> findByToolNameIn(Collection<String> toolNames);

    /**
     * Only the wildcard rows, longest pattern first.
     *
     * <p>Two things to note. First, {@code LIKE '%*%'} needs no escaping: {@code *}
     * is not a SQL {@code LIKE} metacharacter, and {@code *} cannot occur in a real
     * tool name (see {@link ToolClassificationEntity#getToolName()}), so this
     * selects exactly the pattern rows.
     *
     * <p>Second, ordering by descending length is a <b>heuristic</b> for
     * "most specific first", not a proof of it - {@code mcp__github__*} is longer
     * and more specific than {@code mcp__*}, which is the case that matters in
     * practice, but two patterns of equal length that both match one tool name
     * resolve in an unspecified order. That ambiguity is a misconfiguration rather
     * than something to encode a tie-break for, and the drift report is where it
     * should be surfaced. Kept as an explicit {@code ORDER BY} anyway so the
     * resolution is at least stable across calls rather than dependent on physical
     * row order.
     */
    @Query("SELECT e FROM ToolClassificationEntity e WHERE e.toolName LIKE '%*%' ORDER BY LENGTH(e.toolName) DESC")
    List<ToolClassificationEntity> findPatternRows();

    /** Everything, for the console's classification table and the drift report. */
    List<ToolClassificationEntity> findAllByOrderByToolNameAsc();

    /**
     * Atomic upsert on {@code uq_tool_classification_tool_name}.
     *
     * <p>Written as a native {@code INSERT ... ON CONFLICT} for the same reason
     * {@code ApprovalPolicyOverrideRepository#upsert} is: a read-then-write
     * implementation lets two concurrent declarations of the same brand-new tool
     * name race, and the loser dies on the unique constraint inside an
     * already-aborted transaction, which is not retryable. Two sessions declaring
     * the same MCP tool at once is not hypothetical here - it is what happens when
     * two agents in one tenant connect to the same MCP server in the same second.
     *
     * <p>The {@code id} is generated caller-side rather than with
     * {@code gen_random_uuid()} (not guaranteed available on every deployment - see
     * V145's migration comment) and is used only when the row does not yet exist;
     * on conflict the existing row keeps its id, {@code created_at} and
     * {@code created_by}, so the audit trail of who first declared a tool survives
     * every later re-declaration.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO tool_classification (
                id, tool_name, trust, sensitivity, determinism, status,
                required_scope, irreversible, needs_tool_context, source, notes,
                created_by, created_at, updated_at)
            VALUES (
                :id, :toolName, :trust, :sensitivity, :determinism, :status,
                :requiredScope, :irreversible, :needsToolContext, :source, :notes,
                :actor, :now, :now)
            ON CONFLICT (tool_name)
            DO UPDATE SET trust              = EXCLUDED.trust,
                          sensitivity        = EXCLUDED.sensitivity,
                          determinism        = EXCLUDED.determinism,
                          status             = EXCLUDED.status,
                          required_scope     = EXCLUDED.required_scope,
                          irreversible       = EXCLUDED.irreversible,
                          needs_tool_context = EXCLUDED.needs_tool_context,
                          source             = EXCLUDED.source,
                          notes              = EXCLUDED.notes,
                          updated_at         = EXCLUDED.updated_at
            """,
        nativeQuery = true)
    void upsert(
        @Param("id") UUID id,
        @Param("toolName") String toolName,
        @Param("trust") String trust,
        @Param("sensitivity") String sensitivity,
        @Param("determinism") String determinism,
        @Param("status") String status,
        @Param("requiredScope") String requiredScope,
        @Param("irreversible") boolean irreversible,
        @Param("needsToolContext") boolean needsToolContext,
        @Param("source") String source,
        @Param("notes") String notes,
        @Param("actor") String actor,
        @Param("now") Instant now);

    /**
     * Flips an existing row's status without touching its classification.
     *
     * <p>Separate from {@link #upsert} on purpose: quarantining must not require
     * the caller to know or restate the tool's trust/sensitivity/determinism. The
     * caller that quarantines is usually reacting to a manifest change and has no
     * opinion about the classification - forcing it to supply one would mean
     * inventing values, and an invented value written into a security table is
     * worse than the quarantine it was trying to record.
     *
     * @return rows affected; 0 means there was no row, which is itself safe -
     *         a tool with no row is already blocked as
     *         {@link ToolClassificationDecision.Reason#UNCLASSIFIED}
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE ToolClassificationEntity e
           SET e.status = :status, e.notes = :notes, e.updatedAt = :now
         WHERE e.toolName = :toolName
        """)
    int updateStatus(
        @Param("toolName") String toolName,
        @Param("status") String status,
        @Param("notes") String notes,
        @Param("now") Instant now);
}
