package com.agenticai.core.graphmemory;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Text-based parsing of the agtype values Apache AGE hands back for {@link GraphMemoryService}'s
 * queries (the driver returns agtype as text): scalar names lose their quotes, integer scalars are
 * read as ints, and paths are JSON once the {@code ::vertex}/{@code ::edge}/{@code ::path}
 * annotations are stripped. Pure; no database access.
 */
final class GraphMemoryAgtype {

    private GraphMemoryAgtype() {}

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * AGE annotates vertices/edges/paths in agtype text, e.g. {@code {...}::vertex}; JSON once
     * removed.
     */
    private static final Pattern AGTYPE_ANNOTATION =
            Pattern.compile("(?<=[}\\]])::(vertex|edge|path)");

    /**
     * AGE returns a plain-integer agtype scalar (e.g. {@code 3}) for {@code count(n)} - no quotes,
     * unlike a string scalar.
     */
    static int parseAgtypeInt(String agtypeValue) {
        if (agtypeValue == null) {
            return 0;
        }
        String digitsOnly = agtypeValue.trim().replaceAll("[^0-9-]", "");
        return digitsOnly.isEmpty() ? 0 : Integer.parseInt(digitsOnly);
    }

    /**
     * Turns one agtype path ({@code [vertex, edge, vertex, ...]::path}) into the facts on it, in
     * the direction each edge was written, or an empty list when ANY edge on the path is not valid
     * ({@code asOfMillis == null}: not closed; otherwise: valid at that instant). Pure.
     */
    static List<GraphFact> parsePathFacts(String agtypePath, Long asOfMillis)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if (agtypePath == null || agtypePath.isBlank()) {
            return List.of();
        }
        JsonNode path = MAPPER.readTree(AGTYPE_ANNOTATION.matcher(agtypePath).replaceAll(""));
        if (path == null || !path.isArray()) {
            return List.of();
        }
        Map<Long, String> names = new HashMap<>();
        List<JsonNode> edges = new ArrayList<>();
        for (JsonNode element : path) {
            if (element.has("start_id")) {
                edges.add(element);
            } else if (element.has("id")) {
                names.put(
                        element.get("id").asLong(),
                        element.path("properties").path("name").asText(""));
            }
        }
        List<GraphFact> facts = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode props = edge.path("properties");
            if (!validAt(props, asOfMillis)) {
                return List.of();
            }
            String subject = names.get(edge.get("start_id").asLong());
            String object = names.get(edge.get("end_id").asLong());
            String type = props.path("type").asText("");
            facts.add(
                    new GraphFact(
                            subject == null ? "" : subject,
                            type.isEmpty() ? "RELATED_TO" : type,
                            object == null ? "" : object));
        }
        return facts;
    }

    private static boolean validAt(JsonNode edgeProps, Long asOfMillis) {
        JsonNode validFrom = edgeProps.path("valid_from");
        JsonNode validTo = edgeProps.path("valid_to");
        boolean hasFrom = !validFrom.isMissingNode() && !validFrom.isNull();
        boolean hasTo = !validTo.isMissingNode() && !validTo.isNull();
        if (asOfMillis == null) {
            return !hasTo;
        }
        return (!hasFrom || validFrom.asLong() <= asOfMillis)
                && (!hasTo || validTo.asLong() > asOfMillis);
    }

    /**
     * AGE returns agtype-encoded string scalars wrapped in quotes (e.g. {@code "Priya"}) - strips
     * them.
     */
    static String stripAgtypeQuotes(String agtypeValue) {
        if (agtypeValue == null) {
            return "";
        }
        String trimmed = agtypeValue.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }
}
