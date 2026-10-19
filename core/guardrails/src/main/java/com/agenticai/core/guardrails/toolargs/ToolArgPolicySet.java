package com.agenticai.core.guardrails.toolargs;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A validated, immutable set of per-tool argument policies for ONE agent.
 *
 * <pre>
 * {"policies":[
 *   {"tool":"fetch_web_page",
 *    "denyUnlistedArgs":false,
 *    "rules":[ {"path":"url","urlHostIn":["example.com"],"required":true}, ... ]},
 *   {"tool":"mcp__files__*", "rules":[ {"path":"path","pathUnder":["/workspace"]} ]}
 * ]}
 * </pre>
 *
 * {@code tool} is an exact tool name or a glob ({@code *}, {@code ?}). A call is allowed only when EVERY
 * matching policy's rules pass - policies can only tighten, never grant anything. Parsing validates every key
 * (unknown keys are rejected), every pattern (compiled up front) and every size cap, so a stored set is
 * always one the evaluator can run.
 *
 * <p>Pure: no Spring, no I/O. The agents module stores the JSON text; this class owns what it may contain.
 */
public final class ToolArgPolicySet {

    public static final int MAX_POLICIES = 50;
    public static final int MAX_RULES_PER_POLICY = 30;
    public static final int MAX_TOOL_PATTERN_LENGTH = 128;
    public static final int MAX_ENUM_VALUES = 100;
    public static final int MAX_STRING_LIST = 50;
    public static final int MAX_LITERAL_LENGTH = 512;
    public static final int MAX_PATTERN_LENGTH = 200;
    /** Upper bound on the stored document, in characters. */
    public static final int MAX_DOCUMENT_CHARS = 65_536;

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Set<String> POLICY_KEYS = Set.of("tool", "rules", "denyUnlistedArgs");

    /** One tool (or glob) and the rules a call to it must satisfy. */
    public record ToolPolicy(String tool, List<ToolArgRule> rules, boolean denyUnlistedArgs) {

        public ToolPolicy {
            rules = List.copyOf(rules);
        }

        /** Whether this policy governs a tool of that name. */
        public boolean appliesTo(String toolName) {
            return ToolArgGlob.matches(tool, toolName);
        }
    }

    private static final ToolArgPolicySet EMPTY = new ToolArgPolicySet(List.of());

    private final List<ToolPolicy> policies;

    private ToolArgPolicySet(List<ToolPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public static ToolArgPolicySet empty() {
        return EMPTY;
    }

    public List<ToolPolicy> policies() {
        return policies;
    }

    public boolean isEmpty() {
        return policies.isEmpty();
    }

    /**
     * Parses and validates a stored/submitted document.
     *
     * @throws IllegalArgumentException with a precise message when the text is not valid JSON, is too large,
     *     or breaks any rule above
     */
    public static ToolArgPolicySet parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        if (json.length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("The policy document is limited to " + MAX_DOCUMENT_CHARS + " characters.");
        }
        try {
            return parse(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The policy document is not valid JSON.");
        }
    }

    /** As {@link #parse(String)} for an already-parsed tree. */
    public static ToolArgPolicySet parse(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return EMPTY;
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("The policy document must be a JSON object with a 'policies' array.");
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!key.equals("policies")) {
                throw new IllegalArgumentException("Unknown top-level key '" + clip(key) + "'. Only 'policies' is allowed.");
            }
        }
        JsonNode list = root.get("policies");
        if (list == null || list.isNull()) {
            return EMPTY;
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException("'policies' must be an array.");
        }
        if (list.size() > MAX_POLICIES) {
            throw new IllegalArgumentException("At most " + MAX_POLICIES + " policies are allowed.");
        }
        if (root.toString().length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("The policy document is limited to " + MAX_DOCUMENT_CHARS + " characters.");
        }
        List<ToolPolicy> out = new ArrayList<>();
        int index = 0;
        for (JsonNode p : list) {
            index++;
            out.add(parsePolicy(p, index));
        }
        return new ToolArgPolicySet(out);
    }

    private static ToolPolicy parsePolicy(JsonNode p, int index) {
        String where = "Policy #" + index + ": ";
        if (!p.isObject()) {
            throw new IllegalArgumentException(where + "must be a JSON object.");
        }
        for (Iterator<String> it = p.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!POLICY_KEYS.contains(key)) {
                throw new IllegalArgumentException(where + "unknown key '" + clip(key) + "'. Allowed: tool, rules, denyUnlistedArgs.");
            }
        }
        JsonNode toolNode = p.get("tool");
        if (toolNode == null || !toolNode.isTextual() || toolNode.asText().isBlank()) {
            throw new IllegalArgumentException(where + "'tool' must be a tool name or glob.");
        }
        String tool = toolNode.asText().trim();
        if (tool.length() > MAX_TOOL_PATTERN_LENGTH) {
            throw new IllegalArgumentException(where + "'tool' is limited to " + MAX_TOOL_PATTERN_LENGTH + " characters.");
        }
        for (int i = 0; i < tool.length(); i++) {
            char c = tool.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == ':' || c == '*' || c == '?';
            if (!ok) {
                throw new IllegalArgumentException(where + "'tool' may contain letters, digits and _ - . : and the glob characters * ?.");
            }
        }
        JsonNode rulesNode = p.get("rules");
        boolean deny = false;
        if (p.has("denyUnlistedArgs")) {
            if (!p.get("denyUnlistedArgs").isBoolean()) {
                throw new IllegalArgumentException(where + "'denyUnlistedArgs' must be true or false.");
            }
            deny = p.get("denyUnlistedArgs").asBoolean();
        }
        List<ToolArgRule> rules = new ArrayList<>();
        if (rulesNode != null && !rulesNode.isNull()) {
            if (!rulesNode.isArray()) {
                throw new IllegalArgumentException(where + "'rules' must be an array.");
            }
            if (rulesNode.size() > MAX_RULES_PER_POLICY) {
                throw new IllegalArgumentException(where + "at most " + MAX_RULES_PER_POLICY + " rules per tool.");
            }
            for (JsonNode r : rulesNode) {
                try {
                    rules.add(ToolArgRule.parse(r));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(where + e.getMessage());
                }
            }
        }
        if (rules.isEmpty() && !deny) {
            throw new IllegalArgumentException(where + "needs at least one rule (or 'denyUnlistedArgs': true).");
        }
        return new ToolPolicy(tool, rules, deny);
    }

    /** The canonical document this set was parsed from - what gets stored and echoed back. */
    public JsonNode toJsonNode() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("policies");
        for (ToolPolicy p : policies) {
            ObjectNode o = arr.addObject();
            o.put("tool", p.tool());
            if (p.denyUnlistedArgs()) {
                o.put("denyUnlistedArgs", true);
            }
            ArrayNode rules = o.putArray("rules");
            for (ToolArgRule r : p.rules()) {
                JsonNode wire = MAPPER.valueToTree(r.toWire());
                rules.add(wire);
            }
        }
        return root;
    }

    /** {@link #toJsonNode()} as compact text. */
    public String toJson() {
        return toJsonNode().toString();
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
