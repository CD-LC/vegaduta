package com.agenticai.core.agents.tools;

import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Last-mile redaction and truncation for anything {@link AgentStepEventEmitter} is about to put on
 * the wire toward a browser. Pure static functions, no Spring, unit-testable with plain string
 * fixtures - the same shape as its sibling {@link McpToolOutputSanitizer} in this package.
 *
 * <h2>What has already run before this class, and what it is therefore for</h2>
 *
 * {@link StepEventToolCallback} sits OUTERMOST in {@code AgentToolProvider#guardAndLog}'s wrap
 * chain, so a tool RESULT reaching this class has already been through every existing sanitization
 * pass: {@code SanitizedMcpToolCallback} ({@link McpToolOutputSanitizer#sanitizeResult} -
 * ANSI/control stripping, invisible-character stripping, 64KB cap) for MCP tools, then {@code
 * GuardedToolCallback}'s {@code GuardrailService#sanitizeToolOutput} (secret-shaped substrings
 * replaced with {@code [REDACTED]}, plus its own context-budget cap) for every tool, MCP or
 * built-in. Nothing here re-implements any of that.
 *
 * <p>Tool ARGUMENTS are the genuinely unprotected direction, and the reason this class exists.
 * Nothing in the existing chain redacts an argument value: {@link McpToolArgumentGuard} inspects
 * arguments but only ever BLOCKS (traversal/SSRF shapes), and {@code
 * ArgumentSanitizingToolCallingManager} (core/chat) only repairs malformed JSON and hallucinated
 * tool names. Until now nothing was ever going to show an argument to a human, so that was fine; a
 * step trace changes that, so arguments get their own pass here:
 *
 * <ol>
 *   <li><b>Key-name pass</b> - a JSON object's value is replaced with {@code [redacted]} when its
 *       FIELD NAME looks credential-bearing ({@code token}, {@code secret}, {@code password},
 *       {@code api_key}, {@code authorization}, {@code credential}, {@code cookie}, ...),
 *       recursively, at any nesting depth. This is the pass that matters: an agent told to "call
 *       the API with key X" produces exactly this shape, and no value-shape regex would recognize a
 *       bespoke internal token.
 *   <li><b>Value-shape pass</b> - the same seven credential regexes {@code
 *       PatternBasedGuardrailService#REDACTION_PATTERNS} uses (AWS access key, Google API key,
 *       GitHub token, Slack token, OpenAI-style key, PEM private key, JWT), COPIED verbatim rather
 *       than imported, for the same reason {@code SensitiveDataMasker} (core/app) documents for its
 *       own copy: that bean is {@code @ConditionalOnProperty}-gated to one of two
 *       mutually-exclusive guardrail implementations and is not reliably present, whereas this pass
 *       must run on every deployment.
 * </ol>
 *
 * <p>Both passes then go through {@link #flatten}, which strips control characters and collapses
 * whitespace. That is belt-and-braces on the framing rule in {@link AgentStepEventEmitter}'s
 * javadoc (Jackson already escapes a literal newline inside a JSON string value, so a raw newline
 * could not fragment the SSE chunk anyway) and keeps the trace readable as one line in the UI.
 *
 * <p><b>Fail-open on unparseable input</b>, matching {@link McpToolArgumentGuard}'s documented
 * posture: an argument string that is not valid JSON skips the key-name pass and is handled by the
 * value-shape pass and the length cap alone, rather than being dropped or throwing out of a
 * per-tool-call hot path.
 */
public final class AgentStepEventRedactor {

    /**
     * Deliberately short. A trace is a summary of what the agent did, not a second copy of the
     * payload - the full input and output are already persisted verbatim by {@code
     * LoggingToolCallback} into {@code tool_call_log} and readable through {@code GET
     * /api/agents/{id}/tool-calls}, which is the surface for "show me everything". Keeping this
     * small also bounds how much a step trace can add to a turn's total bytes on the wire.
     */
    static final int MAX_ARGUMENT_CHARS = 400;

    static final int MAX_PREVIEW_CHARS = 400;
    static final String TRUNCATION_SUFFIX = "…";
    static final String REDACTED = "[redacted]";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Substring match, lower-cased, on the FIELD NAME - not an exact match: real-world argument
     * keys are {@code apiKey}, {@code api_key}, {@code x-api-key}, {@code githubToken}, {@code
     * clientSecret}, and an exact-match list would miss every one of them. Over-redacting an
     * innocent field called e.g. {@code "keywords"} costs a person one unreadable line in a trace;
     * under-redacting costs a leaked credential, so the asymmetry is deliberate.
     */
    private static final List<String> SECRET_KEY_FRAGMENTS =
            List.of(
                    "password",
                    "passwd",
                    "secret",
                    "token",
                    "credential",
                    "apikey",
                    "api_key",
                    "access_key",
                    "accesskey",
                    "private_key",
                    "privatekey",
                    "authorization",
                    "auth_header",
                    "cookie",
                    "session_key",
                    "bearer",
                    "signature",
                    "passphrase");

    /**
     * Copied verbatim from {@code PatternBasedGuardrailService#REDACTION_PATTERNS} - see this
     * class's javadoc for why it is a copy and not an import. If that list gains a pattern, this
     * one should too.
     */
    private static final List<Pattern> SECRET_VALUE_PATTERNS =
            List.of(
                    Pattern.compile("AKIA[0-9A-Z]{16}"),
                    Pattern.compile("AIza[0-9A-Za-z\\-_]{35}"),
                    Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
                    Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
                    Pattern.compile("sk-[A-Za-z0-9]{20,}"),
                    Pattern.compile(
                            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
                    Pattern.compile(
                            "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    private AgentStepEventRedactor() {}

    /** Full pipeline for a tool's LLM-constructed argument JSON - see the class javadoc. */
    static String redactArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "";
        }
        String byKey = redactSecretKeys(rawArguments);
        return clamp(flatten(redactSecretValues(byKey)), MAX_ARGUMENT_CHARS);
    }

    /**
     * Tool output/error text. No key-name pass here on purpose: this is prose or an already-{@code
     * ensureJsonObject}-wrapped result, not a caller-authored argument map, and it has already been
     * through the guardrail's own redaction (see the class javadoc). The value-shape pass is kept
     * as defense in depth for the deployments running the pattern guardrail's lighter cap ordering.
     */
    static String redactPreview(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "";
        }
        return clamp(flatten(redactSecretValues(rawOutput)), MAX_PREVIEW_CHARS);
    }

    /**
     * Both redaction passes (key-name, then value-shape) with NO flatten and NO length cap - for
     * callers that store a payload for later display and do their own bounded truncation, e.g. the
     * workflow run trace's per-node input/output (core/workflow {@code NodeTracePayloads}). Kept
     * here rather than copied so the key-fragment and credential-pattern lists stay one list.
     * Null-safe; unparseable JSON falls through to the value pass only, same fail-open posture as
     * {@link #redactArguments}.
     */
    public static String redactPayload(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return redactSecretValues(redactSecretKeys(raw));
    }

    /** True when {@code fieldName} is credential-bearing by this class's key-fragment rule. */
    public static boolean isSecretKey(String fieldName) {
        return fieldName != null && isSecretFieldName(fieldName);
    }

    /**
     * Recursive key-name pass. Returns the input unchanged when it is not a JSON object/array
     * (fail-open, see the class javadoc) - the value-shape pass and the cap still apply to it
     * downstream either way.
     */
    private static String redactSecretKeys(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !(root.isObject() || root.isArray())) {
                return json;
            }
            redactNode(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Not valid JSON (or Jackson couldn't re-serialize it) - fall back
            // to the raw string. Never throw out of a per-tool-call path.
            return json;
        }
    }

    private static void redactNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            // Field names are snapshotted into a list first rather than
            // mutated through the live iterator: replacing a value via put()
            // happens to be safe on Jackson's backing map today, but this
            // method is the one place a future edit (dropping a field instead
            // of masking it) would turn that into a
            // ConcurrentModificationException on a per-tool-call path.
            List<String> names = new ArrayList<>();
            object.propertyNames().iterator().forEachRemaining(names::add);
            for (String name : names) {
                if (isSecretFieldName(name)) {
                    object.put(name, REDACTED);
                } else {
                    redactNode(object.get(name));
                }
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(AgentStepEventRedactor::redactNode);
        }
    }

    private static boolean isSecretFieldName(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT).replace("-", "");
        for (String fragment : SECRET_KEY_FRAGMENTS) {
            if (lower.contains(fragment.replace("_", "")) || lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String redactSecretValues(String value) {
        String out = value;
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            out = pattern.matcher(out).replaceAll(REDACTED);
        }
        return out;
    }

    /**
     * Control/ANSI stripping is delegated to {@link McpToolOutputSanitizer} (same package) rather
     * than reimplemented - it already handles split CSI/ OSC sequences and the Unicode
     * invisible/bidi carriers structurally. The whitespace collapse on top is this method's own
     * contribution: it keeps a trace line readable and is the belt-and-braces half of the
     * single-line framing rule described in {@link AgentStepEventEmitter}'s javadoc.
     */
    private static String flatten(String value) {
        String cleaned = McpToolOutputSanitizer.stripControlAndAnsi(value);
        return cleaned == null ? "" : cleaned.replaceAll("\\s+", " ").trim();
    }

    private static String clamp(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + TRUNCATION_SUFFIX;
    }
}
