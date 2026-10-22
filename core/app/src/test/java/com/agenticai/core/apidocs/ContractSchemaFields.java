package com.agenticai.core.apidocs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Resolves the JSONPath-ish field names of a consumer contract ({@code $.a.b}, {@code
 * $.items[*].id}, {@code $[*].id}) against an OpenAPI schema (ADR-0025).
 *
 * <p>Rules, kept identical to {@code web/scripts/api-contract/generate.mjs#schemaHasField}:
 *
 * <ul>
 *   <li>{@code $ref} (components/schemas and components/responses) is followed; a cycle counts as
 *       present.
 *   <li>{@code allOf}, {@code oneOf}, {@code anyOf}: present when any member declares the rest of
 *       the path.
 *   <li>{@code [*]} steps into {@code items}; it is accepted on an untyped schema.
 *   <li>A name must be one of {@code properties}. An object with no {@code properties} (a {@code
 *       Map}, {@code ResponseEntity<?>}, {@code JsonNode}) is free-form: any name is accepted,
 *       because nothing more can be checked. {@link #isOpen} reports that case.
 * </ul>
 */
final class ContractSchemaFields {

    private static final Pattern STEP = Pattern.compile("\\.([A-Za-z0-9_$@-]+)|\\[\\*]");

    private final JsonNode spec;

    ContractSchemaFields(JsonNode spec) {
        this.spec = spec;
    }

    /** Splits {@code $.a.b[*].c} into [a, b, [*], c]; throws on anything else. */
    static List<String> tokens(String field) {
        if (field == null || !field.startsWith("$")) {
            throw new IllegalArgumentException("field must start with $: " + field);
        }
        List<String> out = new ArrayList<>();
        Matcher m = STEP.matcher(field);
        int pos = 1;
        while (pos < field.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException("cannot parse field " + field);
            }
            out.add(m.group(1) != null ? m.group(1) : "[*]");
            pos = m.end();
        }
        return out;
    }

    /** Follows {@code $ref} chains; a missing or cyclic target resolves to an empty schema. */
    JsonNode deref(JsonNode schema) {
        JsonNode s = schema == null ? MissingSchema.EMPTY : schema;
        Set<String> seen = new HashSet<>();
        while (s.has("$ref")) {
            String ref = s.get("$ref").asString();
            if (!seen.add(ref)) {
                return MissingSchema.EMPTY;
            }
            if (ref.startsWith("#/components/schemas/")) {
                s = spec.path("components").path("schemas").path(ref.substring(21));
            } else if (ref.startsWith("#/components/responses/")) {
                s = spec.path("components").path("responses").path(ref.substring(23));
            } else {
                return MissingSchema.EMPTY;
            }
            if (s.isMissingNode()) {
                return MissingSchema.EMPTY;
            }
        }
        return s;
    }

    /** Whether the schema declares the field (free-form objects accept any name). */
    boolean has(JsonNode schema, String field) {
        return has(schema, tokens(field), 0);
    }

    private boolean has(JsonNode schema, List<String> path, int depth) {
        if (depth > 40) {
            return true;
        }
        JsonNode s = deref(schema);
        if (path.isEmpty()) {
            return true;
        }
        for (String key : List.of("allOf", "oneOf", "anyOf")) {
            for (JsonNode member : s.path(key)) {
                if (has(member, path, depth + 1)) {
                    return true;
                }
            }
        }
        String step = path.get(0);
        List<String> rest = path.subList(1, path.size());
        Set<String> types = types(s);
        JsonNode props = s.path("properties");
        if ("[*]".equals(step)) {
            if (types.contains("array") || s.has("items")) {
                return has(s.path("items"), rest, depth + 1);
            }
            return types.isEmpty() && !s.has("properties");
        }
        if (props.isObject() && props.has(step)) {
            return has(props.get(step), rest, depth + 1);
        }
        if (props.isObject() && props.size() > 0) {
            return false;
        }
        return isOpenObject(s, types);
    }

    /** True when the field resolves only because some schema on its path is free-form. */
    boolean isOpen(JsonNode schema, String field) {
        return has(schema, field) && !strictHas(schema, tokens(field), 0);
    }

    private boolean strictHas(JsonNode schema, List<String> path, int depth) {
        if (depth > 40) {
            return true;
        }
        JsonNode s = deref(schema);
        if (path.isEmpty()) {
            return true;
        }
        for (String key : List.of("allOf", "oneOf", "anyOf")) {
            for (JsonNode member : s.path(key)) {
                if (strictHas(member, path, depth + 1)) {
                    return true;
                }
            }
        }
        String step = path.get(0);
        List<String> rest = path.subList(1, path.size());
        if ("[*]".equals(step)) {
            return s.has("items") && strictHas(s.path("items"), rest, depth + 1);
        }
        JsonNode props = s.path("properties");
        return props.has(step) && strictHas(props.get(step), rest, depth + 1);
    }

    private static boolean isOpenObject(JsonNode s, Set<String> types) {
        return (types.isEmpty() || types.contains("object"))
                && !s.has("items")
                && !s.has("allOf")
                && !s.has("oneOf")
                && !s.has("anyOf");
    }

    private static Set<String> types(JsonNode s) {
        Set<String> out = new HashSet<>();
        JsonNode t = s.path("type");
        if (t.isString()) {
            out.add(t.asString());
        } else if (t.isArray()) {
            for (JsonNode x : t) {
                out.add(x.asString());
            }
        }
        return out;
    }

    /**
     * The body schema of a request body or response object: {@code application/json} when declared,
     * else the first media type; null when the object has no content (no body).
     */
    JsonNode bodySchema(JsonNode holder) {
        JsonNode h = deref(holder);
        JsonNode content = h.path("content");
        if (!content.isObject() || content.size() == 0) {
            return null;
        }
        JsonNode json = content.path("application/json");
        if (json.isMissingNode()) {
            Iterator<Map.Entry<String, JsonNode>> it = content.properties().iterator();
            json = it.next().getValue();
        }
        JsonNode schema = json.path("schema");
        return schema.isMissingNode() ? MissingSchema.EMPTY : schema;
    }

    /** An empty JSON object, used where a schema is absent. */
    private static final class MissingSchema {
        static final JsonNode EMPTY = ApiContractRepoSupport.MAPPER.createObjectNode();
    }
}
