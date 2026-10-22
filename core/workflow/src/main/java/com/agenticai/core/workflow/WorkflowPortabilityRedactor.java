package com.agenticai.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public bridge from the workflow portability feature ({@code
 * com.agenticai.core.workflow.portability}) to {@link WorkflowEditRedaction}, which is
 * package-private on purpose (it is the AI generator's edit-mode secret scrubber). Portability
 * reuses its credential detection - the key-name rules, the Bearer/Basic and well-known key shapes,
 * credential-named URL parameters - rather than keeping a second, drifting list, and only changes
 * how a hit is spelled: {@code [[CREDENTIAL_<prefix>n]]} (a named placeholder a person recognises
 * in a file) instead of the prompt-only {@code [[REDACTED_n]]}.
 *
 * <p>A second pass then closes two gaps that heuristic leaves, because a file is something a person
 * keeps or shares (a prompt is not): credentials embedded in a URL's userinfo ({@code
 * https://user:password@host/}), and - for export only, see {@link #redact(ObjectMapper, JsonNode,
 * String, boolean)} - headers and query parameters whose NAME contains the word key, auth or
 * subscription ({@code Ocp-Apim-Subscription-Key}, {@code X-Api-Auth}) without spelling out "api
 * key" or "token".
 *
 * <p>Pure and static. Anything containing a {@code {{ }}} expression (so every {@code
 * {{secret.NAME}}} vault reference) is left exactly as it is: a vault reference names a secret, it
 * does not contain one.
 */
public final class WorkflowPortabilityRedactor {

    /** {@code scheme://user:password@}: group 1 scheme, group 2 the user:password part. */
    private static final Pattern USERINFO =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]{1,20}://)([^/\\s@\"'?#]+:[^/\\s@\"'?#]+)@");

    private static final Pattern QUERY_PARAM =
            Pattern.compile("([?&])([A-Za-z0-9_.-]{1,64})=([^&\\s\"{}\\[\\]#]{3,})");

    /** Containers whose children are request headers / query parameters / cookies. */
    private static final Pattern HEADERISH =
            Pattern.compile("(?i)^(headers?|query|query_?params?|params|parameters|cookies)$");

    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Set<String> SENSITIVE_TOKENS =
            Set.of("key", "keys", "auth", "subscription", "apikey");

    private WorkflowPortabilityRedactor() {}

    /**
     * @param json the tree with every detected credential replaced by a {@code [[CREDENTIAL_..]]}
     *     placeholder
     * @param count how many distinct credential values were replaced
     */
    public record Result(JsonNode json, int count) {}

    /**
     * Redacts {@code tree}. {@code prefix} keeps placeholders from different callers distinct
     * inside one document (for example a node id), so {@code [[CREDENTIAL_n3_1]]} and {@code
     * [[CREDENTIAL_n7_1]]} never collide.
     *
     * @throws IllegalArgumentException when the tree cannot be serialised or re-read
     */
    public static Result redact(ObjectMapper mapper, JsonNode tree, String prefix) {
        return redact(mapper, tree, prefix, false);
    }

    /**
     * As {@link #redact(ObjectMapper, JsonNode, String)}. With {@code exportNames} the second pass
     * also treats a header, query or cookie whose name contains key, auth or subscription as
     * sensitive - used for export, where the file may be shared and a missed key costs more than an
     * over-redacted one.
     *
     * @throws IllegalArgumentException when the tree cannot be serialised or re-read
     */
    public static Result redact(
            ObjectMapper mapper, JsonNode tree, String prefix, boolean exportNames) {
        if (tree == null || tree.isNull() || tree.isMissingNode()) {
            return new Result(tree, 0);
        }
        try {
            String json = mapper.writeValueAsString(tree);
            WorkflowEditRedaction.Redacted redacted = WorkflowEditRedaction.redact(mapper, json);
            int count = redacted.secrets().size();
            String safePrefix = prefix == null ? "" : prefix;
            JsonNode work;
            if (count == 0) {
                work = tree.deepCopy();
            } else {
                String out = redacted.json();
                // Placeholders are numbered 1..count (see WorkflowEditRedaction.Collector); each
                // one ends in "]]", so
                // [[REDACTED_1]] can never match a prefix of [[REDACTED_10]].
                for (int i = 1; i <= count; i++) {
                    out =
                            out.replace(
                                    "[[REDACTED_" + i + "]]",
                                    "[[CREDENTIAL_" + safePrefix + i + "]]");
                }
                work = mapper.readTree(out);
            }
            Extras extras = new Extras(safePrefix, count + 1, exportNames);
            JsonNode result = extras.walk(work, null, false, 0);
            int total = count + extras.added();
            return total == 0 ? new Result(tree, 0) : new Result(result, total);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "The workflow could not be scanned for credentials.", e);
        }
    }

    /** Same as {@link #redact} for graph JSON text; the text must be a JSON document. */
    public static Result redactText(ObjectMapper mapper, String graphJson, String prefix) {
        return redactText(mapper, graphJson, prefix, false);
    }

    /** Same as {@link #redact(ObjectMapper, JsonNode, String, boolean)} for graph JSON text. */
    public static Result redactText(
            ObjectMapper mapper, String graphJson, String prefix, boolean exportNames) {
        try {
            return redact(
                    mapper,
                    mapper.readTree(graphJson == null ? "{}" : graphJson),
                    prefix,
                    exportNames);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "The workflow graph is not valid JSON and cannot be exported.", e);
        }
    }

    /**
     * True for a name that contains the word key, auth or subscription (camelCase and separators
     * split words).
     */
    static boolean exportSensitiveName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (WorkflowEditRedaction.sensitiveKey(name)) {
            return true;
        }
        for (String token :
                CAMEL.matcher(name)
                        .replaceAll("$1 $2")
                        .toLowerCase(Locale.ROOT)
                        .split("[^a-z0-9]+")) {
            if (SENSITIVE_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // ---- the second pass
    // ---------------------------------------------------------------------------------

    private static final class Extras {
        final String prefix;
        final boolean names;
        int next;
        final Map<String, String> byOriginal = new LinkedHashMap<>();

        Extras(String prefix, int firstNumber, boolean names) {
            this.prefix = prefix;
            this.next = firstNumber;
            this.names = names;
        }

        int added() {
            return byOriginal.size();
        }

        String marker(String original) {
            return byOriginal.computeIfAbsent(
                    original, o -> "[[CREDENTIAL_" + prefix + (next++) + "]]");
        }

        JsonNode walk(JsonNode node, String key, boolean headerCtx, int depth) {
            if (node == null || depth > 24) {
                return node;
            }
            if (node.isObject()) {
                ObjectNode object = (ObjectNode) node;
                // A {name, value} (or {key, value}) pair: the NAME decides whether VALUE is
                // sensitive.
                String pairField =
                        object.path("name").isTextual()
                                ? "name"
                                : object.path("key").isTextual() ? "key" : null;
                if (pairField != null && !object.path("value").isTextual()) {
                    pairField = null;
                }
                String pairName = pairField == null ? null : object.path(pairField).asText();
                List<String> fields = new ArrayList<>();
                object.fieldNames().forEachRemaining(fields::add);
                for (String field : fields) {
                    JsonNode child = object.get(field);
                    if (child != null && child.isTextual()) {
                        boolean isPairName = pairField != null && field.equals(pairField);
                        String nameForCheck =
                                "value".equals(field) && pairName != null ? pairName : field;
                        object.set(
                                field,
                                text(nameForCheck, child.asText(), headerCtx && !isPairName));
                    } else {
                        object.set(
                                field,
                                walk(
                                        child,
                                        field,
                                        headerCtx || HEADERISH.matcher(field).matches(),
                                        depth + 1));
                    }
                }
                return object;
            }
            if (node.isArray()) {
                ArrayNode array = (ArrayNode) node;
                for (int i = 0; i < array.size(); i++) {
                    JsonNode child = array.get(i);
                    array.set(
                            i,
                            child != null && child.isTextual()
                                    ? text(key, child.asText(), headerCtx)
                                    : walk(child, key, headerCtx, depth + 1));
                }
                return array;
            }
            if (node.isTextual()) {
                return text(key, node.asText(), headerCtx);
            }
            return node;
        }

        private JsonNode text(String key, String value, boolean nameCheck) {
            return TextNode.valueOf(redactString(key, value, nameCheck));
        }

        private String redactString(String key, String value, boolean nameCheck) {
            if (value.isBlank() || value.contains("{{")) {
                return value;
            }
            boolean alreadyMarked =
                    value.contains("[[CREDENTIAL_") || value.contains("[[REDACTED_");
            if (names && nameCheck && !alreadyMarked && exportSensitiveName(key)) {
                return marker(value);
            }
            String out = value;
            Matcher userinfo = USERINFO.matcher(out);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            boolean changed = false;
            while (userinfo.find()) {
                String credential = userinfo.group(2);
                if (credential.contains("[[") || credential.contains("{{")) {
                    continue;
                }
                sb.append(out, last, userinfo.start(2)).append(marker(credential));
                last = userinfo.end(2);
                changed = true;
            }
            if (changed) {
                out = sb.append(out.substring(last)).toString();
            }
            if (names) {
                Matcher query = QUERY_PARAM.matcher(out);
                sb = new StringBuilder();
                last = 0;
                changed = false;
                while (query.find()) {
                    String credential = query.group(3);
                    if (credential.contains("[[") || !exportSensitiveName(query.group(2))) {
                        continue;
                    }
                    sb.append(out, last, query.start(3)).append(marker(credential));
                    last = query.end(3);
                    changed = true;
                }
                if (changed) {
                    out = sb.append(out.substring(last)).toString();
                }
            }
            return out;
        }
    }
}
