package com.agenticai.core.agents.learning.lessons;

import com.agenticai.core.agents.tools.ToolCallLog;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The cheap pre-filter of the lessons loop: pure functions over a turn's tool calls and the user's
 * next message, no I/O and no model. Most turns match neither signal and cost nothing further.
 */
final class LessonSignals {

    /** A tool call that failed and a later call of the same tool, with other arguments, that worked. */
    record RetryFix(ToolCallLog failed, ToolCallLog fixed) {}

    /** How far after a failure a retry still counts as that failure's fix. */
    static final int RETRY_WINDOW = 6;

    /** A correction is a short remark; a long message is a new request. */
    static final int MAX_CORRECTION_CHARS = 600;

    /** Failures that say nothing about the arguments: retrying them is luck, not learning. */
    private static final Pattern TRANSIENT =
            Pattern.compile(
                    "(?i)time(?:d)?[ -]?out|rate.?limit|too many requests|\\b429\\b|\\b50[234]\\b"
                            + "|temporar|unavailable|connection (?:reset|refused|closed)|try again later|cancel");

    private static final List<Pattern> CORRECTION_CUES =
            List.of(
                    Pattern.compile(
                            "^\\s*(?:no|nope|nah)\\b[,.!:;\\s-]+(?:i|that|it|the|use|not|this|you|my|please|wrong|it's|its)\\b"),
                    Pattern.compile("^\\s*(?:wrong|incorrect)\\b"),
                    Pattern.compile(
                            "\\bthat(?:'s|\\u2019s| is| was)\\s+(?:wrong|incorrect|not (?:right|correct|what)|not it)\\b"),
                    Pattern.compile(
                            "\\bthat\\s+(?:isn't|isn\\u2019t|is not|wasn't|was not)\\s+(?:right|correct|what)\\b"),
                    Pattern.compile("\\b(?:i|we) (?:actually )?meant\\b"),
                    Pattern.compile("\\bnot what i (?:asked|meant|wanted|said)\\b"),
                    Pattern.compile("\\bi (?:said|asked for|told you)\\b"),
                    Pattern.compile(
                            "\\byou (?:misunderstood|got (?:it|that|this) wrong|made a mistake|forgot|missed)\\b"),
                    Pattern.compile("\\buse\\b.{1,60}\\binstead\\b"),
                    Pattern.compile("\\binstead,? (?:use|do|try|send|pick|take)\\b"),
                    Pattern.compile("\\bplease (?:don't|do not|never|stop)\\b"),
                    Pattern.compile(
                            "\\b(?:don't|do not|never) (?:use|do|send|pick|call|put)\\b.{0,40}\\b(?:that|this|it)\\b"),
                    Pattern.compile("\\bshould (?:have|be|use)\\b"),
                    Pattern.compile("\\bactually,? (?:it|the|use|my|that|i)\\b"));

    private LessonSignals() {}

    /** True when the user's message reads as a correction of the previous answer. */
    static boolean looksLikeCorrection(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String m = userMessage.strip().toLowerCase(Locale.ROOT);
        if (m.length() < 6 || m.length() > MAX_CORRECTION_CHARS) {
            return false;
        }
        for (Pattern p : CORRECTION_CUES) {
            if (p.matcher(m).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The last failure in {@code calls} (oldest first) that a later call of the same tool with
     * different arguments fixed. A failure whose message looks transient does not count.
     */
    static Optional<RetryFix> retriedFix(List<ToolCallLog> calls) {
        if (calls == null || calls.size() < 2) {
            return Optional.empty();
        }
        RetryFix found = null;
        for (int i = 0; i < calls.size(); i++) {
            ToolCallLog failed = calls.get(i);
            if (!failed(failed) || looksTransient(failed)) {
                continue;
            }
            int end = Math.min(calls.size(), i + 1 + RETRY_WINDOW);
            for (int j = i + 1; j < end; j++) {
                ToolCallLog later = calls.get(j);
                if (!failed(later)
                        && failed.getToolName() != null
                        && failed.getToolName().equals(later.getToolName())
                        && !normalised(failed.getToolInput()).equals(normalised(later.getToolInput()))) {
                    found = new RetryFix(failed, later);
                    break;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    static boolean failed(ToolCallLog c) {
        return c != null && c.getErrorMessage() != null && !c.getErrorMessage().isBlank();
    }

    private static boolean looksTransient(ToolCallLog c) {
        return TRANSIENT.matcher(c.getErrorMessage()).find();
    }

    private static String normalised(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
