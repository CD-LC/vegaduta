package com.agenticai.core.graphmemory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cypher text building for {@link GraphMemoryService}: the {@code ag_catalog.cypher(...)} SQL
 * wrapper, the escaping every interpolated value goes through, and the invalidation statements
 * that close edges (supersede and user forget). Split out of the service so the query-text rules
 * sit in one place.
 *
 * <p>Extracted entity/relation text comes from an LLM, not a user-facing form field, but is still
 * external-origin content. AGE's cypher() function needs the query text as a literal (no
 * bind-parameter support for the inner query string itself), so escaping - not parameterization -
 * is the injection defense here: every interpolated value goes through
 * CypherLiteralEscaper.escapeCypherString (allowlist encoder: invisible/control chars stripped,
 * quotes/backslashes ESCAPED so names like O'Brien survive, '$' encoded away so the surrounding
 * $$-dollar-quoted SQL literal can never be terminated, length-capped). See that class's javadoc
 * for the full rationale and the honestly-stated residual risk. The graph name is the one
 * interpolated value NOT routed through it - it derives from SchemaNameSanitizer via
 * GraphMemorySchemaSupport, which already guarantees [a-z0-9_].
 */
final class GraphMemoryCypher {

    private GraphMemoryCypher() {}

    /**
     * The only shape a graph name may have ({@code <tenant_schema>_kg}; see {@link GraphMemorySchemaSupport}).
     * No upper length bound: a legacy schema name may already be 63 chars, so the derived graph name can exceed
     * NAMEDATALEN-1; Postgres truncates the {@code name}-typed literal itself (as {@code create_graph} did when the
     * graph was made). Only the character class matters for the {@code ag_catalog.cypher('...')} literal.
     */
    private static final Pattern GRAPH_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    static String cypherCall(String graphName, String cypher, String columns) {
        if (graphName == null || !GRAPH_NAME.matcher(graphName).matches()) {
            throw new IllegalArgumentException("Not a plain AGE graph name: '" + graphName + "'");
        }
        return "SELECT * FROM ag_catalog.cypher('" + graphName + "', $$ " + cypher + " $$) AS (" + columns + ")";
    }

    static String buildForgetInvalidateCypher(
        String tierStr, String subjectKeyStr, GraphFactTextMatcher.ValidEdge edge, long now
    ) {
        return "MATCH (a:Entity {name: '" + sanitize(edge.subject()) + "', agent_id: '" + tierStr + "', subject_key: '" + subjectKeyStr + "'})"
            + "-[r:RELATION {type: '" + sanitize(edge.relation()) + "'}]->"
            + "(b:Entity {name: '" + sanitize(edge.object()) + "', agent_id: '" + tierStr + "', subject_key: '" + subjectKeyStr + "'}) "
            + "WHERE r.valid_to IS NULL "
            + "SET r.valid_to = " + now;
    }

    /**
     * Supersede is an INVALIDATION, not a deletion: the stale edge gets {@code valid_to = now}
     * instead of {@code DELETE r}, which is the whole point of temporal memory - "what did we
     * believe about X in March" is answerable because the old edge is still in the graph with its
     * bounds stamped. Only currently-open edges are touched. A stray invalidated edge is still
     * removed by {@link GraphMemoryService#eraseSubject}'s DETACH DELETE, so GDPR erasure is unaffected.
     */
    static String buildSupersedeInvalidateCypher(String agentIdStr, String subjectKeyStr, GraphTriple triple, long now) {
        String subjectLower = sanitize(triple.subject().toLowerCase(Locale.ROOT));
        String relation = sanitize(triple.relation().toUpperCase(Locale.ROOT));
        String objectLower = sanitize(triple.object().toLowerCase(Locale.ROOT));
        return "MATCH (a:Entity {agent_id: '" + agentIdStr + "', subject_key: '" + subjectKeyStr + "'})"
            + "-[r:RELATION {type: '" + relation + "'}]->"
            + "(b:Entity {agent_id: '" + agentIdStr + "', subject_key: '" + subjectKeyStr + "'}) "
            + "WHERE toLower(a.name) = '" + subjectLower + "' AND toLower(b.name) <> '" + objectLower + "' "
            + "AND r.valid_to IS NULL "
            + "SET r.valid_to = " + now;
    }

    static String singleEdgeFilter(Long asOfMillis) {
        return asOfMillis == null
            ? "r.valid_to IS NULL"
            : "(r.valid_from IS NULL OR r.valid_from <= " + asOfMillis + ") "
                + "AND (r.valid_to IS NULL OR r.valid_to > " + asOfMillis + ")";
    }

    /**
     * Delegates to {@link CypherLiteralEscaper#escapeCypherString} - kept as a local one-liner so
     * every interpolation site reads {@code sanitize(...)} at a glance and a future edit can't
     * accidentally interpolate a raw value without the reviewer noticing a missing call. Escapes
     * (never strips) quotes/backslashes, strips invisibles, encodes {@code $}, caps length - see the
     * escaper's javadoc for why this is escaping-not-parameterization and what the residual risk is.
     */
    static String sanitize(String value) {
        return CypherLiteralEscaper.escapeCypherString(value);
    }
}
