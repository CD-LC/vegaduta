package com.agenticai.core.agents.skills.learning;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes anything secret- or PII-shaped from text that is about to leave the turn (the transcript
 * given to the reflective model call) or enter the skill catalog (the draft it returns). The
 * platform's {@code SensitiveDataMasker} lives in the application module, which this one cannot
 * depend on, so it is plugged in through this interface; {@link #basic()} is always applied too, so
 * a context without that bean still scrubs.
 */
@FunctionalInterface
public interface SkillDraftScrubber {

    String scrub(String text);

    /** Replacement tokens the scrubbers write; {@link #redactions} counts them. */
    Pattern REDACTION_TOKEN = Pattern.compile("\\[(?:REDACTED|EMAIL|PHONE|IP|PATH|SECRET)\\]");

    static int redactions(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        var m = REDACTION_TOKEN.matcher(text);
        while (m.find()) {
            count++;
        }
        return count;
    }

    /**
     * Dependency-free scrubber: provider key shapes, bearer/basic credentials, private keys, JWTs,
     * {@code key=value} secrets, e-mail addresses, long opaque tokens and card-like digit runs.
     */
    static SkillDraftScrubber basic() {
        return Basic.INSTANCE;
    }

    final class Basic implements SkillDraftScrubber {

        static final Basic INSTANCE = new Basic();

        private static final List<Pattern> SECRETS =
                List.of(
                        Pattern.compile(
                                "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
                        Pattern.compile("AKIA[0-9A-Z]{16}"),
                        Pattern.compile("AIza[0-9A-Za-z\\-_]{35}"),
                        Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
                        Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
                        Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),
                        Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
                        Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{16,}"),
                        Pattern.compile("(?i)\\bauthorization\\s*[:=]\\s*\\S+(?:\\s+\\S+)?"),
                        // Any long opaque run (hex digests, base64 blobs, ids) is never general guidance.
                        Pattern.compile("\\b[A-Za-z0-9_+]{40,}={0,2}"));

        private static final Pattern KEY_VALUE =
                Pattern.compile(
                        "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|credential)\\b"
                                + "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
        private static final Pattern URL_CREDENTIALS =
                Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/:@]+:[^\\s/@]+@");
        private static final Pattern EMAIL =
                Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
        private static final Pattern CARD_LIKE = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");

        private Basic() {}

        @Override
        public String scrub(String text) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            String out = text;
            for (Pattern p : SECRETS) {
                out = p.matcher(out).replaceAll("[REDACTED]");
            }
            out = KEY_VALUE.matcher(out).replaceAll("$1$2[REDACTED]");
            out = URL_CREDENTIALS.matcher(out).replaceAll("$1[REDACTED]@");
            out = EMAIL.matcher(out).replaceAll("[EMAIL]");
            return CARD_LIKE.matcher(out).replaceAll("[REDACTED]");
        }
    }
}
