package com.agenticai.core.workflow;

import com.agenticai.core.agents.tools.AgentStepEventRedactor;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redaction and size-capping for the per-node {@code inputJson}/{@code outputJson} a {@link
 * NodeTraceEntry} carries (canvas run-overlay, 2026-09-29). Pure static functions, no Spring.
 *
 * <p><b>Why redact.</b> A run's trace is readable by anyone with VIEWER on the workflow ({@code GET
 * /api/workflows/{id}/runs/{runId}}), which is a lower bar than the RUNNER/EDITOR who wired the
 * nodes. The data flowing between nodes routinely carries credentials: an HTTP node's response
 * echoing an Authorization header, an agent reply quoting a key it was handed, a webhook payload
 * with a {@code password} field. So every payload goes through:
 *
 * <ol>
 *   <li>{@link AgentStepEventRedactor#redactPayload} - the SAME key-name pass (credential-looking
 *       JSON field names, any depth) and value-shape pass (AWS/Google/GitHub/Slack/OpenAI-style
 *       keys, PEM private keys, JWTs) the agent step trace uses, reused rather than copied;
 *   <li>two text-shape passes that pass cannot see because they are not JSON: {@code Bearer
 *       <token>} anywhere in prose, and {@code password=...}-style key/value pairs (query strings,
 *       form bodies, and JSON that is no longer parseable because it is huge).
 * </ol>
 *
 * Redaction runs on the input BEFORE truncation, so a secret straddling the cut is already masked;
 * payloads above {@link #MAX_REDACT_CHARS} are pre-trimmed first (key/value regex still covers the
 * trimmed JSON).
 *
 * <p><b>Why cap.</b> {@code workflow_run.trace_json} is rewritten after every node and polled by
 * the canvas, so its size multiplies. Three bounds keep it sane without a schema change: {@link
 * #MAX_FIELD_CHARS} per field for a normal node, {@link #MAX_ITERATION_FIELD_CHARS} for per-item
 * entries inside loop/parallel/fan-out/sub-flow (up to hundreds per run), and {@link
 * #MAX_RUN_PAYLOAD_CHARS} across the whole trace - once that budget is spent, later entries keep
 * their timings/tokens but store no payload and are flagged truncated. Sizes are in chars (UTF-16
 * units), not bytes.
 */
public final class NodeTracePayloads {

    /** 16 KB per stored input/output on an ordinary node. */
    public static final int MAX_FIELD_CHARS = 16 * 1024;

    /** Per-item entries in loop/parallel/fan-out/sub-flow bodies. */
    public static final int MAX_ITERATION_FIELD_CHARS = 2 * 1024;

    /** All payloads across one run's trace. */
    public static final int MAX_RUN_PAYLOAD_CHARS = 512 * 1024;

    /** Upper bound on how much text is fed to the redactor at all. */
    static final int MAX_REDACT_CHARS = 256 * 1024;

    static final String REDACTED = "[redacted]";

    /** The lookahead demands a digit so prose like "basic authentication" is left alone. */
    private static final Pattern BEARER =
            Pattern.compile(
                    "(?i)\\b(bearer|basic)\\s+(?=[A-Za-z0-9._~+/\\-]*\\d)[A-Za-z0-9._~+/\\-]{8,}=*");

    /**
     * {@code key=value}, {@code key: value} and {@code "key": "value"} where key contains a
     * credential-looking fragment. Group 1 keeps the key and separator; the value (quoted string or
     * bare run up to a delimiter) is replaced. Unterminated quoted values (JSON cut mid-string) are
     * matched to the end.
     */
    // Linear by construction: no unbounded prefix before the keyword (that backtracked
    // super-quadratically on
    // a long alphanumeric run; the prefix stays in the output untouched, so it never needed
    // matching) and a
    // bounded tail, so "tokentoken..." cannot rescan itself.
    private static final Pattern SECRET_KEY_VALUE =
            Pattern.compile(
                    "(?i)((?:password|passwd|secret|token|credential|api[_\\-]?key|access[_\\-]?key|"
                            + "private[_\\-]?key|authorization|cookie|signature|passphrase)[A-Za-z0-9_\\-]{0,64}\"?\\s*[:=]\\s*)"
                            + "(\"(?:[^\"\\\\]|\\\\.)*(?:\"|$)|[^\\s&,;}\\]\"]+)");

    private NodeTracePayloads() {}

    /** A sanitized payload: redacted text (possibly cut) and whether it was cut. */
    public record Sanitized(String text, boolean truncated) {
        static final Sanitized NONE = new Sanitized(null, false);
    }

    /** Redact, then cap to {@code maxChars}. Null in, null out. */
    public static Sanitized sanitize(String raw, int maxChars) {
        if (raw == null) {
            return Sanitized.NONE;
        }
        boolean truncated = false;
        String working = raw;
        if (working.length() > MAX_REDACT_CHARS) {
            working = working.substring(0, MAX_REDACT_CHARS);
            truncated = true;
        }
        String redacted = redact(working);
        if (redacted.length() > maxChars) {
            redacted = redacted.substring(0, Math.max(0, maxChars));
            truncated = true;
        }
        return new Sanitized(redacted, truncated);
    }

    /** Both redaction layers, no cap - see the class javadoc. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = AgentStepEventRedactor.redactPayload(text);
        out = BEARER.matcher(out).replaceAll("$1 " + Matcher.quoteReplacement(REDACTED));
        Matcher m = SECRET_KEY_VALUE.matcher(out);
        StringBuilder sb = new StringBuilder(out.length());
        while (m.find()) {
            String value = m.group(2);
            // AgentStepEventRedactor already masked this JSON field.
            boolean alreadyMasked = value.contains(REDACTED);
            String replacement =
                    alreadyMasked
                            ? m.group(0)
                            : m.group(1)
                                    + (value.startsWith("\"") ? "\"" + REDACTED + "\"" : REDACTED);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Chars of payload the given trace already stores. */
    public static long payloadCharsUsed(List<NodeTraceEntry> trace) {
        long used = 0;
        for (NodeTraceEntry entry : trace) {
            used += entry.inputJson() == null ? 0 : entry.inputJson().length();
            used += entry.outputJson() == null ? 0 : entry.outputJson().length();
        }
        return used;
    }

    /**
     * Attaches sanitized input/output to {@code entry}, honoring the per-field cap and whatever is
     * left of the run budget given {@code alreadyUsed}. A payload that would not fit the remaining
     * budget is dropped (null) and flagged truncated rather than stored partially out of order.
     */
    public static NodeTraceEntry attach(
            NodeTraceEntry entry,
            String rawInput,
            String rawOutput,
            int perFieldCap,
            long alreadyUsed) {
        long remaining = Math.max(0L, MAX_RUN_PAYLOAD_CHARS - alreadyUsed);
        int inCap = (int) Math.min(perFieldCap, remaining);
        Sanitized in = sanitize(rawInput, inCap);
        remaining -= in.text() == null ? 0 : in.text().length();
        int outCap = (int) Math.min(perFieldCap, Math.max(0L, remaining));
        Sanitized out = sanitize(rawOutput, outCap);
        return entry.withPayloads(
                emptyToNullIfCut(in), in.truncated(), emptyToNullIfCut(out), out.truncated());
    }

    /** A payload cut to zero chars by an exhausted budget is stored as absent, not "". */
    private static String emptyToNullIfCut(Sanitized s) {
        return s.truncated() && s.text() != null && s.text().isEmpty() ? null : s.text();
    }
}
