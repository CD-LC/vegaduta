package com.agenticai.core.agents.hooks;

/**
 * Pure matching: does a hook's {@link HookMatch} select a given tool call. No I/O - the
 * classification facts arrive as a {@link ToolFacts} the caller resolved (and memoised).
 */
public final class HookMatcher {

    private HookMatcher() {
    }

    /**
     * What a hook can match on besides the tool name. {@link #UNCLASSIFIED} stands for a tool
     * with no declaration (most MCP and tenant-authored tools): it matches only the
     * {@code UNCLASSIFIED} sentinel, never a real tier, so a "block EGRESS" hook cannot be read as
     * covering a tool nobody classified - authors who want that say so explicitly.
     *
     * <p>{@link #UNKNOWN} is the answer when the classification lookup itself failed: a hook that constrains on
     * classification then matches, because "could not tell" must never read as "does not apply" to a rule whose
     * only job is to tighten.
     */
    public record ToolFacts(boolean classified, String trust, String sensitivity, boolean irreversible, boolean lookupFailed) {

        public static final ToolFacts UNCLASSIFIED = new ToolFacts(false, null, null, false, false);
        public static final ToolFacts UNKNOWN = new ToolFacts(false, null, null, false, true);

        public static ToolFacts classified(String trust, String sensitivity, boolean irreversible) {
            return new ToolFacts(true, trust, sensitivity, irreversible, false);
        }
    }

    /**
     * Glob match over a tool name: {@code *} matches any run of characters (including none),
     * {@code ?} exactly one; everything else is literal and case-sensitive. Iterative with
     * single-star backtracking, so it is linear-ish and cannot blow the stack on a long name.
     */
    public static boolean globMatches(String glob, String name) {
        if (glob == null || name == null) {
            return false;
        }
        int g = 0;
        int n = 0;
        int starG = -1;
        int starN = -1;
        while (n < name.length()) {
            if (g < glob.length() && glob.charAt(g) == '*') {
                starG = g++;
                starN = n;
            } else if (g < glob.length() && (glob.charAt(g) == '?' || glob.charAt(g) == name.charAt(n))) {
                g++;
                n++;
            } else if (starG >= 0) {
                g = starG + 1;
                n = ++starN;
            } else {
                return false;
            }
        }
        while (g < glob.length() && glob.charAt(g) == '*') {
            g++;
        }
        return g == glob.length();
    }

    /**
     * @param toolName the tool being called; {@code null} for a run-level event with no tool
     * @param facts classification facts; only consulted when the match constrains on them, and may be
     *     {@code null} otherwise
     */
    public static boolean matches(HookDefinition hook, String toolName, ToolFacts facts) {
        HookMatch m = hook.match();
        if (m.tool() != null) {
            if (toolName == null || !globMatches(m.tool(), toolName)) {
                return false;
            }
        } else if (toolName == null && hook.point().isToolPoint()) {
            return false;
        }
        if (!m.needsClassification()) {
            return true;
        }
        if (toolName == null) {
            return false;
        }
        ToolFacts f = facts == null ? ToolFacts.UNCLASSIFIED : facts;
        if (f.lookupFailed()) {
            return true;
        }
        if (m.trust() != null && !fieldMatches(m.trust(), f.classified(), f.trust())) {
            return false;
        }
        if (m.sensitivity() != null && !fieldMatches(m.sensitivity(), f.classified(), f.sensitivity())) {
            return false;
        }
        if (m.irreversible() != null) {
            return f.classified() && f.irreversible() == m.irreversible();
        }
        return true;
    }

    private static boolean fieldMatches(String wanted, boolean classified, String actual) {
        if (HookMatch.UNCLASSIFIED.equals(wanted)) {
            return !classified;
        }
        return classified && wanted.equals(actual);
    }
}
