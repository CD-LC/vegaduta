package com.agenticai.core.graphmemory;

import static com.agenticai.core.graphmemory.GraphMemoryAgtype.parsePathFacts;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.cypherCall;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Path-row collection behind {@link GraphMemoryService}'s recall methods, extracted to keep that
 * class under the file-size cap. Runs on an already-AGE-ready {@link Statement}.
 */
final class GraphMemoryRecall {

    private GraphMemoryRecall() {}

    /**
     * Executes {@code cypher} (a {@code RETURN p} path query) and parses each row into {@link
     * GraphFact}s via {@link GraphMemoryAgtype#parsePathFacts}, in row order, until {@code
     * maxFacts} are collected. AGE has no list predicate for multi-hop paths, so the validity
     * window is applied in Java by the parser.
     */
    static List<GraphFact> collectFacts(
            Statement st, String graphName, String cypher, int maxFacts, Long asOfMillis)
            throws SQLException {
        Set<GraphFact> result = new LinkedHashSet<>();
        try (ResultSet rs = st.executeQuery(cypherCall(graphName, cypher, "p ag_catalog.agtype"))) {
            while (rs != null && rs.next() && result.size() < maxFacts) {
                try {
                    for (GraphFact fact : parsePathFacts(rs.getString("p"), asOfMillis)) {
                        if (result.size() < maxFacts) {
                            result.add(fact);
                        }
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new SQLException(
                            "Unparseable agtype path: " + ex.getOriginalMessage(), ex);
                }
            }
        }
        return new ArrayList<>(result);
    }
}
