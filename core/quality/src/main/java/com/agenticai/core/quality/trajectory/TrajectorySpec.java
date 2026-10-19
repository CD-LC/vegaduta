package com.agenticai.core.quality.trajectory;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A dataset case's expected trajectory plus how to score it - stored as the case's {@code grader_spec} JSON
 * (the existing nullable TEXT column, V375) with {@code "type":"trajectory"}, so NO new column and no
 * migration. A case therefore carries either a response grader (regex / exact / json_schema) OR a trajectory
 * spec; {@code EvalDatasetService#setCaseGrader} routes between them.
 *
 * <pre>
 * {"type":"trajectory",
 *  "mode":"in_order",            exact | in_order (default) | any_order | f1
 *  "minScore":1.0,               pass bar on the mode's score, 0..1 (default 1.0)
 *  "maxExtraCalls":2,            optional hard cap on calls that match no expected step
 *  "minEfficiency":0.5,          optional floor on expected-steps / actual-steps
 *  "steps":[ {"tool":"search_knowledge_base","args":{"query":{"regex":"refund"}}}, {"tool":"send_email"} ]}
 * </pre>
 *
 * Unknown keys are rejected at write time (a typo must not silently weaken a spec). {@code steps} may be empty:
 * "the agent should call no tools" (use mode {@code exact}, or {@code maxExtraCalls: 0}).
 */
public final class TrajectorySpec {

    public enum Mode {
        EXACT, IN_ORDER, ANY_ORDER, F1;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Mode parse(String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'mode' must be one of exact, in_order, any_order, f1.");
            }
        }
    }

    public static final String TYPE = "trajectory";
    public static final int MAX_STEPS = 50;
    public static final int MAX_ARGS_PER_STEP = 20;
    public static final int MAX_DOCUMENT_CHARS = 16_384;

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Set<String> KEYS = Set.of("type", "mode", "minScore", "maxExtraCalls", "minEfficiency", "steps");
    private static final Set<String> STEP_KEYS = Set.of("tool", "args");
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern ARG_PATH = Pattern.compile("[A-Za-z0-9_$-]+(\\.[A-Za-z0-9_$-]+){0,7}");

    private final Mode mode;
    private final double minScore;
    private final Integer maxExtraCalls;
    private final Double minEfficiency;
    private final List<ExpectedStep> steps;

    private TrajectorySpec(Mode mode, double minScore, Integer maxExtraCalls, Double minEfficiency, List<ExpectedStep> steps) {
        this.mode = mode;
        this.minScore = minScore;
        this.maxExtraCalls = maxExtraCalls;
        this.minEfficiency = minEfficiency;
        this.steps = List.copyOf(steps);
    }

    public Mode mode() {
        return mode;
    }

    public double minScore() {
        return minScore;
    }

    public Integer maxExtraCalls() {
        return maxExtraCalls;
    }

    public Double minEfficiency() {
        return minEfficiency;
    }

    List<ExpectedStep> steps() {
        return steps;
    }

    public int stepCount() {
        return steps.size();
    }

    // ------------------------------------------------------------------

    /**
     * Cheap routing test: is this stored {@code grader_spec} a trajectory spec? False for anything that is not
     * a JSON object with {@code "type":"trajectory"} (including text that does not parse - the code-grader
     * path owns reporting that).
     */
    public static boolean isTrajectory(String specJson) {
        if (specJson == null || specJson.isBlank() || !specJson.toLowerCase(Locale.ROOT).contains(TYPE)) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(specJson);
            return node != null && node.isObject() && node.path("type").isTextual()
                && TYPE.equalsIgnoreCase(node.path("type").asText().trim());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Write-time gate used by {@code EvalDatasetService#setCaseGrader}: with the feature off a trajectory spec
     * is rejected with exactly the message today's code-grader parser gives for an unknown type, so flag-off
     * behaviour is unchanged; with it on the spec is fully validated.
     *
     * @throws IllegalArgumentException when disabled or invalid
     */
    public static void validateForWrite(String specJson, boolean featureEnabled) {
        if (!featureEnabled) {
            throw new IllegalArgumentException("Unknown grader type '" + TYPE + "' - use regex, exact or json_schema.");
        }
        parse(specJson);
    }

    /** @throws IllegalArgumentException with a precise message when the document is not a valid trajectory spec */
    public static TrajectorySpec parse(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw new IllegalArgumentException("Trajectory spec is empty.");
        }
        if (specJson.length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("The trajectory spec is limited to " + MAX_DOCUMENT_CHARS + " characters.");
        }
        try {
            return parse(MAPPER.readTree(specJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Trajectory spec is not valid JSON.");
        }
    }

    /** As {@link #parse(String)} for an already-parsed tree. */
    public static TrajectorySpec parse(JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw new IllegalArgumentException("Trajectory spec must be a JSON object.");
        }
        for (Iterator<String> it = spec.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown trajectory key '" + clip(key) + "'. Allowed: "
                    + String.join(", ", new java.util.TreeSet<>(KEYS)) + ".");
            }
        }
        if (!spec.has("type") || !TYPE.equalsIgnoreCase(spec.path("type").asText("").trim())) {
            throw new IllegalArgumentException("Trajectory spec needs \"type\":\"" + TYPE + "\".");
        }
        Mode mode = Mode.IN_ORDER;
        if (spec.has("mode")) {
            if (!spec.get("mode").isTextual()) {
                throw new IllegalArgumentException("'mode' must be text.");
            }
            mode = Mode.parse(spec.get("mode").asText());
        }
        double minScore = 1.0;
        if (spec.has("minScore")) {
            minScore = unitInterval(spec.get("minScore"), "minScore");
        }
        Integer maxExtra = null;
        if (spec.has("maxExtraCalls")) {
            JsonNode v = spec.get("maxExtraCalls");
            if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < 0 || v.asInt() > 1000) {
                throw new IllegalArgumentException("'maxExtraCalls' must be a whole number from 0 to 1000.");
            }
            maxExtra = v.asInt();
        }
        Double minEfficiency = null;
        if (spec.has("minEfficiency")) {
            minEfficiency = unitInterval(spec.get("minEfficiency"), "minEfficiency");
        }
        JsonNode stepsNode = spec.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            throw new IllegalArgumentException("Trajectory spec needs a 'steps' array (it may be empty).");
        }
        if (stepsNode.size() > MAX_STEPS) {
            throw new IllegalArgumentException("A trajectory can list at most " + MAX_STEPS + " steps.");
        }
        List<ExpectedStep> steps = new ArrayList<>();
        int index = 0;
        for (JsonNode s : stepsNode) {
            index++;
            steps.add(parseStep(s, "Step #" + index + ": "));
        }
        return new TrajectorySpec(mode, minScore, maxExtra, minEfficiency, steps);
    }

    private static ExpectedStep parseStep(JsonNode s, String where) {
        if (!s.isObject()) {
            throw new IllegalArgumentException(where + "must be a JSON object.");
        }
        for (Iterator<String> it = s.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!STEP_KEYS.contains(key)) {
                throw new IllegalArgumentException(where + "unknown key '" + clip(key) + "'. Allowed: tool, args.");
            }
        }
        JsonNode tool = s.get("tool");
        if (tool == null || !tool.isTextual() || !TOOL_NAME.matcher(tool.asText().trim()).matches()) {
            throw new IllegalArgumentException(where + "'tool' must be an exact tool name (letters, digits, _ - . :).");
        }
        Map<String, ArgMatcher> matchers = new LinkedHashMap<>();
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        JsonNode args = s.get("args");
        if (args != null && !args.isNull()) {
            if (!args.isObject()) {
                throw new IllegalArgumentException(where + "'args' must be an object of argument matchers.");
            }
            if (args.size() > MAX_ARGS_PER_STEP) {
                throw new IllegalArgumentException(where + "at most " + MAX_ARGS_PER_STEP + " argument matchers per step.");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = args.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!ARG_PATH.matcher(e.getKey()).matches()) {
                    throw new IllegalArgumentException(where + "argument path '" + clip(e.getKey())
                        + "' must be field names joined by dots, e.g. opts.limit.");
                }
                try {
                    matchers.put(e.getKey(), ArgMatcher.parse(e.getKey(), e.getValue()));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(where + ex.getMessage());
                }
                raw.put(e.getKey(), e.getValue());
            }
        }
        return new ExpectedStep(tool.asText().trim(), matchers, raw);
    }

    private static double unitInterval(JsonNode v, String key) {
        if (!v.isNumber()) {
            throw new IllegalArgumentException("'" + key + "' must be a number from 0 to 1.");
        }
        BigDecimal d = v.decimalValue();
        if (d.compareTo(BigDecimal.ZERO) < 0 || d.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("'" + key + "' must be a number from 0 to 1.");
        }
        return d.doubleValue();
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /** The canonical document this spec was parsed from (defaults made explicit). */
    public JsonNode toJsonNode() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", TYPE);
        root.put("mode", mode.wire());
        root.put("minScore", minScore);
        if (maxExtraCalls != null) {
            root.put("maxExtraCalls", maxExtraCalls);
        }
        if (minEfficiency != null) {
            root.put("minEfficiency", minEfficiency);
        }
        ArrayNode arr = root.putArray("steps");
        for (ExpectedStep step : steps) {
            ObjectNode o = arr.addObject();
            o.put("tool", step.tool());
            if (step.hasMatchers()) {
                ObjectNode args = o.putObject("args");
                for (Map.Entry<String, JsonNode> e : step.rawMatchers().entrySet()) {
                    args.set(e.getKey(), e.getValue());
                }
            }
        }
        return root;
    }

    public String toJson() {
        return toJsonNode().toString();
    }
}
