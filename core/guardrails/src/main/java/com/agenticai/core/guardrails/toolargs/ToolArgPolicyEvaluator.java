package com.agenticai.core.guardrails.toolargs;

import com.agenticai.core.guardrails.toolargs.ToolArgPolicySet.ToolPolicy;
import com.agenticai.core.guardrails.toolargs.ToolArgRule.ArgType;
import com.agenticai.core.guardrails.toolargs.ToolArgRule.NormalizedPath;
import com.agenticai.core.json.JsonMappers;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks one tool call's JSON arguments against a {@link ToolArgPolicySet}, BEFORE the tool runs.
 *
 * <p><b>Pure and reusable.</b> No Spring, no I/O, no clock: {@code evaluate(toolName,
 * argumentsJson)} in, {@link Result} out. {@link ToolArgPolicyCallback} is the wrapper that applies
 * it in the guard chain; any other pre-dispatch hook can call the evaluator directly.
 *
 * <p><b>Tighten-only.</b> The evaluator can only refuse. A call no policy matches is allowed here
 * (the other guards in the chain still run); a matching policy never approves anything, and with
 * several matching policies every one must pass.
 *
 * <p><b>Fail-closed on what it cannot read.</b> If a policy governs the tool and the arguments are
 * not valid JSON, not an object, carry duplicate keys or trailing garbage (the shapes where two
 * parsers can disagree about what was said), exceed {@link #MAX_INPUT_CHARS}, or a regex cannot be
 * evaluated within its step budget, the call is refused with that reason - a value the policy
 * cannot verify is not waved through. Violation messages name the argument and the constraint but
 * never echo the offending value, so audit rows and logs cannot leak what the model was passing.
 *
 * <p>Numeric constraints ({@code min}/{@code max}) also read a numeric STRING ("9999") as a number,
 * and a string constraint reads a scalar as its text, so changing an argument's JSON type cannot be
 * used to step around a limit that a lenient tool would coerce back.
 */
public final class ToolArgPolicyEvaluator {

    /** Arguments larger than this (characters) are refused when a policy governs the tool. */
    public static final int MAX_INPUT_CHARS = 262_144;

    /** Values longer than this are not run through a {@code pattern} (and so cannot pass one). */
    public static final int MAX_PATTERN_INPUT_CHARS = 8_192;

    /** Violations collected per call; the rest are summarised. */
    public static final int MAX_VIOLATIONS = 10;

    /**
     * One failed constraint. {@code path} is the concrete argument path, e.g. {@code items[2].id}.
     */
    public record Violation(String tool, String path, String rule, String message) {}

    /** The verdict. {@code violations} is empty exactly when {@code allowed}. */
    public record Result(boolean allowed, List<Violation> violations) {

        private static final Result ALLOWED_RESULT = new Result(true, List.of());

        public Result {
            violations = List.copyOf(violations);
        }

        public static Result allow() {
            return ALLOWED_RESULT;
        }

        /** Model-facing reason: the violation messages, joined. Empty when allowed. */
        public String reason() {
            if (violations.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < violations.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(violations.get(i).message());
            }
            return sb.toString();
        }
    }

    /**
     * Strict reader: duplicate keys and trailing tokens are errors, so two parsers cannot disagree.
     */
    private static final ObjectMapper STRICT = JsonMappers.strictReader();

    private final ToolArgPolicySet policySet;

    public ToolArgPolicyEvaluator(ToolArgPolicySet policySet) {
        this.policySet = policySet != null ? policySet : ToolArgPolicySet.empty();
    }

    /**
     * True when at least one policy governs the tool (so the caller should evaluate, and the
     * arguments get parsed).
     */
    public boolean appliesTo(String toolName) {
        if (toolName == null) {
            return false;
        }
        for (ToolPolicy p : policySet.policies()) {
            if (p.appliesTo(toolName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param toolName the tool being called
     * @param argumentsJson the raw JSON the model produced; null or blank counts as {@code {}}
     */
    public Result evaluate(String toolName, String argumentsJson) {
        List<ToolPolicy> matching = new ArrayList<>();
        if (toolName != null) {
            for (ToolPolicy p : policySet.policies()) {
                if (p.appliesTo(toolName)) {
                    matching.add(p);
                }
            }
        }
        if (matching.isEmpty()) {
            return Result.allow();
        }
        String label = matching.get(0).tool();
        if (argumentsJson != null && argumentsJson.length() > MAX_INPUT_CHARS) {
            return deny(
                    label,
                    "",
                    "size",
                    "the arguments are too large to verify (limit "
                            + MAX_INPUT_CHARS
                            + " characters)");
        }
        JsonNode root;
        try {
            root =
                    argumentsJson == null || argumentsJson.isBlank()
                            ? STRICT.createObjectNode()
                            : STRICT.readTree(argumentsJson);
        } catch (JacksonException e) {
            return deny(
                    label,
                    "",
                    "json",
                    "the arguments are not valid JSON (duplicate keys and trailing text are not"
                            + " accepted)");
        }
        if (root == null || !root.isObject()) {
            return deny(label, "", "json", "the arguments must be a JSON object");
        }

        List<Violation> out = new ArrayList<>();
        for (ToolPolicy policy : matching) {
            if (policy.denyUnlistedArgs()) {
                Set<String> listed = new HashSet<>();
                for (ToolArgRule r : policy.rules()) {
                    listed.add(r.path().topLevelField());
                }
                root.propertyNames()
                        .iterator()
                        .forEachRemaining(
                                name -> {
                                    if (!listed.contains(name)) {
                                        add(
                                                out,
                                                new Violation(
                                                        policy.tool(),
                                                        name,
                                                        "unlisted",
                                                        "argument '"
                                                                + name
                                                                + "' is not allowed for this"
                                                                + " tool"));
                                    }
                                });
            }
            for (ToolArgRule rule : policy.rules()) {
                checkRule(policy.tool(), rule, root, out);
            }
            if (out.size() >= MAX_VIOLATIONS) {
                break;
            }
        }
        return out.isEmpty() ? Result.allow() : new Result(false, out);
    }

    // ------------------------------------------------------------------

    private static Result deny(String tool, String path, String rule, String message) {
        return new Result(false, List.of(new Violation(tool, path, rule, message)));
    }

    private static void add(List<Violation> out, Violation v) {
        if (out.size() < MAX_VIOLATIONS) {
            out.add(v);
        }
    }

    private static void checkRule(
            String tool, ToolArgRule rule, JsonNode root, List<Violation> out) {
        ToolArgPathSpec.Resolution resolution = rule.path().resolve(root);
        boolean anyPresent = false;
        for (ToolArgPathSpec.Hit hit : resolution.hits()) {
            JsonNode value = hit.value();
            if (value == null || value.isNull()) {
                continue; // an explicit null is "not provided"
            }
            anyPresent = true;
            if (rule.forbidden()) {
                add(
                        out,
                        new Violation(
                                tool,
                                hit.path(),
                                "forbidden",
                                "argument '" + hit.path() + "' must not be provided"));
                continue;
            }
            checkValue(tool, rule, hit.path(), value, out);
        }
        if (resolution.truncated()) {
            add(
                    out,
                    new Violation(
                            tool,
                            rule.path().text(),
                            "fanout",
                            "'"
                                    + rule.path().text()
                                    + "' lists more than "
                                    + ToolArgPathSpec.MAX_FAN_OUT
                                    + " elements, which is too many to verify"));
        }
        if (rule.required() && !anyPresent) {
            add(
                    out,
                    new Violation(
                            tool,
                            rule.path().text(),
                            "required",
                            "'" + rule.path().text() + "' is required"));
        }
    }

    private static void checkValue(
            String tool, ToolArgRule rule, String path, JsonNode value, List<Violation> out) {
        String q = "'" + path + "'";
        if (rule.type() != null && !typeMatches(rule.type(), value)) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "type",
                            q + " must be " + article(rule.type()) + " " + rule.type().wire()));
            return; // later constraints assume the declared type
        }
        if (rule.enumValues() != null && !inEnum(rule.enumValues(), value)) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "enum",
                            q + " must be one of: " + describeEnum(rule.enumValues())));
        }
        if (rule.maxItems() != null) {
            if (!value.isArray()) {
                add(out, new Violation(tool, path, "maxItems", q + " must be a list"));
            } else if (value.size() > rule.maxItems()) {
                add(
                        out,
                        new Violation(
                                tool,
                                path,
                                "maxItems",
                                q + " must have at most " + rule.maxItems() + " items"));
            }
        }
        if (rule.min() != null || rule.max() != null) {
            BigDecimal n = numeric(value);
            if (n == null) {
                add(out, new Violation(tool, path, "number", q + " must be a number"));
            } else {
                if (rule.min() != null && n.compareTo(rule.min()) < 0) {
                    add(
                            out,
                            new Violation(
                                    tool,
                                    path,
                                    "min",
                                    q + " must be at least " + rule.min().toPlainString()));
                }
                if (rule.max() != null && n.compareTo(rule.max()) > 0) {
                    add(
                            out,
                            new Violation(
                                    tool,
                                    path,
                                    "max",
                                    q + " must be at most " + rule.max().toPlainString()));
                }
            }
        }
        boolean textRules =
                rule.minLength() != null
                        || rule.maxLength() != null
                        || rule.pattern() != null
                        || rule.urlHostIn() != null
                        || rule.pathUnder() != null;
        if (!textRules) {
            return;
        }
        if (value.isContainer()) {
            add(out, new Violation(tool, path, "text", q + " must be a text value"));
            return;
        }
        String text = value.asString();
        if (rule.minLength() != null && text.length() < rule.minLength()) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "minLength",
                            q + " must be at least " + rule.minLength() + " characters"));
        }
        if (rule.maxLength() != null && text.length() > rule.maxLength()) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "maxLength",
                            q + " must be at most " + rule.maxLength() + " characters"));
        }
        if (rule.pattern() != null) {
            checkPattern(tool, rule, path, text, out);
        }
        if (rule.urlHostIn() != null) {
            checkUrlHost(tool, rule, path, text, out);
        }
        if (rule.pathUnder() != null) {
            checkPathUnder(tool, rule, path, text, out);
        }
    }

    private static void checkPattern(
            String tool, ToolArgRule rule, String path, String text, List<Violation> out) {
        String q = "'" + path + "'";
        if (text.length() > MAX_PATTERN_INPUT_CHARS) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "pattern",
                            q + " is too long to check against its allowed pattern"));
            return;
        }
        try {
            if (!BoundedRegex.matchesWhole(rule.pattern(), text)) {
                add(
                        out,
                        new Violation(
                                tool, path, "pattern", q + " does not match the allowed pattern"));
            }
        } catch (BoundedRegex.BudgetExceeded e) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "pattern",
                            q + " could not be checked against its allowed pattern in time"));
        }
    }

    private static void checkUrlHost(
            String tool, ToolArgRule rule, String path, String text, List<Violation> out) {
        String q = "'" + path + "'";
        String allowed = "one of: " + describeList(rule.urlHostIn());
        URI uri;
        try {
            uri = new URI(text.trim());
        } catch (URISyntaxException e) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "urlHostIn",
                            q + " must be an http(s) URL whose host is " + allowed));
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if ((!scheme.equals("http") && !scheme.equals("https")) || host == null || host.isEmpty()) {
            add(
                    out,
                    new Violation(
                            tool,
                            path,
                            "urlHostIn",
                            q + " must be an http(s) URL whose host is " + allowed));
            return;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        for (String pattern : rule.urlHostIn()) {
            if (hostMatches(pattern, h)) {
                return;
            }
        }
        add(
                out,
                new Violation(
                        tool,
                        path,
                        "urlHostIn",
                        q + " must be an http(s) URL whose host is " + allowed));
    }

    /** Exact host, or {@code *.suffix} for subdomains (the apex itself needs its own entry). */
    static boolean hostMatches(String pattern, String host) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1); // ".example.com"
            return host.length() > suffix.length() && host.endsWith(suffix);
        }
        return pattern.equals(host);
    }

    private static void checkPathUnder(
            String tool, ToolArgRule rule, String path, String text, List<Violation> out) {
        String q = "'" + path + "'";
        NormalizedPath normalized = ToolArgRule.normalizePath(text);
        if (normalized != null) {
            for (NormalizedPath prefix : rule.pathUnder()) {
                if (normalized.isUnder(prefix)) {
                    return;
                }
            }
        }
        List<String> shown = new ArrayList<>();
        for (NormalizedPath p : rule.pathUnder()) {
            shown.add(p.display());
        }
        add(
                out,
                new Violation(
                        tool,
                        path,
                        "pathUnder",
                        q + " must be a path under: " + describeList(shown)));
    }

    // ------------------------------------------------------------------
    // Value helpers
    // ------------------------------------------------------------------

    private static boolean typeMatches(ArgType type, JsonNode v) {
        return switch (type) {
            case STRING -> v.isString();
            case INTEGER ->
                    v.isIntegralNumber()
                            || (v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0);
            case NUMBER -> v.isNumber();
            case BOOLEAN -> v.isBoolean();
            case ARRAY -> v.isArray();
            case OBJECT -> v.isObject();
        };
    }

    private static String article(ArgType type) {
        return type == ArgType.INTEGER || type == ArgType.ARRAY || type == ArgType.OBJECT
                ? "an"
                : "a";
    }

    private static boolean inEnum(List<JsonNode> allowed, JsonNode value) {
        for (JsonNode a : allowed) {
            if (a.isNumber() && value.isNumber()) {
                if (a.decimalValue().compareTo(value.decimalValue()) == 0) {
                    return true;
                }
            } else if (a.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A number, or null. A numeric string counts: a lenient tool would coerce it, so the limit must
     * hold for it too.
     */
    private static BigDecimal numeric(JsonNode v) {
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isString()) {
            String t = v.asString().trim();
            if (t.isEmpty() || t.length() > 64) {
                return null;
            }
            try {
                return new BigDecimal(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String describeEnum(List<JsonNode> values) {
        List<String> parts = new ArrayList<>();
        for (JsonNode v : values) {
            parts.add(v.isString() ? v.asString() : v.toString());
        }
        return describeList(parts);
    }

    /** First ten entries, each clipped, with a count of the rest. */
    private static String describeList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(values.size(), 10);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String s = values.get(i);
            sb.append(s.length() <= 40 ? s : s.substring(0, 40) + "...");
        }
        if (values.size() > shown) {
            sb.append(" (+").append(values.size() - shown).append(" more)");
        }
        return sb.toString();
    }
}
