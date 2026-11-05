package com.agenticai.core.graphmemory;

import static com.agenticai.core.graphmemory.GraphFactTextMatcher.edgesOnlyThisFactSupports;
import static com.agenticai.core.graphmemory.GraphMemoryAgtype.parseAgtypeInt;
import static com.agenticai.core.graphmemory.GraphMemoryAgtype.stripAgtypeQuotes;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.buildForgetInvalidateCypher;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.cypherCall;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.sanitize;

import com.agenticai.core.graphmemory.GraphFactTextMatcher.ValidEdge;
import com.agenticai.core.tenancy.MissingTenantContextException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subject-erasure and user-initiated forget operations for {@link GraphMemoryService}, extracted so
 * the service stays under the file-size cap. Package-private: callers still go through {@link
 * GraphMemoryService#eraseSubject} / {@link GraphMemoryService#forgetFactText}.
 */
final class GraphMemoryErasure {

    private static final Logger log = LoggerFactory.getLogger(GraphMemoryErasure.class);

    private final GraphMemoryService owner;

    GraphMemoryErasure(GraphMemoryService owner) {
        this.owner = owner;
    }

    /**
     * #815 Governance follow-up (PII handling review) - GDPR Article 17 "right to erasure" for a
     * single data subject's graph-memory facts. Closes the gap {@code
     * ChannelSessionService#eraseDataSubject}'s own javadoc already flags in this codebase ("Does
     * NOT touch: GraphMemoryService-extracted entities... see this method's follow-up note in
     * AI-GOVERNANCE-ASSESSMENT.md") - see this method's own callers for how the two are wired
     * together.
     *
     * <p>Scoped by {@code subject_key} ALONE, across every agent this tenant has, not just one -
     * the same "every agent, not just the currently-bound one" reasoning {@code
     * AgentMemoryService#forgetAllForSubject} already documents: a data subject who has talked to
     * three different agents has three different agent-scoped memories of them, and an erasure
     * request is about the PERSON, not about any one agent relationship.
     *
     * <p><b>Unlike every other method on this class, this one does NOT fail open.</b> {@link
     * #remember}/{@link #recallRelated}/{@link #supersede} degrade gracefully because graph memory
     * is supplementary context to a chat turn - a write or read failing there must never break the
     * conversation. An erasure request is different: silently swallowing a failure here would mean
     * reporting "erased" (or simply not surfacing an error) while the subject's data may still sit
     * in the graph - exactly the failure mode {@code TenantDataErasureService#eraseCurrentTenant}
     * already establishes the precedent against (its own {@code DROP SCHEMA} failure is rethrown,
     * not logged-and-swallowed, for the same reason). Any failure here propagates as an unchecked
     * exception.
     *
     * @return how many {@code Entity} vertices (and, via {@code DETACH DELETE}, every edge attached
     *     to them) were removed. Zero is a legitimate outcome - this subject was never written to
     *     the graph - not an error.
     */
    int eraseSubject(String subjectKey) {
        String graphName = GraphMemorySchemaSupport.currentGraphName();
        String subjectKeyStr = sanitize(subjectKey);

        Integer erased =
                owner.onAge(
                        st -> {
                            int matched = 0;
                            try (ResultSet rs =
                                    st.executeQuery(
                                            cypherCall(
                                                    graphName,
                                                    "MATCH (n:Entity {subject_key: '"
                                                            + subjectKeyStr
                                                            + "'}) RETURN count(n)",
                                                    "vertex_count ag_catalog.agtype"))) {
                                if (rs != null && rs.next()) {
                                    matched = parseAgtypeInt(rs.getString("vertex_count"));
                                }
                            }

                            if (matched > 0) {
                                // DETACH DELETE removes the vertex AND every edge attached to it in
                                // one
                                // statement - a subject's Entity vertex is very likely the endpoint
                                // of edges
                                // from other subjects' vertices too (shared entities, e.g. a
                                // company both
                                // Priya and Raj are linked to), and this deliberately only removes
                                // the
                                // deleted subject's OWN vertices (already filtered by subject_key
                                // above) -
                                // any edge whose OTHER endpoint belongs to a different subject
                                // simply loses
                                // that one edge, the other subject's own vertex is untouched.
                                st.execute(
                                        cypherCall(
                                                graphName,
                                                "MATCH (n:Entity {subject_key: '"
                                                        + subjectKeyStr
                                                        + "'}) DETACH DELETE n",
                                                GraphMemoryService.ONE_COLUMN));
                            }
                            return matched;
                        });

        int erasedCount = erased == null ? 0 : erased;
        // No subject key in the line: this is reachable by every user (self-service forget-all) and
        // the key is an identity.
        log.info(
                "Erased {} graph-memory entit{} for one subject across every agent",
                erasedCount,
                erasedCount == 1 ? "y" : "ies");
        return erasedCount;
    }

    /**
     * Fail-open twin of {@link GraphMemorySchemaSupport#currentGraphName()} for the supplementary
     * read/write paths: no tenant schema on this thread means "no graph context", logged, never a
     * broken chat turn - and never a fallback to a shared graph. {@link #eraseSubject} deliberately
     * does NOT use this (erasure fails closed).
     */
    static String graphNameOrNull() {
        try {
            return GraphMemorySchemaSupport.currentGraphName();
        } catch (MissingTenantContextException e) {
            log.warn("Graph memory skipped - {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // User-initiated forget sync (app.graph-memory.user-forget-sync.enabled,
    // default OFF).
    // ------------------------------------------------------------------

    /**
     * As {@link #forgetFactText(String, UUID, String, Collection)} with no other facts to protect.
     */
    int forgetFactText(String subjectKey, UUID agentId, String factText) {
        return forgetFactText(subjectKey, agentId, factText, List.of());
    }

    /**
     * Best-effort graph side of "the user deleted (or rewrote) this remembered fact".
     *
     * <p>Edges carry no fact id, so an edge cannot be tied to the one fact that produced it. This
     * invalidates - {@code valid_to} stamp, the same temporal mechanism {@link #supersede} uses,
     * never a DELETE - every currently-valid edge in the fact's own tier whose two entity names
     * both occur in {@code factText}, unless one of {@code otherFactTexts} (the user's remaining
     * facts in that tier) names both as well. No LLM call: the match is on the stored names, so it
     * is deterministic and costs one read plus one write per edge.
     *
     * <p>The subject's currently-valid edges are read in a fixed order, {@value
     * #GraphMemoryService.FORGET_SCAN_PAGE} at a time, until a short page (at most {@value
     * #GraphMemoryService.FORGET_SCAN_MAX_PAGES} pages), so the result does not depend on which
     * rows the database happened to return first.
     *
     * <p>Known limits: an edge whose entity names were paraphrased by the extractor (not present
     * verbatim in the fact text) is left in place, and names containing a backslash are skipped.
     * When the fact was edited rather than deleted, nothing is written for the new text - the graph
     * learns it again only when an agent next saves it. {@link #eraseSubject} remains the complete
     * erasure path.
     *
     * <p>Fails open like {@link #remember}: any failure is logged and reported as 0.
     *
     * @param agentId the agent whose private tier the fact lives in, or {@code null} for the
     *     tenant-shared tier ({@link #GraphMemoryService.SHARED_AGENT_SENTINEL})
     * @return how many edges were invalidated
     */
    int forgetFactText(
            String subjectKey, UUID agentId, String factText, Collection<String> otherFactTexts) {
        if (subjectKey == null || subjectKey.isBlank() || factText == null || factText.isBlank()) {
            return 0;
        }
        Collection<String> others = otherFactTexts == null ? List.of() : otherFactTexts;
        try {
            String graphName = GraphMemorySchemaSupport.currentGraphName();
            String tierStr =
                    agentId == null
                            ? GraphMemoryService.SHARED_AGENT_SENTINEL
                            : sanitize(agentId.toString());
            String subjectKeyStr = sanitize(subjectKey);
            String listCypher =
                    "MATCH (a:Entity)-[r:RELATION]->(b:Entity) "
                            + "WHERE a.agent_id = '"
                            + tierStr
                            + "' AND a.subject_key = '"
                            + subjectKeyStr
                            + "' "
                            + "AND b.agent_id = '"
                            + tierStr
                            + "' AND b.subject_key = '"
                            + subjectKeyStr
                            + "' "
                            + "AND r.valid_to IS NULL "
                            + "RETURN a.name, r.type, b.name ORDER BY a.name, r.type, b.name";

            Integer invalidated =
                    owner.onAge(
                            st -> {
                                // All reads first, then the writes: invalidating while paging would
                                // shift the offsets.
                                List<ValidEdge> edges = new ArrayList<>();
                                for (int page = 0;
                                        page < owner.properties.getForget().getScanMaxPages();
                                        page++) {
                                    int rows = 0;
                                    try (ResultSet rs =
                                            st.executeQuery(
                                                    cypherCall(
                                                            graphName,
                                                            listCypher
                                                                    + " SKIP "
                                                                    + (page
                                                                            * owner.properties
                                                                                    .getForget()
                                                                                    .getScanPage())
                                                                    + " LIMIT "
                                                                    + owner.properties
                                                                            .getForget()
                                                                            .getScanPage(),
                                                            "subject ag_catalog.agtype, relation ag_catalog.agtype, object ag_catalog.agtype"))) {
                                        while (rs != null && rs.next()) {
                                            rows++;
                                            edges.add(
                                                    new ValidEdge(
                                                            stripAgtypeQuotes(
                                                                    rs.getString("subject")),
                                                            stripAgtypeQuotes(
                                                                    rs.getString("relation")),
                                                            stripAgtypeQuotes(
                                                                    rs.getString("object"))));
                                        }
                                    }
                                    if (rows < owner.properties.getForget().getScanPage()) {
                                        break;
                                    }
                                }
                                long now = owner.nowMillis();
                                int count = 0;
                                for (ValidEdge edge :
                                        edgesOnlyThisFactSupports(edges, factText, others)) {
                                    st.execute(
                                            cypherCall(
                                                    graphName,
                                                    buildForgetInvalidateCypher(
                                                            tierStr, subjectKeyStr, edge, now),
                                                    GraphMemoryService.ONE_COLUMN));
                                    count++;
                                }
                                return count;
                            });
            int count = invalidated == null ? 0 : invalidated;
            log.debug("Invalidated {} graph edge(s) after a user-initiated fact removal", count);
            return count;
        } catch (Exception ex) {
            log.warn("Graph memory forget-sync failed - vector memory is unaffected", ex);
            return 0;
        }
    }
}
