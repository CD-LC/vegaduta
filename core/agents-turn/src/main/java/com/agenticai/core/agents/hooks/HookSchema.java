package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The one place a hook list is parsed, validated, normalised and serialised. Strict by design: an
 * unknown field, an action that makes no sense at its point, an out-of-range number or an oversized
 * text is a 400 ({@link IllegalArgumentException}) with a message that names the offending hook
 * ({@code hooks[2]: ...}), never a silently-ignored typo - a typo in a <i>governance</i> rule that
 * quietly does nothing is worse than a rejected save.
 *
 * <p>Caps: at most {@value #MAX_HOOKS} hooks per scope, at most {@value #MAX_JSON_CHARS} characters
 * of serialised JSON, and per-field size caps below.
 *
 * <p>{@code parse} applies an optional webhook-URL check (the allow-list) at save time; the runtime
 * checks again on every call, because the allow-list and DNS can change after a save.
 */
public final class HookSchema {

    public static final int MAX_HOOKS = 20;
    public static final int MAX_JSON_CHARS = 32_000;
    public static final int MAX_REASON_CHARS = 300;
    public static final int MAX_NOTE_CHARS = 500;
    public static final int MAX_LABEL_CHARS = 100;
    public static final int MAX_URL_CHARS = 2_048;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_TIMEOUT_MS = 2_000;
    public static final int MAX_LIMIT_PER_RUN = 1_000;
    public static final int MAX_LIMIT_PER_MINUTE = 10_000;

    static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    static final Pattern TOOL_GLOB_PATTERN = Pattern.compile("[A-Za-z0-9_.:*?-]{1,128}");

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final Set<String> HOOK_FIELDS =
            Set.of("id", "enabled", "point", "match", "action", "params", "failMode", "timeoutMs");
    private static final Set<String> MATCH_FIELDS =
            Set.of("tool", "trust", "sensitivity", "irreversible");

    private HookSchema() {}

    // ------------------------------------------------------------------ parse

    /**
     * @param hooksNode a JSON array of hook objects; {@code null} / JSON null / missing means no
     *     hooks
     * @param webhookUrlCheck returns an error message for a webhook URL that is not allowed, or
     *     {@code null} when it is; may itself be {@code null} to skip the check (stored data
     *     re-read)
     * @throws IllegalArgumentException with a field-level message when anything is invalid
     */
    public static List<HookDefinition> parse(
            JsonNode hooksNode, Function<String, String> webhookUrlCheck) {
        if (hooksNode == null || hooksNode.isNull() || hooksNode.isMissingNode()) {
            return List.of();
        }
        if (!hooksNode.isArray()) {
            throw new IllegalArgumentException("hooks must be an array");
        }
        if (hooksNode.size() > MAX_HOOKS) {
            throw new IllegalArgumentException(
                    "at most " + MAX_HOOKS + " hooks are allowed (got " + hooksNode.size() + ")");
        }
        List<HookDefinition> out = new ArrayList<>(hooksNode.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < hooksNode.size(); i++) {
            HookDefinition def = parseOne(hooksNode.get(i), "hooks[" + i + "]", webhookUrlCheck);
            if (!seen.add(def.id())) {
                throw new IllegalArgumentException(
                        "hooks[" + i + "]: duplicate id '" + def.id() + "'");
            }
            out.add(def);
        }
        if (toJson(out).length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException(
                    "the hook list is too large (over " + MAX_JSON_CHARS + " characters)");
        }
        return List.copyOf(out);
    }

    /** Re-reads stored JSON: the same shape rules, no webhook-URL check. Blank means no hooks. */
    public static List<HookDefinition> parseStored(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return parse(MAPPER.readTree(json), null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("stored hooks are not valid JSON", e);
        }
    }

    private static HookDefinition parseOne(
            JsonNode node, String at, Function<String, String> urlCheck) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(at + ": each hook must be an object");
        }
        rejectUnknown(node, HOOK_FIELDS, at);

        String id = text(node, "id", at, true);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    at
                            + ".id: use 1-40 characters of a-z, 0-9, '-' and '_', starting with a letter or digit");
        }
        at = at + " ('" + id + "')";

        boolean enabled = true;
        if (node.has("enabled") && !node.get("enabled").isNull()) {
            if (!node.get("enabled").isBoolean()) {
                throw new IllegalArgumentException(at + ".enabled: must be true or false");
            }
            enabled = node.get("enabled").asBoolean();
        }

        HookPoint point = HookPoint.parse(text(node, "point", at, true));
        if (point == null) {
            throw new IllegalArgumentException(
                    at
                            + ".point: must be one of BEFORE_MODEL, AFTER_MODEL, BEFORE_TOOL, AFTER_TOOL, ON_ERROR, ON_RUN_END");
        }
        HookActionType action = HookActionType.parse(text(node, "action", at, true));
        if (action == null) {
            throw new IllegalArgumentException(
                    at
                            + ".action: must be one of block, require_approval, redact, limit, append_context, webhook, log");
        }
        if (!HookActionType.allowedAt(point).contains(action)) {
            throw new IllegalArgumentException(
                    at
                            + ": the '"
                            + action.wire()
                            + "' action cannot be used at "
                            + point
                            + " (allowed there: "
                            + wireList(HookActionType.allowedAt(point))
                            + ")");
        }

        HookMatch match = parseMatch(node.get("match"), point, at);
        Map<String, Object> params = parseParams(node.get("params"), action, point, at, urlCheck);

        FailMode failMode = null;
        if (node.has("failMode") && !node.get("failMode").isNull()) {
            if (action != HookActionType.WEBHOOK) {
                throw new IllegalArgumentException(
                        at + ".failMode: only webhook hooks have a fail mode");
            }
            failMode = FailMode.parse(node.get("failMode").asText());
            if (failMode == null) {
                throw new IllegalArgumentException(at + ".failMode: must be 'open' or 'closed'");
            }
        }
        if (failMode == null) {
            failMode =
                    (point == HookPoint.BEFORE_TOOL || point == HookPoint.BEFORE_MODEL)
                            ? FailMode.CLOSED
                            : FailMode.OPEN;
        }

        int timeoutMs = DEFAULT_TIMEOUT_MS;
        if (node.has("timeoutMs") && !node.get("timeoutMs").isNull()) {
            if (action != HookActionType.WEBHOOK) {
                throw new IllegalArgumentException(
                        at + ".timeoutMs: only webhook hooks have a timeout");
            }
            timeoutMs =
                    intIn(node.get("timeoutMs"), at + ".timeoutMs", MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        }
        return new HookDefinition(id, enabled, point, match, action, params, failMode, timeoutMs);
    }

    private static HookMatch parseMatch(JsonNode node, HookPoint point, String at) {
        if (node == null || node.isNull() || (node.isObject() && node.isEmpty())) {
            return HookMatch.ANY;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(at + ".match: must be an object");
        }
        if (!point.isToolPoint() && point != HookPoint.ON_ERROR) {
            throw new IllegalArgumentException(
                    at
                            + ".match: a matcher only applies to tool hooks and ON_ERROR; leave it out at "
                            + point);
        }
        rejectUnknown(node, MATCH_FIELDS, at + ".match");
        String tool = optText(node, "tool", at + ".match");
        if (tool != null && !TOOL_GLOB_PATTERN.matcher(tool).matches()) {
            throw new IllegalArgumentException(
                    at
                            + ".match.tool: a tool name glob of letters, digits and _ . : - with * and ? wildcards (max 128)");
        }
        String trust = enumText(node, "trust", at + ".match", trustNames());
        String sensitivity = enumText(node, "sensitivity", at + ".match", sensitivityNames());
        Boolean irreversible = null;
        if (node.has("irreversible") && !node.get("irreversible").isNull()) {
            if (!node.get("irreversible").isBoolean()) {
                throw new IllegalArgumentException(
                        at + ".match.irreversible: must be true or false");
            }
            irreversible = node.get("irreversible").asBoolean();
        }
        HookMatch m = new HookMatch(tool, trust, sensitivity, irreversible);
        return m.isAny() ? HookMatch.ANY : m;
    }

    private static Map<String, Object> parseParams(
            JsonNode node,
            HookActionType action,
            HookPoint point,
            String at,
            Function<String, String> urlCheck) {
        String where = at + ".params";
        if (node != null && !node.isNull() && !node.isObject()) {
            throw new IllegalArgumentException(where + ": must be an object");
        }
        JsonNode p = node == null || node.isNull() ? MAPPER.createObjectNode() : node;
        Map<String, Object> out = new LinkedHashMap<>();
        switch (action) {
            case BLOCK, REQUIRE_APPROVAL -> {
                rejectUnknown(p, Set.of("reason"), where);
                putText(out, p, "reason", where, MAX_REASON_CHARS, false);
            }
            case LOG -> {
                rejectUnknown(p, Set.of("label"), where);
                putText(out, p, "label", where, MAX_LABEL_CHARS, false);
            }
            case APPEND_CONTEXT -> {
                rejectUnknown(p, Set.of("note"), where);
                putText(out, p, "note", where, MAX_NOTE_CHARS, true);
            }
            case LIMIT -> {
                rejectUnknown(p, Set.of("maxPerRun", "maxPerMinute", "message"), where);
                boolean any = false;
                if (p.has("maxPerRun") && !p.get("maxPerRun").isNull()) {
                    if (point == HookPoint.BEFORE_MODEL) {
                        throw new IllegalArgumentException(
                                where
                                        + ".maxPerRun: a model-call limit is per minute only; use maxPerMinute");
                    }
                    out.put(
                            "maxPerRun",
                            intIn(p.get("maxPerRun"), where + ".maxPerRun", 1, MAX_LIMIT_PER_RUN));
                    any = true;
                }
                if (p.has("maxPerMinute") && !p.get("maxPerMinute").isNull()) {
                    out.put(
                            "maxPerMinute",
                            intIn(
                                    p.get("maxPerMinute"),
                                    where + ".maxPerMinute",
                                    1,
                                    MAX_LIMIT_PER_MINUTE));
                    any = true;
                }
                if (!any) {
                    throw new IllegalArgumentException(
                            where
                                    + ": set maxPerMinute"
                                    + (point == HookPoint.BEFORE_MODEL ? "" : " and/or maxPerRun"));
                }
                putText(out, p, "message", where, MAX_REASON_CHARS, false);
            }
            case REDACT -> {
                rejectUnknown(p, Set.of("patterns", "regex", "replacement"), where);
                List<String> patterns =
                        textList(
                                p.get("patterns"),
                                where + ".patterns",
                                HookRedactor.builtinNames().size());
                for (String name : patterns) {
                    if (!HookRedactor.builtinNames().contains(name)) {
                        throw new IllegalArgumentException(
                                where
                                        + ".patterns: unknown pattern '"
                                        + name
                                        + "' (known: "
                                        + String.join(
                                                ", ",
                                                new java.util.TreeSet<>(
                                                        HookRedactor.builtinNames()))
                                        + ")");
                    }
                }
                List<String> regex =
                        textList(
                                p.get("regex"), where + ".regex", HookRedactor.MAX_CUSTOM_PATTERNS);
                for (String r : regex) {
                    String problem = HookRedactor.validateRegex(r);
                    if (problem != null) {
                        throw new IllegalArgumentException(where + ".regex: " + problem);
                    }
                }
                if (patterns.isEmpty() && regex.isEmpty()) {
                    throw new IllegalArgumentException(
                            where + ": give at least one built-in pattern or one regex");
                }
                if (!patterns.isEmpty()) {
                    out.put("patterns", List.copyOf(patterns));
                }
                if (!regex.isEmpty()) {
                    out.put("regex", List.copyOf(regex));
                }
                putText(out, p, "replacement", where, HookRedactor.MAX_REPLACEMENT_LENGTH, false);
            }
            case WEBHOOK -> {
                rejectUnknown(p, Set.of("url", "includeData"), where);
                String url = optText(p, "url", where);
                if (url == null) {
                    throw new IllegalArgumentException(where + ".url: required");
                }
                String urlProblem = structuralUrlProblem(url);
                if (urlProblem != null) {
                    throw new IllegalArgumentException(where + ".url: " + urlProblem);
                }
                if (urlCheck != null) {
                    String allowed = urlCheck.apply(url);
                    if (allowed != null) {
                        throw new IllegalArgumentException(where + ".url: " + allowed);
                    }
                }
                out.put("url", url);
                if (p.has("includeData") && !p.get("includeData").isNull()) {
                    if (!p.get("includeData").isBoolean()) {
                        throw new IllegalArgumentException(
                                where + ".includeData: must be true or false");
                    }
                    out.put("includeData", p.get("includeData").asBoolean());
                }
            }
        }
        return out;
    }

    /**
     * Shape-only URL check (https, a host, no credentials). The allow-list and SSRF guard run
     * elsewhere.
     */
    public static String structuralUrlProblem(String url) {
        if (url == null || url.isBlank()) {
            return "must not be blank";
        }
        if (url.length() > MAX_URL_CHARS) {
            return "is too long";
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return "is not a valid URL";
        }
        if (uri.getScheme() == null || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            return "must be an https URL";
        }
        if (uri.getRawUserInfo() != null) {
            return "must not contain credentials";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "must have a host";
        }
        return null;
    }

    // -------------------------------------------------------------- serialise

    /** Canonical, API-shaped form of a hook list (also what is stored). */
    public static List<Map<String, Object>> toMaps(List<HookDefinition> hooks) {
        List<Map<String, Object>> out = new ArrayList<>(hooks.size());
        for (HookDefinition h : hooks) {
            out.add(toMap(h));
        }
        return out;
    }

    public static Map<String, Object> toMap(HookDefinition h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id());
        m.put("enabled", h.enabled());
        m.put("point", h.point().name());
        if (!h.match().isAny()) {
            Map<String, Object> match = new LinkedHashMap<>();
            if (h.match().tool() != null) {
                match.put("tool", h.match().tool());
            }
            if (h.match().trust() != null) {
                match.put("trust", h.match().trust());
            }
            if (h.match().sensitivity() != null) {
                match.put("sensitivity", h.match().sensitivity());
            }
            if (h.match().irreversible() != null) {
                match.put("irreversible", h.match().irreversible());
            }
            m.put("match", match);
        }
        m.put("action", h.action().wire());
        m.put("params", new LinkedHashMap<>(h.params()));
        if (h.action() == HookActionType.WEBHOOK) {
            m.put("failMode", h.failMode().wire());
            m.put("timeoutMs", h.timeoutMs());
        }
        return m;
    }

    public static String toJson(List<HookDefinition> hooks) {
        try {
            return MAPPER.writeValueAsString(toMaps(hooks));
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise hooks", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String wireList(Set<HookActionType> actions) {
        List<String> names = new ArrayList<>();
        for (HookActionType a : HookActionType.values()) {
            if (actions.contains(a)) {
                names.add(a.wire());
            }
        }
        return String.join(", ", names);
    }

    private static Set<String> trustNames() {
        Set<String> names = new HashSet<>();
        for (ToolTrustTier t : ToolTrustTier.values()) {
            names.add(t.name());
        }
        names.add(HookMatch.UNCLASSIFIED);
        return names;
    }

    private static Set<String> sensitivityNames() {
        Set<String> names = new HashSet<>();
        for (ToolSensitivity s : ToolSensitivity.values()) {
            names.add(s.name());
        }
        names.add(HookMatch.UNCLASSIFIED);
        return names;
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String at) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(at + ": unknown field '" + name + "'");
            }
        }
    }

    private static String text(JsonNode node, String field, String at, boolean required) {
        String value = optText(node, field, at);
        if (value == null && required) {
            throw new IllegalArgumentException(at + "." + field + ": required");
        }
        return value;
    }

    private static String optText(JsonNode node, String field, String at) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(at + "." + field + ": must be a string");
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String enumText(JsonNode node, String field, String at, Set<String> allowed) {
        String v = optText(node, field, at);
        if (v == null) {
            return null;
        }
        String upper = v.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new IllegalArgumentException(
                    at
                            + "."
                            + field
                            + ": '"
                            + v
                            + "' is not one of "
                            + new java.util.TreeSet<>(allowed));
        }
        return upper;
    }

    private static void putText(
            Map<String, Object> out,
            JsonNode p,
            String field,
            String where,
            int max,
            boolean required) {
        String v = optText(p, field, where);
        if (v == null) {
            if (required) {
                throw new IllegalArgumentException(where + "." + field + ": required");
            }
            return;
        }
        if (v.length() > max) {
            throw new IllegalArgumentException(
                    where + "." + field + ": at most " + max + " characters");
        }
        for (int i = 0; i < v.length(); i++) {
            if (Character.isISOControl(v.charAt(i))) {
                throw new IllegalArgumentException(
                        where + "." + field + ": must be a single line of plain text");
            }
        }
        out.put(field, v);
    }

    private static int intIn(JsonNode v, String at, int min, int max) {
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) {
            throw new IllegalArgumentException(
                    at + ": must be a whole number between " + min + " and " + max);
        }
        int n = v.asInt();
        if (n < min || n > max) {
            throw new IllegalArgumentException(at + ": must be between " + min + " and " + max);
        }
        return n;
    }

    private static List<String> textList(JsonNode v, String at, int maxItems) {
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            throw new IllegalArgumentException(at + ": must be an array of strings");
        }
        if (v.size() > maxItems) {
            throw new IllegalArgumentException(at + ": at most " + maxItems + " entries");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : v) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(at + ": every entry must be a non-blank string");
            }
            String s = item.asText();
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
