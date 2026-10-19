package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatImportFields.addAttachment;
import static com.agenticai.core.agents.chatport.ChatImportFields.cleanId;
import static com.agenticai.core.agents.chatport.ChatImportFields.cleanTitle;
import static com.agenticai.core.agents.chatport.ChatImportFields.parseIso;
import static com.agenticai.core.agents.chatport.ChatImportFields.text;
import static com.agenticai.core.agents.chatport.ChatImportParser.ROLE_ASSISTANT;
import static com.agenticai.core.agents.chatport.ChatImportParser.ROLE_USER;
import static com.agenticai.core.agents.chatport.ChatImportParser.SOURCE_CHATGPT;
import static com.agenticai.core.agents.chatport.ChatImportParser.SOURCE_CLAUDE;
import static com.agenticai.core.agents.chatport.ChatImportParser.SOURCE_VEGADUTA;

import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * One conversation of this platform's own export ({@link ChatExportRenderer}'s JSON): {@code {id, source, title,
 * createdAt, truncated, messages[{role, content, timestamp, attachments[]}]}}. Messages are already in order; the same
 * rules as the other formats apply - only {@code user} / {@code assistant} text is kept, attachments are names only,
 * text is cleaned and capped. An export file is as untrusted as any other upload.
 */
final class VegaDutaImportFormat implements ChatImportFormat {

    @Override
    public boolean matches(JsonNode conv) {
        return conv.path("messages").isArray();
    }

    /**
     * A conversation that was itself imported from ChatGPT / Claude keeps that source (and so de-duplicates against
     * the original import); a conversation held here is "vegaduta".
     */
    @Override
    public String source(JsonNode conv) {
        String declared = text(conv, "source");
        return SOURCE_CHATGPT.equals(declared) || SOURCE_CLAUDE.equals(declared) ? declared : SOURCE_VEGADUTA;
    }

    @Override
    public String sourceId(JsonNode conv) {
        return cleanId(text(conv, "id"), null);
    }

    @Override
    public ParsedChat convert(JsonNode conv, String key, String source, String sourceId, ChatImportCollector c) {
        JsonNode msgs = conv.get("messages");
        if (msgs == null || !msgs.isArray()) {
            return null;
        }
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(c.limits, c.keepMessages);
        for (JsonNode m : msgs) {
            if (!m.isObject()) {
                continue;
            }
            String role = text(m, "role");
            if (!ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
                continue;
            }
            String raw = text(m, "content");
            String body = ChatPortText.clean(raw == null ? "" : raw);
            List<String> attachments = new ArrayList<>();
            JsonNode names = m.get("attachments");
            if (names != null && names.isArray()) {
                for (JsonNode name : names) {
                    if (name.isTextual()) {
                        addAttachment(attachments, name.asText());
                    }
                }
            }
            if (body.isBlank() && attachments.isEmpty()) {
                continue;
            }
            if (!b.add(role, body, parseIso(text(m, "timestamp")), attachments)) {
                break;
            }
        }
        if (b.isEmpty()) {
            return null;
        }
        String title = cleanTitle(text(conv, "title"), b.firstUserText());
        return b.build(key, source, sourceId, title, parseIso(text(conv, "createdAt")), null,
            conv.path("truncated").asBoolean(false));
    }
}
