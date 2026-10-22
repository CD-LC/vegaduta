package com.agenticai.core.knowledge;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * General equality filter over {@code knowledge_chunks.metadata} (jsonb, migration V192) for {@link
 * KnowledgeSearchService} - before this the only metadata predicate was the hardcoded {@code
 * source_type <> 'image'}.
 *
 * <p>The whole filter compiles to ONE parameterised containment predicate, {@code
 * <alias>.metadata @> ?::jsonb}, whose single bind value is a JSON document built by Jackson from
 * the validated filter map. Nothing a caller supplies - key or value - is ever concatenated into
 * SQL text. {@code @>} is also exactly the operator V192's {@code GIN (metadata jsonb_path_ops)}
 * index serves, so the filter is index-assisted rather than a per-row {@code ->>} evaluation.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Keys must match {@code [a-zA-Z0-9_.-]{1,64}}. A dot addresses a nested object: {@code
 *       "author.team": "legal"} matches {@code {"author": {"team": "legal"}}}. Empty path segments
 *       ({@code "a..b"}, {@code ".a"}) and a key that is both a leaf and a parent of another key
 *       ({@code "a"} with {@code "a.b"}) are rejected.
 *   <li>Values must be a string, number or boolean. Equality is jsonb equality - type-sensitive
 *       ({@code "5"} does not match {@code 5}). Null, arrays and objects are rejected: containment
 *       semantics for those ("array contains element") aren't equality and would surprise.
 *   <li>At most {@link #MAX_KEYS} keys - a bound on the work one request can ask of the index, not
 *       a correctness limit.
 * </ul>
 *
 * Violations throw {@link IllegalArgumentException} (the global handler's 400).
 */
public final class KnowledgeMetadataFilter {

    public static final int MAX_KEYS = 16;
    static final Pattern KEY_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** A SQL predicate fragment (leading {@code " AND "}) plus its one bind value. */
    public record SqlPredicate(String sql, String jsonParam) {}

    private KnowledgeMetadataFilter() {}

    /**
     * @param columnRef the metadata column reference, e.g. {@code "metadata"} or {@code
     *     "c.metadata"} - a code constant from the caller, never request input
     * @return null when {@code filter} is null/empty (no predicate at all)
     */
    public static SqlPredicate toSql(Map<String, Object> filter, String columnRef) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        return new SqlPredicate(" AND " + columnRef + " @> ?::jsonb", toContainmentJson(filter));
    }

    static String toContainmentJson(Map<String, Object> filter) {
        if (filter.size() > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "metadataFilter supports at most "
                            + MAX_KEYS
                            + " keys (got "
                            + filter.size()
                            + ")");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            if (key == null || !KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "metadataFilter key must match [a-zA-Z0-9_.-]{1,64} (got "
                                + describeKey(key)
                                + ")");
            }
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "metadataFilter value for \""
                                + key
                                + "\" must be a string, number or boolean");
            }
            put(root, key.split("\\.", -1), value, key);
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Only String/Number/Boolean leaves in nested LinkedHashMaps - not reachable in
            // practice.
            throw new IllegalArgumentException("metadataFilter could not be serialized", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String[] path, Object value, String fullKey) {
        Map<String, Object> node = root;
        for (int i = 0; i < path.length; i++) {
            String segment = path[i];
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(
                        "metadataFilter key \"" + fullKey + "\" has an empty path segment");
            }
            boolean leaf = i == path.length - 1;
            Object existing = node.get(segment);
            if (leaf) {
                if (existing != null) {
                    throw new IllegalArgumentException(
                            "metadataFilter key \"" + fullKey + "\" conflicts with another key");
                }
                node.put(segment, value);
            } else {
                if (existing == null) {
                    Map<String, Object> child = new LinkedHashMap<>();
                    node.put(segment, child);
                    node = child;
                } else if (existing instanceof Map) {
                    node = (Map<String, Object>) existing;
                } else {
                    throw new IllegalArgumentException(
                            "metadataFilter key \"" + fullKey + "\" conflicts with another key");
                }
            }
        }
    }

    private static String describeKey(String key) {
        if (key == null) {
            return "null";
        }
        // Don't echo an arbitrarily long/odd key back verbatim.
        return key.length() > 64
                ? "a " + key.length() + "-character key"
                : "\"" + key.replaceAll("[^\\x20-\\x7E]", "?") + "\"";
    }
}
