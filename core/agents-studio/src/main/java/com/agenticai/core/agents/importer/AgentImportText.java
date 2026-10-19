package com.agenticai.core.agents.importer;

import java.util.regex.Pattern;

/**
 * Text helpers for imported content. Pure and total (never throws on null or odd input): everything
 * here runs on untrusted payload text. Nothing in this class interprets the text - it only trims,
 * strips and caps it.
 */
public final class AgentImportText {

    private AgentImportText() {
    }

    /** Longest name the importer produces (the agent column allows 255; shorter reads better in lists). */
    public static final int NAME_MAX = 100;

    private static final Pattern BLOCK_BREAK =
        Pattern.compile("(?i)</p\\s*>|<br\\s*/?>|</li\\s*>|</div\\s*>|</h[1-6]\\s*>");
    private static final Pattern HTML_HINT = Pattern.compile("(?i)</p\\s*>|<p[ >]|<br\\s*/?>|<li[ >]|<ul[ >]|<strong>");
    // Bounded body so a long run of '<' with no '>' cannot degrade to quadratic scanning.
    private static final Pattern TAG = Pattern.compile("<[^<>]{0,1000}>");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern WS = Pattern.compile("\\s+");

    /** {@code s} cut to at most {@code max} chars without splitting a surrogate pair; null-safe. */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * A safe agent name: control characters and angle brackets removed, whitespace collapsed, trimmed,
     * capped at {@link #NAME_MAX}. Falls back to {@code fallback} when nothing printable is left.
     */
    public static String sanitizeName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = CONTROL.matcher(raw).replaceAll(" ").replace('<', ' ').replace('>', ' ');
        s = WS.matcher(s).replaceAll(" ").trim();
        s = truncate(s, NAME_MAX).trim();
        return s.isEmpty() ? fallback : s;
    }

    /** Inline label for report lines and logs: one line, no control chars, capped. */
    public static String inline(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String s = CONTROL.matcher(raw).replaceAll(" ");
        s = WS.matcher(s).replaceAll(" ").trim();
        return truncate(s, max);
    }

    /** Short, single-line form of untrusted text that is safe to put in a log line. Never log a whole payload. */
    public static String forLog(String raw) {
        return inline(raw, 120);
    }

    /**
     * Converts the rich-text HTML Flowise's agentflow editor stores ({@code <p>..</p>}, mention spans)
     * to plain text. Text that does not look like HTML is returned untouched, because a plain prompt
     * may legitimately contain XML-ish tags that must survive.
     */
    public static String htmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        if (!HTML_HINT.matcher(html).find()) {
            return html;
        }
        String s = BLOCK_BREAK.matcher(html).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&");
        s = MANY_NEWLINES.matcher(s).replaceAll("\n\n");
        return s.trim();
    }
}
