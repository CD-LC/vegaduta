package com.agenticai.core.agents.tools;

import com.agenticai.core.guardrails.ToolResultMarker;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Injection/flooding caps for content that crosses from a dynamic MCP server into the agent loop
 * (MCP-DOCTOR hardening, 2026-07-25). Two directions:
 *
 * <ul>
 *   <li><b>Tool RESULTS</b> - a malicious/buggy server can return megabytes (context-window
 *       flooding = silent instruction loss) or ANSI/control sequences (terminal-escape smuggling,
 *       invisible-text prompt injection). Capped at {@value #MAX_RESULT_CHARS} chars with an
 *       explicit truncation marker; ESC/control chars stripped (tab/newline kept).
 *   <li><b>Tool NAMES/DESCRIPTIONS at discovery</b> - tool-description poisoning defense: a hostile
 *       server's 50KB "description" is a prompt injection vector straight into the system prompt.
 *       Clamped to sane lengths and control-stripped before the definition ever reaches the model.
 * </ul>
 *
 * Applied by {@code SanitizedMcpToolCallback}, which {@code DynamicMcpConnectionManager} wraps
 * around every callback a dynamic server advertises. Pure static functions - unit-tested directly.
 */
final class McpToolOutputSanitizer {

    /**
     * 64KB of characters - beyond this a tool result is truncated with {@link #TRUNCATION_MARKER}.
     *
     * <p>This is a SECURITY ceiling on what the platform will hold and scan from an untrusted MCP
     * server, not the model-facing cap: the result then goes through {@code GuardedToolCallback},
     * which cuts it to {@code app.tools.output.*} (20,000 characters by default) with a marker that
     * states how much was cut. It is deliberately a constant above that cap.
     */
    static final int MAX_RESULT_CHARS = 64 * 1024;

    static final int MAX_NAME_CHARS = 128;
    static final int MAX_DESCRIPTION_CHARS = 2048;
    static final String TRUNCATION_MARKER =
            "\n...[truncated by platform: tool result exceeded 64KB]";

    private static final char ESC = (char) 0x1B;
    private static final char BEL = (char) 0x07;
    private static final char DEL = (char) 0x7F;
    private static final ObjectMapper JSON_MAPPER = JsonMappers.plain();

    private McpToolOutputSanitizer() {}

    /**
     * Live incident, 2026-08-13: Spring AI's Gemini integration ({@code
     * org.springframework.ai.google.genai.GoogleGenAiChatModel#parseJsonToMap}) unconditionally
     * parses every tool-role message's content as a JSON object when building the next request's
     * function-response part - and throws, killing the ENTIRE chat turn (confirmed live via a real
     * {@code JsonParseException}: "Unrecognized token 'Tool': was expecting (JSON String, Number,
     * Array, Object...)"), on anything that isn't valid JSON. That describes the overwhelming
     * majority of real MCP tool output: the MCP spec's {@code TextContent} shape is plain prose,
     * not JSON, for both a normal success message and an error string - {@link
     * SanitizedMcpToolCallback}'s own {@code failedResult}/{@code blockedResult} text included.
     * This was masked as long as a tool call simply never failed AND happened to return a
     * JSON-shaped success value (coincidence, not a guarantee) - any tool returning plain prose, or
     * any failure path at all, was always one Gemini turn away from this crash.
     *
     * <p>Wraps non-JSON-object content as {@code {"result": "<original text>"}} so Gemini's parser
     * always succeeds; a tool result that's already a real JSON object passes through byte-for-byte
     * unchanged (no double-wrapping an already-well-shaped response, and no behavior change for
     * whatever coincidentally already worked). Provider-agnostic and applied to every {@link
     * SanitizedMcpToolCallback} return path regardless of which model answers this turn - a
     * non-Gemini provider that never had this problem is unaffected either way, since a JSON object
     * is still perfectly readable prose-equivalent content to any LLM.
     */
    static String ensureJsonObject(String result) {
        if (result == null) {
            return null;
        }
        String trimmed = result.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = JSON_MAPPER.readTree(trimmed);
                if (node != null && node.isObject()) {
                    return result; // already a real JSON object - pass through unchanged
                }
            } catch (Exception ignored) {
                // Not actually valid JSON despite the leading brace - fall through to wrap.
            }
        }
        // The wrap mints a new String, and {@link ToolResultMarker}'s registry keys on identity:
        // without re-registration a platform answer (a refusal, a park, a withheld result relayed
        // through call_tool) would come out of here unmarked - unrecognised by the guard's
        // platform-answer bypass and journalable as an executed effect. Carrying the kind over is
        // not new detection: {@code classify} reads exactly what consumers would have read off the
        // unwrapped text, so a tool's own output that merely reads like a refusal is no more and no
        // less classified than it was before the wrap.
        String wrapped = wrap(result);
        return ToolResultMarker.classify(result)
                .map(kind -> ToolResultMarker.mark(wrapped, kind))
                .orElse(wrapped);
    }

    private static String wrap(String result) {
        try {
            return JSON_MAPPER.writeValueAsString(Map.of("result", result));
        } catch (Exception e) {
            // Unreachable in practice (Jackson always serializes a String value) - last-resort
            // manual escape rather than letting a tool result ever propagate un-wrapped.
            return "{\"result\":\""
                    + result.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "")
                    + "\"}";
        }
    }

    /**
     * Result-path sanitization: strip ANSI/control, then cap at {@link #MAX_RESULT_CHARS}.
     * Null-safe.
     *
     * <p>Pentest M6: by the time {@code raw} reaches this method, the underlying MCP client SDK has
     * already fully materialized it in memory - confirmed by decompiling {@code mcp-core} 0.18.2:
     * {@code StdioClientTransport} reads a message via an unbounded {@code
     * BufferedReader.readLine()} (no max-line-length option), and the HTTP transports' {@code
     * ResponseSubscribers.AggregateSubscriber} accumulates the whole body into an unbounded {@code
     * StringBuilder} - neither exposes a configurable max-read/max-string-length hook in this SDK
     * version, so a hostile/compromised server returning a multi-GB result has already forced that
     * allocation before any code in this module ever runs. That is a genuine SDK-level gap this
     * method cannot close (would require vendoring/patching the SDK's transport classes - tracked
     * as a follow-up, not a localized patch).
     *
     * <p>What this method CAN do - and previously didn't - is avoid making its OWN cost scale with
     * an already-oversized {@code raw}: the old code ran {@link #stripControlAndAnsi} (which itself
     * allocated a {@code StringBuilder(raw.length())}) over the ENTIRE raw string, then truncated
     * the cleaned result - i.e. a second full-size allocation plus an O(raw.length()) scan on top
     * of the already-materialized string, for a server that could be handing over gigabytes.
     * Capping the working window to {@link #MAX_RESULT_CHARS} raw characters BEFORE stripping
     * bounds this method's own added memory/CPU to O(cap) regardless of how large {@code raw} is -
     * stripping only ever removes characters (never adds any), so the cleaned result is never
     * longer than the bounded window.
     */
    static String sanitizeResult(String raw) {
        if (raw == null) {
            return null;
        }
        boolean overLength = raw.length() > MAX_RESULT_CHARS;
        String bounded = overLength ? raw.substring(0, MAX_RESULT_CHARS) : raw;
        String cleaned = stripControlAndAnsi(bounded);
        return overLength ? cleaned + TRUNCATION_MARKER : cleaned;
    }

    /** Discovery-path clamp for tool names. Null-safe. */
    static String sanitizeName(String raw) {
        return clamp(stripControlAndAnsi(raw), MAX_NAME_CHARS);
    }

    /** Discovery-path clamp for tool descriptions. Null-safe. */
    static String sanitizeDescription(String raw) {
        return clamp(stripControlAndAnsi(raw), MAX_DESCRIPTION_CHARS);
    }

    private static String clamp(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * Removes ANSI escape sequences (CSI/OSC/two-char) and every other control character except
     * tab, LF and CR. Handles the common CSI form {@code ESC [ ... final-byte} and OSC {@code ESC ]
     * ... BEL/ST} structurally rather than by regex alone, so split/partial sequences still lose
     * their ESC byte.
     */
    static String stripControlAndAnsi(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == ESC) {
                i++;
                if (i < n && raw.charAt(i) == '[') {
                    // CSI: parameter/intermediate bytes 0x20-0x3F, then one final byte
                    i++;
                    while (i < n && raw.charAt(i) >= 0x20 && raw.charAt(i) <= 0x3F) {
                        i++;
                    }
                    if (i < n) {
                        i++; // consume the final byte
                    }
                } else if (i < n && raw.charAt(i) == ']') {
                    // OSC: runs until BEL or ST (ESC \)
                    i++;
                    while (i < n && raw.charAt(i) != BEL && raw.charAt(i) != ESC) {
                        i++;
                    }
                    if (i < n && raw.charAt(i) == BEL) {
                        i++;
                    } else if (i + 1 < n && raw.charAt(i) == ESC && raw.charAt(i + 1) == '\\') {
                        i += 2;
                    }
                } else if (i < n) {
                    i++; // two-char escape (ESC + one)
                }
                continue;
            }
            boolean keep =
                    c == '\t'
                            || c == '\n'
                            || c == '\r'
                            || (c >= 0x20 && c != DEL && !(c >= 0x80 && c <= 0x9F));
            if (keep) {
                out.append(c);
            }
            i++;
        }
        return stripInvisibles(out.toString());
    }

    /**
     * Pentest M7: strip Unicode invisible/formatting characters that survive the &gt;=0x20
     * keep-rule but are the standard carriers of invisible prompt injection - bidi
     * overrides/isolates (text that renders reversed or reordered), zero-width joiners/spaces, the
     * BOM, soft hyphen, and the Unicode Tag block (U+E0000..U+E007F, used to smuggle hidden ASCII
     * into a single visible glyph). Code-point aware so the supplementary Tag range (surrogate
     * pairs) is handled.
     */
    private static String stripInvisibles(String s) {
        // Fast path: nothing to do for the overwhelmingly common ASCII case.
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                any = true;
                break;
            }
        }
        if (!any) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!isDisallowedInvisible(cp)) {
                b.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    private static boolean isDisallowedInvisible(int cp) {
        return (cp >= 0x200B && cp <= 0x200F) // ZWSP/ZWNJ/ZWJ + LRM/RLM
                || (cp >= 0x202A && cp <= 0x202E) // bidi embeddings/overrides
                || (cp >= 0x2060 && cp <= 0x2064) // word joiner + invisible math ops
                || (cp >= 0x2066 && cp <= 0x2069) // bidi isolates
                || cp == 0xFEFF // ZWNBSP / BOM
                || cp == 0x00AD // soft hyphen
                || (cp >= 0xE0000 && cp <= 0xE007F); // Unicode Tag block
    }
}
