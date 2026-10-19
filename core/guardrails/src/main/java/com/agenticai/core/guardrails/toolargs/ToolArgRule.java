package com.agenticai.core.guardrails.toolargs;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One constraint on one argument of a tool call (JSON-schema-lite). Immutable; built and validated by
 * {@link #parse(JsonNode)}.
 *
 * <pre>
 *   {"path":"url",   "urlHostIn":["example.com","*.docs.example.com"], "maxLength":2048, "required":true}
 *   {"path":"mode",  "enum":["fast","slow"]}
 *   {"path":"limit", "type":"integer", "min":1, "max":50}
 *   {"path":"file",  "pathUnder":["/workspace","reports"]}
 *   {"path":"name",  "pattern":"[a-z0-9_-]{1,40}"}
 *   {"path":"items[*].id", "type":"string", "maxLength":36}
 *   {"path":"debug", "forbidden":true}
 * </pre>
 *
 * Every key is validated and an UNKNOWN key is rejected: a typo such as {@code "maxLenght"} must never
 * become a policy that silently enforces nothing.
 */
public final class ToolArgRule {

    /** JSON value kinds a rule can require. */
    public enum ArgType {
        STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT;

        static ArgType parse(String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "'type' must be one of string, integer, number, boolean, array, object.");
            }
        }

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A normalised {@code pathUnder} prefix or value: absolute flag plus lexical segments. */
    record NormalizedPath(boolean absolute, List<String> segments) {

        boolean isUnder(NormalizedPath prefix) {
            if (absolute != prefix.absolute() || segments.size() < prefix.segments().size()) {
                return false;
            }
            for (int i = 0; i < prefix.segments().size(); i++) {
                if (!segments.get(i).equals(prefix.segments().get(i))) {
                    return false;
                }
            }
            return true;
        }

        String display() {
            if (segments.isEmpty()) {
                return absolute ? "/" : ".";
            }
            return (absolute ? "/" : "") + String.join("/", segments);
        }
    }

    static final Set<String> KNOWN_KEYS = Set.of(
        "path", "required", "forbidden", "type", "enum", "minLength", "maxLength", "min", "max",
        "pattern", "urlHostIn", "pathUnder", "maxItems");

    private final ToolArgPathSpec path;
    private final boolean required;
    private final boolean forbidden;
    private final ArgType type;
    private final List<JsonNode> enumValues;
    private final Integer minLength;
    private final Integer maxLength;
    private final BigDecimal min;
    private final BigDecimal max;
    private final Pattern pattern;
    private final List<String> urlHostIn;
    private final List<NormalizedPath> pathUnder;
    private final Integer maxItems;

    private ToolArgRule(
        ToolArgPathSpec path, boolean required, boolean forbidden, ArgType type, List<JsonNode> enumValues,
        Integer minLength, Integer maxLength, BigDecimal min, BigDecimal max, Pattern pattern,
        List<String> urlHostIn, List<NormalizedPath> pathUnder, Integer maxItems
    ) {
        this.path = path;
        this.required = required;
        this.forbidden = forbidden;
        this.type = type;
        this.enumValues = enumValues;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.min = min;
        this.max = max;
        this.pattern = pattern;
        this.urlHostIn = urlHostIn;
        this.pathUnder = pathUnder;
        this.maxItems = maxItems;
    }

    public ToolArgPathSpec path() {
        return path;
    }

    public boolean required() {
        return required;
    }

    public boolean forbidden() {
        return forbidden;
    }

    public ArgType type() {
        return type;
    }

    List<JsonNode> enumValues() {
        return enumValues;
    }

    Integer minLength() {
        return minLength;
    }

    Integer maxLength() {
        return maxLength;
    }

    BigDecimal min() {
        return min;
    }

    BigDecimal max() {
        return max;
    }

    Pattern pattern() {
        return pattern;
    }

    List<String> urlHostIn() {
        return urlHostIn;
    }

    List<NormalizedPath> pathUnder() {
        return pathUnder;
    }

    Integer maxItems() {
        return maxItems;
    }

    /** True when the rule has anything to check on a PRESENT value (beyond {@code forbidden}). */
    boolean hasValueConstraints() {
        return type != null || enumValues != null || minLength != null || maxLength != null || min != null
            || max != null || pattern != null || urlHostIn != null || pathUnder != null || maxItems != null;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /**
     * @throws IllegalArgumentException naming the offending key when the rule is malformed, unknown, or
     *     exceeds a size cap
     */
    public static ToolArgRule parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Each rule must be a JSON object.");
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown rule key '" + clip(key) + "'. Allowed keys: "
                    + String.join(", ", new java.util.TreeSet<>(KNOWN_KEYS)) + ".");
            }
        }
        JsonNode pathNode = node.get("path");
        if (pathNode == null || !pathNode.isTextual()) {
            throw new IllegalArgumentException("Each rule needs a text 'path'.");
        }
        ToolArgPathSpec path = ToolArgPathSpec.parse(pathNode.asText());
        String where = "Rule for '" + path.text() + "': ";

        boolean required = optionalBoolean(node, "required", where);
        boolean forbidden = optionalBoolean(node, "forbidden", where);
        ArgType type = null;
        if (node.has("type")) {
            if (!node.get("type").isTextual()) {
                throw new IllegalArgumentException(where + "'type' must be text.");
            }
            type = ArgType.parse(node.get("type").asText());
        }

        List<JsonNode> enumValues = null;
        if (node.has("enum")) {
            JsonNode e = node.get("enum");
            if (!e.isArray() || e.isEmpty()) {
                throw new IllegalArgumentException(where + "'enum' must be a non-empty array.");
            }
            if (e.size() > ToolArgPolicySet.MAX_ENUM_VALUES) {
                throw new IllegalArgumentException(where + "'enum' is limited to " + ToolArgPolicySet.MAX_ENUM_VALUES + " values.");
            }
            enumValues = new ArrayList<>();
            for (JsonNode v : e) {
                if (!(v.isTextual() || v.isNumber() || v.isBoolean())) {
                    throw new IllegalArgumentException(where + "'enum' values must be strings, numbers or booleans.");
                }
                if (v.isTextual() && v.asText().length() > ToolArgPolicySet.MAX_LITERAL_LENGTH) {
                    throw new IllegalArgumentException(where + "an 'enum' value is longer than "
                        + ToolArgPolicySet.MAX_LITERAL_LENGTH + " characters.");
                }
                enumValues.add(v);
            }
            enumValues = List.copyOf(enumValues);
        }

        Integer minLength = optionalNonNegativeInt(node, "minLength", where);
        Integer maxLength = optionalNonNegativeInt(node, "maxLength", where);
        Integer maxItems = optionalNonNegativeInt(node, "maxItems", where);
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw new IllegalArgumentException(where + "'minLength' is greater than 'maxLength'.");
        }
        BigDecimal min = optionalNumber(node, "min", where);
        BigDecimal max = optionalNumber(node, "max", where);
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException(where + "'min' is greater than 'max'.");
        }

        Pattern pattern = null;
        if (node.has("pattern")) {
            JsonNode p = node.get("pattern");
            if (!p.isTextual() || p.asText().isEmpty()) {
                throw new IllegalArgumentException(where + "'pattern' must be non-empty text.");
            }
            if (p.asText().length() > ToolArgPolicySet.MAX_PATTERN_LENGTH) {
                throw new IllegalArgumentException(where + "'pattern' is limited to "
                    + ToolArgPolicySet.MAX_PATTERN_LENGTH + " characters.");
            }
            try {
                pattern = Pattern.compile(p.asText());
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException(where + "'pattern' is not a valid regular expression: "
                    + ex.getDescription() + ".");
            }
        }

        List<String> hosts = null;
        if (node.has("urlHostIn")) {
            hosts = new ArrayList<>();
            for (String h : stringList(node, "urlHostIn", where)) {
                hosts.add(validateHostPattern(h, where));
            }
            hosts = List.copyOf(hosts);
        }

        List<NormalizedPath> prefixes = null;
        if (node.has("pathUnder")) {
            prefixes = new ArrayList<>();
            for (String p : stringList(node, "pathUnder", where)) {
                NormalizedPath n = normalizePath(p);
                if (n == null) {
                    throw new IllegalArgumentException(where + "'pathUnder' entries must be plain paths.");
                }
                if (n.segments().contains("..")) {
                    throw new IllegalArgumentException(where + "'pathUnder' entries must not climb above their own root ('..').");
                }
                prefixes.add(n);
            }
            prefixes = List.copyOf(prefixes);
        }

        if (forbidden) {
            if (required) {
                throw new IllegalArgumentException(where + "'forbidden' and 'required' cannot both be set.");
            }
            if (type != null || enumValues != null || minLength != null || maxLength != null || min != null
                || max != null || pattern != null || hosts != null || prefixes != null || maxItems != null) {
                throw new IllegalArgumentException(where + "'forbidden' cannot be combined with value constraints.");
            }
        }
        if (required && path.hasWildcard()) {
            throw new IllegalArgumentException(where + "'required' cannot be used with a [*] path; require the array itself instead.");
        }
        if (!forbidden && !required && type == null && enumValues == null && minLength == null && maxLength == null
            && min == null && max == null && pattern == null && hosts == null && prefixes == null && maxItems == null) {
            throw new IllegalArgumentException(where + "the rule constrains nothing - add 'type', 'enum', 'pattern' or another constraint.");
        }
        return new ToolArgRule(path, required, forbidden, type, enumValues, minLength, maxLength, min, max,
            pattern, hosts, prefixes, maxItems);
    }

    private static boolean optionalBoolean(JsonNode node, String key, String where) {
        if (!node.has(key)) {
            return false;
        }
        if (!node.get(key).isBoolean()) {
            throw new IllegalArgumentException(where + "'" + key + "' must be true or false.");
        }
        return node.get(key).asBoolean();
    }

    private static Integer optionalNonNegativeInt(JsonNode node, String key, String where) {
        if (!node.has(key)) {
            return null;
        }
        JsonNode v = node.get(key);
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < 0) {
            throw new IllegalArgumentException(where + "'" + key + "' must be a whole number of at least 0.");
        }
        return v.asInt();
    }

    private static BigDecimal optionalNumber(JsonNode node, String key, String where) {
        if (!node.has(key)) {
            return null;
        }
        JsonNode v = node.get(key);
        if (!v.isNumber()) {
            throw new IllegalArgumentException(where + "'" + key + "' must be a number.");
        }
        return v.decimalValue();
    }

    private static List<String> stringList(JsonNode node, String key, String where) {
        JsonNode a = node.get(key);
        if (!a.isArray() || a.isEmpty()) {
            throw new IllegalArgumentException(where + "'" + key + "' must be a non-empty array of text.");
        }
        if (a.size() > ToolArgPolicySet.MAX_STRING_LIST) {
            throw new IllegalArgumentException(where + "'" + key + "' is limited to " + ToolArgPolicySet.MAX_STRING_LIST + " entries.");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : a) {
            if (!v.isTextual() || v.asText().isBlank()) {
                throw new IllegalArgumentException(where + "'" + key + "' entries must be non-empty text.");
            }
            if (v.asText().length() > ToolArgPolicySet.MAX_LITERAL_LENGTH) {
                throw new IllegalArgumentException(where + "a '" + key + "' entry is longer than "
                    + ToolArgPolicySet.MAX_LITERAL_LENGTH + " characters.");
            }
            out.add(v.asText());
        }
        return out;
    }

    /** Host patterns are an exact lower-case host, or {@code *.suffix} (subdomains only, not the apex). */
    private static String validateHostPattern(String raw, String where) {
        String h = raw.trim().toLowerCase(Locale.ROOT);
        String body = h.startsWith("*.") ? h.substring(2) : h;
        boolean ok = !body.isEmpty() && body.length() <= 253 && !body.startsWith(".") && !body.endsWith(".")
            && !body.contains("..");
        if (ok) {
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                // IPv6 literals are written with their brackets stripped ("::1" style) - allow ':' for them.
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == ':')) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) {
            throw new IllegalArgumentException(where + "'urlHostIn' entry \"" + clip(raw)
                + "\" must be a host name like example.com or a subdomain wildcard like *.example.com.");
        }
        return h;
    }

    /**
     * Lexical path normalisation shared by the {@code pathUnder} prefixes and the values they are checked
     * against. {@code \} counts as a separator, {@code .} and empty segments vanish, {@code ..} pops (and on a
     * relative path that cannot pop stays as a leading {@code ..}, so it can never satisfy a prefix).
     * Returns null for a value containing NUL. It does not resolve symlinks or decode percent escapes - it is a
     * pre-dispatch screen, not a sandbox.
     */
    static NormalizedPath normalizePath(String raw) {
        if (raw == null || raw.indexOf('\0') >= 0) {
            return null;
        }
        String s = raw.replace('\\', '/');
        boolean absolute = s.startsWith("/");
        List<String> out = new ArrayList<>();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
                    out.remove(out.size() - 1);
                } else if (!absolute) {
                    out.add("..");
                }
                continue;
            }
            out.add(part);
        }
        if (!absolute && !out.isEmpty() && out.get(0).length() == 2 && out.get(0).charAt(1) == ':'
            && Character.isLetter(out.get(0).charAt(0))) {
            // Drive-letter path (C:/x): absolute, drive compared case-insensitively.
            absolute = true;
            out.set(0, out.get(0).toLowerCase(Locale.ROOT));
        }
        return new NormalizedPath(absolute, List.copyOf(out));
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /** Wire form of this rule, used to persist a validated policy set. */
    Map<String, Object> toWire() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("path", path.text());
        if (required) {
            m.put("required", true);
        }
        if (forbidden) {
            m.put("forbidden", true);
        }
        if (type != null) {
            m.put("type", type.wire());
        }
        if (enumValues != null) {
            m.put("enum", enumValues);
        }
        if (minLength != null) {
            m.put("minLength", minLength);
        }
        if (maxLength != null) {
            m.put("maxLength", maxLength);
        }
        if (min != null) {
            m.put("min", min);
        }
        if (max != null) {
            m.put("max", max);
        }
        if (pattern != null) {
            m.put("pattern", pattern.pattern());
        }
        if (urlHostIn != null) {
            m.put("urlHostIn", urlHostIn);
        }
        if (pathUnder != null) {
            List<String> shown = new ArrayList<>();
            for (NormalizedPath p : pathUnder) {
                shown.add(p.display());
            }
            m.put("pathUnder", shown);
        }
        if (maxItems != null) {
            m.put("maxItems", maxItems);
        }
        return m;
    }
}
