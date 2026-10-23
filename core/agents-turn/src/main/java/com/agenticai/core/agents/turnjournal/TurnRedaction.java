package com.agenticai.core.agents.turnjournal;

import com.agenticai.core.security.TextRedaction;
import java.util.regex.Pattern;

/**
 * Makes a free-text fragment safe for a journal summary: collapses whitespace, masks emails (via
 * the platform's LogMasking convention), bearer tokens, key-like tokens and long digit runs, and
 * truncates. Summaries are meant to be structural ("tool=send_email status=ok"), never content.
 */
final class TurnRedaction {

    static final int MAX_SUMMARY = 240;

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern KEYLIKE =
            Pattern.compile("\\b(?:sk|pk|rk|key|tok|ghp|xox[a-z])[-_][A-Za-z0-9_-]{8,}\\b");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{9,}");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{32,}={0,2}");

    private TurnRedaction() {}

    static String summary(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        s = BEARER.matcher(s).replaceAll("[redacted]");
        s = TextRedaction.redactEmails(s, "[email]");
        s = KEYLIKE.matcher(s).replaceAll("[redacted]");
        s = LONG_TOKEN.matcher(s).replaceAll("[redacted]");
        s = LONG_DIGITS.matcher(s).replaceAll("[number]");
        return s.length() > MAX_SUMMARY ? s.substring(0, MAX_SUMMARY) : s;
    }
}
