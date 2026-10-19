package com.agenticai.core.agents.chatport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, model-free pass over the USER side of imported chats that picks out first-person statements
 * ("I live in Pune", "My stack is Spring and Postgres") as <em>candidate</em> memory lines. It never saves
 * anything: the lines are handed to the existing memory-import preview (which screens for secrets, injection and
 * duplicates) and the user ticks what to keep. Conservative on purpose - a question, a very short or very long
 * sentence, or anything not clearly about the user is dropped.
 */
final class ChatMemoryCandidateExtractor {

    static final int MIN_CHARS = 12;
    static final int MAX_CHARS = 240;
    private static final int MAX_MESSAGE_CHARS = 6000;

    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    private static final Pattern SELF_STATEMENT = Pattern.compile(
        "^(?:i am (?:a|an|the|from|based|currently|learning|building|working|studying|looking)\\b"
            + "|i'm (?:a|an|the|from|based|currently|learning|building|working|studying|looking)\\b"
            + "|i live\\b|i work\\b|i use\\b|i prefer\\b|i speak\\b|i study\\b|i code\\b|i program\\b|i write\\b"
            + "|i (?:really )?(?:like|love|enjoy|hate|dislike)\\b|i don't like\\b|i do not like\\b"
            + "|i have (?:a|an|two|three|\\d+)\\b|i was born\\b|i go by\\b"
            + "|my (?:name|job|role|title|company|employer|team|stack|favorite|favourite|goal|project|business|startup"
            + "|website|city|country|timezone|time zone|birthday|pronouns|language|editor|os)\\b"
            + "|call me\\b|please call me\\b)",
        Pattern.CASE_INSENSITIVE);

    private ChatMemoryCandidateExtractor() {
    }

    /**
     * @param userMessages the user-role message texts of the chosen chats, in any order
     * @param maxLines     stop after this many candidates
     * @param maxChars     stop once the candidates together (one per line) would exceed this many characters
     * @return distinct candidate sentences, in first-seen order
     */
    static List<String> extract(List<String> userMessages, int maxLines, int maxChars) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long chars = 0;
        if (userMessages == null) {
            return out;
        }
        for (String message : userMessages) {
            if (message == null || message.isBlank()) {
                continue;
            }
            String clipped = ChatPortText.truncate(message, MAX_MESSAGE_CHARS, false);
            for (String raw : SENTENCES.split(clipped)) {
                String s = ChatPortText.oneLine(ChatPortText.clean(raw));
                if (s.length() < MIN_CHARS || s.length() > MAX_CHARS || s.endsWith("?")) {
                    continue;
                }
                if (!SELF_STATEMENT.matcher(s).find()) {
                    continue;
                }
                String key = s.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                if (out.size() >= maxLines || chars + s.length() + 1 > maxChars) {
                    return out;
                }
                chars += s.length() + 1;
                out.add(s);
            }
        }
        return out;
    }
}
