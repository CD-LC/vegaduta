package com.agenticai.core.graphmemory;

import static com.agenticai.core.graphmemory.GraphFactTextMatcher.edgesOnlyThisFactSupports;
import static com.agenticai.core.graphmemory.GraphMemoryAgtype.stripAgtypeQuotes;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.buildForgetInvalidateCypher;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.cypherCall;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.sanitize;

import com.agenticai.core.graphmemory.GraphFactTextMatcher.ValidEdge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Paged scan-and-invalidate work behind {@link GraphMemoryService#forgetFactText}, extracted to
 * keep that class under the file-size cap. Runs on an already-AGE-ready {@link Statement}.
 */
final class GraphMemoryForget {

    private GraphMemoryForget() {}

    /**
     * Reads the subject's currently-valid edges in a fixed order ({@code scanPage} rows at a time,
     * at most {@code scanMaxPages} pages, so the result does not depend on row order), then
     * invalidates - {@code valid_to} stamp, never a DELETE - every edge whose two entity names both
     * occur in {@code factText}, unless one of {@code otherFactTexts} names both as well. Returns
     * the number of edges invalidated.
     */
    static int scanAndInvalidate(
            Statement st,
            String graphName,
            String tierStr,
            String subjectKeyStr,
            String factText,
            Collection<String> otherFactTexts,
            GraphMemoryProperties.Forget forget,
            long now)
            throws SQLException {
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

        // All reads first, then the writes: invalidating while paging would shift the offsets.
        List<ValidEdge> edges = new ArrayList<>();
        for (int page = 0; page < forget.getScanMaxPages(); page++) {
            int rows = 0;
            try (ResultSet rs =
                    st.executeQuery(
                            // codeql[java/sql-injection] -- tierStr/subjectKeyStr are sanitize()d
                            // Cypher string content; SKIP/LIMIT are ints.
                            cypherCall(
                                    graphName,
                                    listCypher
                                            + " SKIP "
                                            + (page * forget.getScanPage())
                                            + " LIMIT "
                                            + forget.getScanPage(),
                                    "subject ag_catalog.agtype, relation"
                                            + " ag_catalog.agtype, object"
                                            + " ag_catalog.agtype"))) {
                while (rs != null && rs.next()) {
                    rows++;
                    edges.add(
                            new ValidEdge(
                                    stripAgtypeQuotes(rs.getString("subject")),
                                    stripAgtypeQuotes(rs.getString("relation")),
                                    stripAgtypeQuotes(rs.getString("object"))));
                }
            }
            if (rows < forget.getScanPage()) {
                break;
            }
        }
        int count = 0;
        for (ValidEdge edge : edgesOnlyThisFactSupports(edges, factText, otherFactTexts)) {
            st.execute(
                    // codeql[java/sql-injection] -- buildForgetInvalidateCypher sanitises every
                    // value it interpolates; `now` is a long.
                    cypherCall(
                            graphName,
                            buildForgetInvalidateCypher(tierStr, subjectKeyStr, edge, now),
                            "v ag_catalog.agtype"));
            count++;
        }
        return count;
    }

    static String tierFor(java.util.UUID agentId) {
        return agentId == null
                ? GraphMemoryService.SHARED_AGENT_SENTINEL
                : sanitize(agentId.toString());
    }

    /**
     * Counts then deletes ({@code DETACH DELETE}, which removes every attached edge too) all {@code
     * Entity} vertices for {@code subjectKeyStr} - already sanitize()d by the caller - returning
     * the pre-delete count. A subject's Entity vertex is very likely the endpoint of edges from
     * other subjects' vertices too (shared entities), and this deliberately only removes the
     * deleted subject's OWN vertices (filtered by subject_key): any edge whose OTHER endpoint
     * belongs to a different subject simply loses that one edge, the other subject's own vertex is
     * untouched.
     */
    static int eraseSubjectEdges(Statement st, String graphName, String subjectKeyStr)
            throws SQLException {
        int matched = 0;
        try (ResultSet rs =
                st.executeQuery(
                        // codeql[java/sql-injection] -- every interpolated value is sanitize()d
                        // Cypher string content; the graph name comes from
                        // GraphMemorySchemaSupport.
                        cypherCall(
                                graphName,
                                "MATCH (n:Entity {subject_key: '"
                                        + subjectKeyStr
                                        + "'}) RETURN count(n)",
                                "vertex_count ag_catalog.agtype"))) {
            if (rs != null && rs.next()) {
                matched = GraphMemoryAgtype.parseAgtypeInt(rs.getString("vertex_count"));
            }
        }

        if (matched > 0) {
            st.execute(
                    // codeql[java/sql-injection] -- subjectKeyStr is sanitize()d Cypher string
                    // content; no user input reaches the query raw.
                    cypherCall(
                            graphName,
                            "MATCH (n:Entity {subject_key: '"
                                    + subjectKeyStr
                                    + "'}) DETACH DELETE n",
                            "v ag_catalog.agtype"));
        }
        return matched;
    }
}
