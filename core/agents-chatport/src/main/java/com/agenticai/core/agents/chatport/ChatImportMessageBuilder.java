package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatImportParser.FIRST_USER_TEXT_CHARS;
import static com.agenticai.core.agents.chatport.ChatImportParser.ROLE_USER;
import static com.agenticai.core.agents.chatport.ChatImportParser.TRUNCATED_MARK;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Accumulates one conversation's messages under the per-chat caps; shared by every {@link ChatImportFormat}. */
final class ChatImportMessageBuilder {

    private final Limits limits;
    private final boolean keep;
    private final List<ParsedMessage> messages = new ArrayList<>();
    private int count;
    private long chars;
    private boolean truncated;
    private String firstUserText;

    ChatImportMessageBuilder(Limits limits, boolean keep) {
        this.limits = limits;
        this.keep = keep;
    }

    /** @return false once the per-chat caps are hit and nothing more should be added */
    boolean add(String role, String content, Instant ts, List<String> attachments) {
        if (count >= limits.maxMessagesPerChat() || chars >= limits.maxCharsPerChat()) {
            truncated = true;
            return false;
        }
        String body = content == null ? "" : content;
        int perMessage = Math.max(1, limits.maxCharsPerMessage());
        if (body.length() > perMessage) {
            body = ChatPortText.truncate(body, perMessage, false) + TRUNCATED_MARK;
            truncated = true;
        }
        long room = limits.maxCharsPerChat() - chars;
        if (body.length() > room) {
            body = ChatPortText.truncate(body, (int) Math.max(0, room), false) + TRUNCATED_MARK;
            truncated = true;
        }
        count++;
        chars += body.length();
        if (ROLE_USER.equals(role) && firstUserText == null && !body.isBlank()) {
            firstUserText = ChatPortText.truncate(body, FIRST_USER_TEXT_CHARS, false);
        }
        if (keep) {
            messages.add(new ParsedMessage(role, body, ts, attachments == null ? List.of() : List.copyOf(attachments)));
        }
        return true;
    }

    boolean isEmpty() {
        return count == 0;
    }

    String firstUserText() {
        return firstUserText;
    }

    /**
     * The finished chat. {@code extraTruncated} marks it truncated even when no cap here was hit (a re-imported
     * export that was already truncated).
     */
    ParsedChat build(String key, String source, String sourceId, String title, Instant createdAt, Instant updatedAt,
        boolean extraTruncated) {
        return new ParsedChat(key, source, sourceId, title, createdAt, updatedAt, messages, count, chars, firstUserText,
            truncated || extraTruncated);
    }
}
