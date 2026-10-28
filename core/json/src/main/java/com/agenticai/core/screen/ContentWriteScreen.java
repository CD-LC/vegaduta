package com.agenticai.core.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one regex tripwire for text that is about to be PERSISTED where a later model turn will read
 * it back: agent memory ({@code remember_fact}), graph memory, knowledge collections.
 *
 * <p>Whatever lands in those stores is retrieved into a future context, possibly for a different
 * user, so a stored directive is a stored prompt injection (OWASP ASI06 Memory &amp; Context
 * Poisoning, LLM04) and a stored credential is a leak waiting for a search hit (LLM02). The read
 * path already screens tool output; this closes the write side with the same phrase families the
 * runtime guardrail hard-blocks plus the credential shapes it redacts.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li>{@link #credentialHit} - a credential or private key. Always refused, on every path.
 *   <li>{@link #injectionHit} - an instruction-shaped phrase. Refused for an AGENT-authored note
 *       ({@code remember_fact}, {@code add_to_knowledge_base}, graph triples); recorded as a
 *       {@code screen_flags} provenance marker (not refused) for bulk ingestion, where a regex hit
 *       in a long document is far more often a quotation, a security article or a prompt-
 *       engineering guide than an attack. Callers choose; this class only reports.
 * </ul>
 *
 * <h2>False positives</h2>
 *
 * It is a tripwire, not a classifier: a paraphrased injection passes, and legitimate text that
 * quotes an injection phrase (a note about prompt-injection research) is refused when an agent
 * writes it. The refusal message tells the model to store the fact rather than the directive, and
 * a person can always add the content through the upload path, where it is flagged, not refused.
 * Matches are never echoed back. This class is dependency-free so every module that persists text
 * can use it (the knowledge and graph-memory modules cannot depend on guardrails).
 */
public final class ContentWriteScreen {

    /** Flag value recorded when an instruction-shaped phrase matched. */
    public static final String FLAG_INJECTION = "injection_phrase";

    /** Flag value recorded when a credential shape matched (such content is refused, not stored). */
    public static final String FLAG_CREDENTIAL = "credential";

    private static final List<Pattern> INJECTION =
            List.of(
                    Pattern.compile(
                            "ignore\\s+(all\\s+)?(the\\s+)?(above|previous|prior)\\s+instructions",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "disregard\\s+(your|the)\\s+(system\\s+prompt|instructions)",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "reveal\\s+(your|the)\\s+system\\s+prompt", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "you\\s+are\\s+now\\s+in\\s+(developer|dan|jailbreak)\\s+mode",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "###\\s*(system|admin|override)\\s*###", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "(?m)^\\s*(system|assistant)\\s*:\\s*(you|ignore|from now)",
                            Pattern.CASE_INSENSITIVE));

    private static final List<Pattern> SECRETS =
            List.of(
                    Pattern.compile("AKIA[0-9A-Z]{16}"),
                    Pattern.compile("AIza[0-9A-Za-z\\-_]{35}"),
                    Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
                    Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
                    Pattern.compile("sk-[A-Za-z0-9]{20,}"),
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    Pattern.compile(
                            "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    /** The refusal wording for an injection hit; never echoes the match. */
    public static final String INJECTION_REFUSAL =
            "content looks like instructions aimed at a future model (prompt injection) "
                    + "and was not saved - store facts, not directives";

    /** The refusal wording for a credential hit; never echoes the match. */
    public static final String CREDENTIAL_REFUSAL =
            "content appears to contain a credential or private key and was not saved";

    private ContentWriteScreen() {}

    public static boolean injectionHit(String content) {
        return content != null && anyMatch(INJECTION, content);
    }

    public static boolean credentialHit(String content) {
        return content != null && anyMatch(SECRETS, content);
    }

    /**
     * A caller-safe reason an AGENT-authored note must not be stored, or empty when it passes.
     * Injection is checked first (the stricter signal to the model), then credentials.
     */
    public static Optional<String> refusal(String content) {
        if (injectionHit(content)) {
            return Optional.of(INJECTION_REFUSAL);
        }
        if (credentialHit(content)) {
            return Optional.of(CREDENTIAL_REFUSAL);
        }
        return Optional.empty();
    }

    /** The {@code FLAG_*} values that apply to {@code content}, in a stable order; empty = clean. */
    public static List<String> flags(String content) {
        List<String> out = new ArrayList<>(2);
        if (injectionHit(content)) {
            out.add(FLAG_INJECTION);
        }
        if (credentialHit(content)) {
            out.add(FLAG_CREDENTIAL);
        }
        return out;
    }

    private static boolean anyMatch(List<Pattern> patterns, String content) {
        for (Pattern p : patterns) {
            if (p.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }
}
