package com.agenticai.core.graphmemory;

import static com.agenticai.core.graphmemory.GraphMemoryAgtype.parseAgtypeInt;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.cypherCall;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.sanitize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Vertex/edge write path for {@link GraphMemoryService}, extracted to keep that class under the
 * file-size cap. All methods take an already-AGE-ready {@link Statement} (see {@code
 * GraphMemoryService#onAge}); they open no connections themselves.
 */
final class GraphMemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(GraphMemoryWriter.class);

    private static final String ONE_COLUMN = "v ag_catalog.agtype";

    private GraphMemoryWriter() {}

    /**
     * Temporal validity (P1 developer-extensibility, Zep/Graphiti-style): edges carry {@code
     * valid_from} (epoch-ms when the fact became true) and {@code valid_to} (epoch-ms when it
     * stopped being true - set by supersede/forget, never on a plain write). Both are plain edge
     * properties on the schemaless agtype - no migration, and every edge written before the
     * temporal change simply has neither property, which the recall filters treat as "still valid"
     * ({@code valid_to} absent) / "always was" ({@code valid_from} absent), so old rows remain
     * correct forever.
     *
     * <p>Three statements, not one {@code MERGE}: (1) is there already an OPEN edge for this exact
     * fact? then nothing to do (idempotent); (2) {@code MERGE} the two vertices; (3) {@code CREATE}
     * the edge with its {@code valid_from}. A single {@code MERGE (a)-[r {type}]->(b)} would also
     * match a CLOSED edge and silently resurrect nothing - the fact would stay invalid forever.
     * Verified on real AGE. The open-edge check compares names case-insensitively.
     */
    static void writeTriple(
            Statement st,
            String graphName,
            String tierStr,
            String subjectKeyStr,
            GraphTriple triple,
            long now)
            throws SQLException {
        if (triple.tripsWriteScreen()) {
            log.warn("Graph triple dropped: write screen tripped (injection phrase or credential)");
            return;
        }
        String subject = sanitize(triple.subject());
        String relation = sanitize(triple.relation().toUpperCase(Locale.ROOT));
        String object = sanitize(triple.object());
        String subjectLower = sanitize(triple.subject().toLowerCase(Locale.ROOT));
        String objectLower = sanitize(triple.object().toLowerCase(Locale.ROOT));

        if (hasOpenEdge(
                st, graphName, tierStr, subjectKeyStr, subjectLower, relation, objectLower)) {
            return;
        }
        st.execute(
                cypherCall(
                        graphName,
                        "MERGE (a:Entity {name: '"
                                + subject
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'}) "
                                + "MERGE (b:Entity {name: '"
                                + object
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'})",
                        ONE_COLUMN));
        st.execute(
                cypherCall(
                        graphName,
                        "MATCH (a:Entity {name: '"
                                + subject
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'}), "
                                + "(b:Entity {name: '"
                                + object
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'}) "
                                + "CREATE (a)-[:RELATION {type: '"
                                + relation
                                + "', valid_from: "
                                + now
                                + "}]->(b)",
                        ONE_COLUMN));
    }

    private static boolean hasOpenEdge(
            Statement st,
            String graphName,
            String tierStr,
            String subjectKeyStr,
            String subjectLower,
            String relation,
            String objectLower)
            throws SQLException {
        String cypher =
                "MATCH (a:Entity {agent_id: '"
                        + tierStr
                        + "', subject_key: '"
                        + subjectKeyStr
                        + "'})"
                        + "-[r:RELATION {type: '"
                        + relation
                        + "'}]->"
                        + "(b:Entity {agent_id: '"
                        + tierStr
                        + "', subject_key: '"
                        + subjectKeyStr
                        + "'}) "
                        + "WHERE toLower(a.name) = '"
                        + subjectLower
                        + "' AND toLower(b.name) = '"
                        + objectLower
                        + "' "
                        + "AND r.valid_to IS NULL RETURN count(r)";
        try (ResultSet rs =
                st.executeQuery(cypherCall(graphName, cypher, "open_edges ag_catalog.agtype"))) {
            return rs != null && rs.next() && parseAgtypeInt(rs.getString("open_edges")) > 0;
        }
    }
}
