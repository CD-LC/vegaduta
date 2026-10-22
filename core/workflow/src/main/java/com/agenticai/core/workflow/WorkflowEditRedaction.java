package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Keeps literal credentials out of the AI generator's edit-mode prompt. Edit mode sends the whole
 * canvas graph to the LLM provider; a user may have typed a real API key into an http header, a
 * body, a URL or a tool's args instead of using a vault reference. {@link #redact} swaps each such
 * value for a {@code [[REDACTED_n]]} placeholder before the graph is serialised into the prompt,
 * and {@link #restore} puts the originals back into the graph the model returned, so the canvas
 * never loses them and the provider never sees them.
 *
 * <p>What counts as a credential (best effort, deliberately biased towards redacting):
 *
 * <ul>
 *   <li>the whole value of a field whose key - or, for a {@code {name, value}} pair such as an http
 *       header, whose {@code name} - looks sensitive (authorization, api key, token, secret,
 *       password, cookie, credential, signature);
 *   <li>inside any other string: the credential after {@code Bearer} / {@code Basic}, well-known
 *       key shapes (sk-..., ghp_..., xox?-..., AKIA..., AIza..., a JWT), credential-named URL query
 *       parameters, and credential-named string fields of embedded JSON (a tool's argsJson).
 * </ul>
 *
 * Anything containing a {@code {{ }}} expression (so every {@code {{secret.NAME}}} vault reference)
 * is left exactly as it is. Pure and static; unit-testable without Spring.
 */
final class WorkflowEditRedaction {

    /** Appended to the edit-mode user message only when something was redacted. */
    static final String PROMPT_NOTE =
            "\n\nValues shown as [[REDACTED_1]], [[REDACTED_2]], ... are hidden "
                    + "credentials. Copy each placeholder back EXACTLY as written wherever its value is kept; never invent, "
                    + "alter or explain one.";

    private static final Pattern SENSITIVE_NAME =
            Pattern.compile(
                    "(?i)(authorization|api[-_ ]?key|token|secret|password|passwd|cookie|credential|signature)");

    /** Keys that contain a sensitive word but hold configuration, not a credential. */
    private static final Pattern NOT_A_CREDENTIAL_KEY =
            Pattern.compile("(?i)^(max_?tokens?|token_?budget|toolName)$");

    /** Each pattern's group 1 is the credential itself. */
    private static final List<Pattern> EMBEDDED =
            List.of(
                    Pattern.compile("(?i)\\b(?:bearer|basic)\\s+([A-Za-z0-9._~+/=-]{16,})"),
                    Pattern.compile("\\b((?:sk|pk|rk)[-_][A-Za-z0-9_-]{16,})"),
                    Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{20,})"),
                    Pattern.compile("\\b(xox[abprs]-[A-Za-z0-9-]{10,})"),
                    Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"),
                    Pattern.compile("\\b(AIza[0-9A-Za-z_-]{30,})"),
                    Pattern.compile(
                            "\\b(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})"),
                    Pattern.compile(
                            "(?i)[?&](?:api[-_]?key|key|token|access_token|secret|password|sig|signature)=([^&\\s\"{}\\[\\]]{6,})"),
                    Pattern.compile(
                            "(?i)\"(?:authorization|api[-_]?key|token|access_token|secret|client_secret|password)\"\\s*:\\s*\"([^\"{}\\[\\]]{6,})\""));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[\\[REDACTED_\\d+]]");

    private WorkflowEditRedaction() {}

    /**
     * @param json the graph JSON safe to send to the model (the input itself when nothing was
     *     redacted)
     * @param secrets placeholder -> original text, in first-seen order
     */
    record Redacted(String json, Map<String, String> secrets) {

        static final Redacted NONE = new Redacted("", Map.of());
    }

    /** Never throws: JSON that cannot be read is returned unchanged with nothing redacted. */
    static Redacted redact(ObjectMapper mapper, String graphJson) {
        if (graphJson == null || graphJson.isEmpty()) {
            return new Redacted("", Map.of());
        }
        try {
            JsonNode root = mapper.readTree(graphJson);
            Collector collector = new Collector();
            JsonNode out = walk(root, null, collector, 0);
            if (collector.byPlaceholder.isEmpty()) {
                return new Redacted(graphJson, Map.of());
            }
            return new Redacted(
                    mapper.writeValueAsString(out), Map.copyOf(collector.byPlaceholder));
        } catch (Exception e) {
            return new Redacted(graphJson, Map.of());
        }
    }

    /**
     * Puts the originals back wherever the model kept a placeholder. A placeholder the model
     * dropped is simply gone (the user removed or replaced that node); one it invented stays as
     * inert text.
     *
     * @throws WorkflowValidationException when the restored graph cannot be rebuilt - the caller
     *     must not hand back a graph that still carries placeholders in place of the user's values
     */
    static WorkflowGraphDto restore(
            ObjectMapper mapper, WorkflowGraphDto graph, Redacted redacted) {
        if (graph == null || redacted == null || redacted.secrets().isEmpty()) {
            return graph;
        }
        try {
            String json = mapper.writeValueAsString(graph);
            for (Map.Entry<String, String> secret : redacted.secrets().entrySet()) {
                if (json.contains(secret.getKey())) {
                    // The original as it must appear INSIDE a JSON string literal.
                    String quoted = mapper.writeValueAsString(secret.getValue());
                    json = json.replace(secret.getKey(), quoted.substring(1, quoted.length() - 1));
                }
            }
            return mapper.readValue(json, WorkflowGraphDto.class);
        } catch (Exception e) {
            throw new WorkflowValidationException(
                    List.of(
                            "The AI's change could not be applied to this workflow - try again, or edit it on the canvas."));
        }
    }

    // --- the walk -------------------------------------------------------------------------

    private static final class Collector {
        final Map<String, String> byPlaceholder = new LinkedHashMap<>();
        final Map<String, String> byOriginal = new LinkedHashMap<>();

        String placeholderFor(String original) {
            return byOriginal.computeIfAbsent(
                    original,
                    o -> {
                        String placeholder = "[[REDACTED_" + (byPlaceholder.size() + 1) + "]]";
                        byPlaceholder.put(placeholder, o);
                        return placeholder;
                    });
        }
    }

    private static JsonNode walk(JsonNode node, String key, Collector collector, int depth) {
        if (node == null || depth > 24) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            // A {name, value} pair (http header, query param): the NAME decides whether VALUE is
            // sensitive.
            String pairName =
                    object.path("name").isString() ? object.path("name").asString() : null;
            List<String> fields = new ArrayList<>();
            object.propertyNames().forEach(fields::add);
            for (String field : fields) {
                String effectiveKey = "value".equals(field) && pairName != null ? pairName : field;
                object.set(field, walk(object.get(field), effectiveKey, collector, depth + 1));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, walk(array.get(i), key, collector, depth + 1));
            }
            return array;
        }
        if (node.isString()) {
            String redacted = redactText(key, node.asString(), collector);
            return redacted.equals(node.asString()) ? node : StringNode.valueOf(redacted);
        }
        return node;
    }

    static boolean sensitiveKey(String key) {
        return key != null
                && SENSITIVE_NAME.matcher(key).find()
                && !NOT_A_CREDENTIAL_KEY.matcher(key).matches();
    }

    private static String redactText(String key, String text, Collector collector) {
        if (text.isBlank() || PLACEHOLDER.matcher(text).find()) {
            return text;
        }
        boolean hasExpression = text.contains("{{");
        if (sensitiveKey(key) && !hasExpression) {
            // Keep a recognisable scheme word so the model still sees what kind of header this is.
            Matcher scheme =
                    Pattern.compile("(?i)^(bearer|basic|token)\\s+(\\S.*)$").matcher(text.strip());
            if (scheme.matches()) {
                return scheme.group(1) + " " + collector.placeholderFor(scheme.group(2));
            }
            return collector.placeholderFor(text);
        }
        String out = text;
        for (Pattern pattern : EMBEDDED) {
            Matcher m = pattern.matcher(out);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            boolean changed = false;
            while (m.find()) {
                String credential = m.group(1);
                if (PLACEHOLDER.matcher(credential).find()) {
                    continue;
                }
                sb.append(out, last, m.start(1)).append(collector.placeholderFor(credential));
                last = m.end(1);
                changed = true;
            }
            if (changed) {
                out = sb.append(out.substring(last)).toString();
            }
        }
        return out;
    }
}
