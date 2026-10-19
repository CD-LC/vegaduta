package com.agenticai.core.mcp.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Local matching + ranking for "Browse registry", over the catalog snapshot
 * {@link OfficialMcpRegistryClient} keeps in memory. Pure and deterministic -
 * no I/O, no Spring state - so the matching rules are unit-testable on their
 * own, same reasoning as {@link RegistryServerMapper}.
 *
 * <p><b>Why not just forward the query upstream.</b> The registry's own {@code
 * ?search=} parameter is documented as substring matching <i>on the server
 * name only</i>. That is far too narrow to be what a person means when they
 * type into a search box: "transcript" misses a server called {@code
 * com.example/media-tools} whose description is all about transcripts, and
 * multi-word queries ("youtube transcript") match nothing at all because no
 * single name contains that string. Matching here instead means the query
 * runs against name, title, description and repository URL together.
 *
 * <p><b>Rules.</b> The query is split on whitespace and every term must match
 * (AND), each as a case-insensitive substring. {@code *} (any run of
 * characters) and {@code ?} (any single character) are honoured inside a term,
 * so {@code you*be} and {@code slac?} work; everything else is matched
 * literally, so a query containing {@code .} or {@code /} or {@code +} can't
 * turn into a surprise regex. An unmatchable term simply yields no results
 * rather than throwing.
 *
 * <p><b>Ranking.</b> Name/title hits sort above description-only hits (someone
 * typing "github" wants the GitHub server, not the 40 servers whose
 * description mentions GitHub), with a stable alphabetical tiebreak so
 * paging through results never reorders under the caller.
 */
final class RegistryQueryMatcher {

    private RegistryQueryMatcher() {
    }

    /**
     * Compiles a user query into terms. Blank query -> empty list, which
     * {@link #matches} treats as "match everything" (the browse-all case).
     */
    static List<Pattern> compile(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Pattern> terms = new ArrayList<>();
        for (String raw : query.trim().split("\\s+")) {
            if (raw.isBlank()) {
                continue;
            }
            terms.add(Pattern.compile(toRegex(raw), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(terms);
    }

    /** True when every term matches somewhere in the result's searchable text. */
    static boolean matches(RegistrySearchResult result, List<Pattern> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        String haystack = haystack(result);
        for (Pattern term : terms) {
            if (!term.matcher(haystack).find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lower is better. 0 = every term is in the name/title, 1 = some terms
     * only matched the description or repo URL.
     */
    static int rank(RegistrySearchResult result, List<Pattern> terms) {
        if (terms.isEmpty()) {
            return 1;
        }
        String identity = nullSafe(result.name()) + " " + nullSafe(result.displayName());
        for (Pattern term : terms) {
            if (!term.matcher(identity).find()) {
                return 1;
            }
        }
        return 0;
    }

    /** Stable secondary sort key, so equal-rank results never shuffle between pages. */
    static String sortKey(RegistrySearchResult result) {
        String display = nullSafe(result.displayName());
        return (display.isBlank() ? nullSafe(result.name()) : display).toLowerCase(Locale.ROOT);
    }

    private static String haystack(RegistrySearchResult result) {
        return nullSafe(result.name()) + " "
            + nullSafe(result.displayName()) + " "
            + nullSafe(result.description()) + " "
            + nullSafe(result.repositoryUrl());
    }

    /**
     * Escapes the term literally, then re-enables {@code *} and {@code ?} as
     * wildcards. Done by quoting each literal run with {@link
     * Pattern#quote} rather than escaping character-by-character, so no
     * regex metacharacter can survive into the compiled pattern.
     */
    private static String toRegex(String term) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        // Unanchored: Matcher#find already means "appears anywhere", which is
        // the substring behaviour a search box implies.
        return regex.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
