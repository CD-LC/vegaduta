package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatImportFields.addAttachment;
import static com.agenticai.core.agents.chatport.ChatImportFields.cleanId;
import static com.agenticai.core.agents.chatport.ChatImportFields.cleanTitle;
import static com.agenticai.core.agents.chatport.ChatImportFields.parseIso;
import static com.agenticai.core.agents.chatport.ChatImportFields.text;
import static com.agenticai.core.agents.chatport.ChatImportParser.ROLE_ASSISTANT;
import static com.agenticai.core.agents.chatport.ChatImportParser.ROLE_USER;
import static com.agenticai.core.agents.chatport.ChatImportParser.SOURCE_CLAUDE;

import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude's {@code conversations.json}: {@code {uuid, name, created_at, updated_at,
 * chat_messages[{sender, text, content[], attachments[], files[], created_at}]}}. Text blocks of
 * {@code content} win over the flat {@code text}.
 */
final class ClaudeImportFormat implements ChatImportFormat {

    @Override
    public boolean matches(JsonNode conv) {
        return conv.has("chat_messages");
    }

    @Override
    public String source(JsonNode conv) {
        return SOURCE_CLAUDE;
    }

    @Override
    public String sourceId(JsonNode conv) {
        return cleanId(text(conv, "uuid"), text(conv, "id"));
    }

    @Override
    public ParsedChat convert(
            JsonNode conv, String key, String source, String sourceId, ChatImportCollector c) {
        JsonNode msgs = conv.get("chat_messages");
        if (msgs == null || !msgs.isArray()) {
            return null;
        }
        ChatImportMessageBuilder b = new ChatImportMessageBuilder(c.limits, c.keepMessages);
        for (JsonNode m : msgs) {
            if (!m.isObject()) {
                continue;
            }
            String sender = text(m, "sender");
            String role;
            if ("human".equals(sender) || ROLE_USER.equals(sender)) {
                role = ROLE_USER;
            } else if (ROLE_ASSISTANT.equals(sender)) {
                role = ROLE_ASSISTANT;
            } else {
                continue;
            }
            String body = ChatPortText.clean(body(m));
            List<String> attachments = new ArrayList<>();
            addNames(attachments, m.get("attachments"));
            addNames(attachments, m.get("files"));
            if (body.isBlank() && attachments.isEmpty()) {
                continue;
            }
            if (!b.add(role, body, parseIso(text(m, "created_at")), attachments)) {
                break;
            }
        }
        if (b.isEmpty()) {
            return null;
        }
        String title = cleanTitle(text(conv, "name"), b.firstUserText());
        return b.build(
                key,
                SOURCE_CLAUDE,
                sourceId,
                title,
                parseIso(text(conv, "created_at")),
                parseIso(text(conv, "updated_at")),
                false);
    }

    private static String body(JsonNode m) {
        JsonNode content = m.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.isObject() && "text".equals(text(block, "type"))) {
                    String t = text(block, "text");
                    if (t != null && !t.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(t);
                    }
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        String t = text(m, "text");
        return t == null ? "" : t;
    }

    private static void addNames(List<String> out, JsonNode array) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode a : array) {
            addAttachment(out, text(a, "file_name"));
        }
    }
}
