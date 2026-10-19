package com.agenticai.core.chat;

/**
 * Turns whatever the model actually replied into something an editor can
 * paste at the cursor - the other pure, unit-testable half of {@link
 * FimCompletionService}.
 *
 * <p>Needed because the hosted pool has no infill endpoint (see {@link
 * FimTemplate}'s delivery caveat): the completion comes back from a CHAT
 * model, so it arrives with chat habits attached. The three that actually
 * break a paste, in the order they are undone here:
 *
 * <ol>
 *   <li><b>Markdown fences.</b> An instruct model wraps code in {@code ```}
 *       roughly whenever it feels like it, system prompt or not. A fenced
 *       reply pasted verbatim produces three backticks in the user's
 *       source.</li>
 *   <li><b>Echoed prefix.</b> The model re-states the line it was given
 *       before continuing it, which duplicates that text at the cursor.</li>
 *   <li><b>Echoed suffix.</b> The model runs on and re-types the code that
 *       already follows the cursor, which duplicates it below.</li>
 * </ol>
 *
 * <p>Echo removal is deliberately a longest-overlap match rather than a
 * fuzzy one: only text that is character-identical to the tail of the prefix
 * (or the head of the suffix) is removed. A near-miss is left alone - a
 * slightly duplicated line is a visible, fixable annoyance, whereas
 * over-eager trimming silently eats real completion text and the user never
 * learns what was dropped.
 *
 * <p>Leading whitespace is never trimmed (it is the completion's
 * indentation); trailing whitespace is, apart from a single trailing
 * newline, which is usually the model correctly ending the statement.
 */
final class FimResponseCleaner {

    /**
     * How far back into the prefix / forward into the suffix an echo is
     * looked for. Bounded so cleanup stays O(window) per request in a
     * keystroke loop rather than O(file).
     */
    static final int MAX_ECHO_OVERLAP_CHARS = 512;

    private FimResponseCleaner() {
    }

    /**
     * @return the pasteable span, or {@code ""} when nothing usable survived.
     *     Never null - an empty completion is this endpoint's normal "no
     *     suggestion" answer, not an error.
     */
    static String clean(String raw, String prefix, String suffix, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = stripFences(raw);
        text = stripEchoedPrefix(text, prefix);
        text = stripEchoedSuffix(text, suffix);
        text = stripTrailingWhitespaceKeepingOneNewline(text);
        if (text.isBlank()) {
            return "";
        }
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, maxChars);
        }
        return text;
    }

    /**
     * Unwraps ONE surrounding markdown fence, including the optional
     * language tag on the opening line ({@code ```java}). A reply that opens
     * a fence but never closes it is still unwrapped - truncation at the
     * token cap is the usual reason, and the opening backticks must not
     * reach the editor either way. Text with no fence is returned unchanged,
     * and a fence that starts mid-reply is left alone (that is content, not
     * a wrapper).
     */
    static String stripFences(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return raw;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            // A whole reply of "```java" and nothing else - no content at all.
            return "";
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        // Strip the newline the closing fence sat on, but keep the body's own
        // leading indentation.
        return body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
    }

    /**
     * Removes the longest tail of {@code prefix} that the completion
     * repeats at its start.
     */
    static String stripEchoedPrefix(String completion, String prefix) {
        if (completion.isEmpty() || prefix == null || prefix.isEmpty()) {
            return completion;
        }
        int window = Math.min(Math.min(prefix.length(), completion.length()), MAX_ECHO_OVERLAP_CHARS);
        for (int len = window; len > 0; len--) {
            if (completion.startsWith(prefix.substring(prefix.length() - len))) {
                return completion.substring(len);
            }
        }
        return completion;
    }

    /**
     * Removes the longest head of {@code suffix} that the completion
     * repeats at its end.
     */
    static String stripEchoedSuffix(String completion, String suffix) {
        if (completion.isEmpty() || suffix == null || suffix.isEmpty()) {
            return completion;
        }
        int window = Math.min(Math.min(suffix.length(), completion.length()), MAX_ECHO_OVERLAP_CHARS);
        for (int len = window; len > 0; len--) {
            if (completion.endsWith(suffix.substring(0, len))) {
                return completion.substring(0, completion.length() - len);
            }
        }
        return completion;
    }

    /** Trailing spaces/tabs/newlines collapsed to at most one newline. */
    static String stripTrailingWhitespaceKeepingOneNewline(String text) {
        int end = text.length();
        boolean sawNewline = false;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            sawNewline |= text.charAt(end - 1) == '\n';
            end--;
        }
        if (end == 0) {
            return "";
        }
        return sawNewline ? text.substring(0, end) + "\n" : text.substring(0, end);
    }
}
