package com.agenticai.core.graphmemory;

import java.util.regex.Pattern;

/**
 * Allowlist-style encoder for values interpolated into AGE Cypher query text - replaces the old
 * strip-a-blocklist defense ({@code UNSAFE_CHARS = ['"\$;]}) that {@link GraphMemoryService}
 * previously used.
 *
 * <p><b>Why escaping, not parameterization:</b> Apache AGE's {@code ag_catalog.cypher(graph,
 * query)} function takes the Cypher query text as a literal argument - there is no way to bind
 * the inner query's values as parameters from JDBC (AGE's own prepared-statement parameter
 * support applies to the third {@code agtype} argument, which this codebase's plain-{@code
 * Statement} usage predates and which still would not cover every site here). So every
 * external-origin value (LLM-extracted entity names, subject keys, recall hints) is textually
 * embedded, and the only defense is making that embedding provably inert. That is what this
 * class does, in three layers:
 *
 * <ol>
 *   <li><b>Strip invisibles outright</b> - every code point in Unicode category {@code C}
 *       (control chars, zero-width/bidi/format chars like U+200B/U+202E/U+FEFF, unassigned,
 *       private-use, unpaired surrogates). These have no legitimate place in an entity name and
 *       are the classic smuggling vector for making an injection payload invisible in logs and
 *       reviews. Stripping (not escaping) is deliberate here: there is nothing to preserve.</li>
 *   <li><b>Cap length</b> at {@link #MAX_VALUE_LENGTH} code points (truncate, not reject -
 *       graph writes fail open by design in this module, and a silently-dropped fact is worse
 *       than a truncated one). Counted in code points so a surrogate pair is never split.</li>
 *   <li><b>Escape, never strip, the syntactically meaningful characters</b> - {@code \} becomes
 *       {@code \\}, {@code '} becomes {@code \'}, {@code "} becomes {@code \"} (all standard
 *       openCypher string-literal escapes), so legitimate names like {@code O'Brien} or Windows
 *       paths survive round-trip instead of being corrupted the way the old strip-based
 *       defense corrupted them. {@code $} gets special treatment: it is encoded as the Cypher
 *       backslash-u0024 unicode escape (spelled longhand here - the Java compiler pre-lexes
 *       unicode escapes even inside comments), because the Cypher text is itself embedded in a
 *       Postgres dollar-quoted SQL literal ({@code $$ ... $$}) and dollar-quoting has NO escape
 *       mechanism - a literal {@code $$} inside a value would terminate the SQL string no
 *       matter how correctly it was escaped at the Cypher layer. Encoding the dollar sign away
 *       entirely closes both that SQL-layer break-out and any {@code $param} spoofing in one
 *       move, while the stored property value still round-trips to a real {@code $}.</li>
 * </ol>
 *
 * <p><b>Residual risk, stated honestly:</b> this is escaping-not-parameterization, so its
 * correctness rests on AGE's Cypher lexer actually implementing the openCypher string-literal
 * escapes (backslash-backslash, backslash-quote, backslash-uXXXX; the literal sequences are
 * spelled out longhand here because a Java compiler pre-lexes unicode escapes even inside
 * comments) per spec - a divergence there would be a gap this class cannot see. Like
 * everything else in this module, that is the kind of
 * claim that needs a real AGE-enabled Postgres to verify end to end, not just static reasoning
 * (see {@link GraphMemoryService}'s class javadoc for the same disclosed ceiling). Also, the
 * category-{@code C} strip uses this JDK's Unicode tables, so a code point the JDK doesn't know
 * yet (category {@code Cn}) is stripped even if a newer Unicode version assigns it - a
 * conservative-direction error.
 */
final class CypherLiteralEscaper {

    /**
     * Per-value cap, in code points. The module had no existing per-value cap to inherit
     * (checked: {@code EntityExtractionService} and the tool callbacks only {@code trim()});
     * 512 comfortably covers any legitimate entity name / subject key / recall hint while
     * bounding how much attacker-shaped text can ever reach the query builder.
     */
    static final int MAX_VALUE_LENGTH = 512;

    /** Unicode category C: control, format (zero-width/bidi/BOM), surrogate, private-use, unassigned. */
    private static final Pattern INVISIBLE_OR_CONTROL = Pattern.compile("\\p{C}");

    /**
     * Identifiers (labels, relationship types, property keys) CANNOT be escaped safely in
     * Cypher - there is no spec-guaranteed escape inside a backtick-quoted identifier that
     * every implementation honors - so they are allowlisted outright instead: leading
     * letter/underscore, then {@code [A-Za-z0-9_]}, max 63 chars (Postgres's identifier limit,
     * the tightest layer involved). No call site passes a dynamic identifier today (every
     * label/type/key in {@link GraphMemoryService} is a compile-time literal); this exists so
     * the first future dynamic identifier has a correct gate to go through rather than being
     * tempted through {@link #escapeCypherString}, which is NOT safe for identifiers.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private CypherLiteralEscaper() {
        // utility class
    }

    /**
     * Encodes one external-origin value for embedding between single quotes in a Cypher string
     * literal that itself sits inside a Postgres {@code $$ ... $$} dollar-quoted literal. Never
     * returns null; a null input encodes to the empty string (matching the fail-open posture of
     * the callers - a missing value must degrade, not throw, mid-chat-turn).
     */
    static String escapeCypherString(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = INVISIBLE_OR_CONTROL.matcher(value).replaceAll("");
        cleaned = truncateToCodePoints(cleaned, MAX_VALUE_LENGTH);
        StringBuilder out = new StringBuilder(cleaned.length() + 8);
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '"' -> out.append("\\\"");
                case '$' -> out.append("\\u0024");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Gate for any dynamic Cypher identifier (label / relationship type / property key). Returns
     * the candidate unchanged if it matches the strict allowlist, otherwise throws - identifiers
     * fail CLOSED, unlike values, because a rejected identifier means the caller's query shape
     * itself is attacker-influenced and no "degraded" version of that query is safe to run.
     * The exception message deliberately carries only a sanitized preview of the candidate, so a
     * rejected payload can't smuggle itself into logs verbatim.
     */
    static String safeIdentifier(String candidate) {
        if (candidate == null || !SAFE_IDENTIFIER.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                "Unsafe Cypher identifier rejected (allowlist [A-Za-z_][A-Za-z0-9_]{0,62}): "
                    + previewForLog(candidate)
            );
        }
        return candidate;
    }

    private static String previewForLog(String candidate) {
        if (candidate == null) {
            return "<null>";
        }
        String stripped = INVISIBLE_OR_CONTROL.matcher(candidate).replaceAll("");
        return stripped.length() <= 40 ? stripped : stripped.substring(0, 40) + "...";
    }

    private static String truncateToCodePoints(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
