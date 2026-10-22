package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_ATTACHMENTS_PER_MESSAGE;
import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_ATTACHMENT_NAME_CHARS;
import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_ID_CHARS;
import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_TITLE_CHARS;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Field readers and cleaners shared by every {@link ChatImportFormat}. Every value read here is
 * untrusted.
 */
final class ChatImportFields {

    /** 2100-01-01T00:00:00Z: a conversation timestamp beyond this is garbage, not a date. */
    private static final double MAX_EPOCH_SECONDS = 4_102_444_800d;

    private ChatImportFields() {}

    static void addAttachment(List<String> out, String rawName) {
        if (rawName == null || out.size() >= MAX_ATTACHMENTS_PER_MESSAGE) {
            return;
        }
        String name =
                ChatPortText.truncate(
                        ChatPortText.oneLine(ChatPortText.clean(rawName)),
                        MAX_ATTACHMENT_NAME_CHARS,
                        false);
        if (!name.isEmpty()) {
            out.add(name);
        }
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    static String cleanId(String primary, String fallback) {
        String raw = primary != null && !primary.isBlank() ? primary : fallback;
        if (raw == null) {
            return null;
        }
        String id =
                ChatPortText.truncate(
                        ChatPortText.oneLine(ChatPortText.clean(raw)), MAX_ID_CHARS, false);
        return id.isEmpty() ? null : id;
    }

    static String cleanTitle(String raw, String firstUserText) {
        String t = raw == null ? "" : ChatPortText.oneLine(ChatPortText.clean(raw));
        if (t.isEmpty() && firstUserText != null) {
            t = ChatPortText.oneLine(firstUserText);
        }
        if (t.isEmpty()) {
            t = "Untitled chat";
        }
        return ChatPortText.truncate(t, MAX_TITLE_CHARS, false);
    }

    /**
     * ChatGPT timestamps are epoch seconds (a double); anything non-finite, non-positive or past
     * 2100 is dropped.
     */
    static Instant epochSeconds(JsonNode v) {
        if (v == null || !v.isNumber()) {
            return null;
        }
        double d = v.asDouble();
        if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0 || d > MAX_EPOCH_SECONDS) {
            return null;
        }
        return Instant.ofEpochMilli(Math.round(d * 1000d));
    }

    static Instant parseIso(String s) {
        if (s == null || s.isBlank() || s.length() > 40) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}
