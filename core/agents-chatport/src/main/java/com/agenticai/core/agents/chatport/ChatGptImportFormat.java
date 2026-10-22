package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatImportFields.addAttachment;
import static com.agenticai.core.agents.chatport.ChatImportFields.cleanId;
import static com.agenticai.core.agents.chatport.ChatImportFields.cleanTitle;
import static com.agenticai.core.agents.chatport.ChatImportFields.epochSeconds;
import static com.agenticai.core.agents.chatport.ChatImportFields.text;
import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_STRING_CHARS;
import static com.agenticai.core.agents.chatport.ChatImportParser.ROLE_ASSISTANT;
import static com.agenticai.core.agents.chatport.ChatImportParser.ROLE_USER;
import static com.agenticai.core.agents.chatport.ChatImportParser.SOURCE_CHATGPT;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ChatGPT's {@code conversations.json}: {@code {title, create_time, update_time, mapping{id ->
 * {message, parent, children}}, current_node}}. The message tree is walked from {@code
 * current_node} (or the newest leaf) up the {@code parent} chain with a visited set and a step cap,
 * so a cyclic or enormous {@code mapping} cannot loop.
 */
final class ChatGptImportFormat implements ChatImportFormat {

    @Override
    public boolean matches(JsonNode conv) {
        return conv.has("mapping");
    }

    @Override
    public String source(JsonNode conv) {
        return SOURCE_CHATGPT;
    }

    @Override
    public String sourceId(JsonNode conv) {
        return cleanId(text(conv, "conversation_id"), text(conv, "id"));
    }

    @Override
    public ParsedChat convert(
            JsonNode conv, String key, String source, String sourceId, ChatImportCollector c) {
        JsonNode mapping = conv.get("mapping");
        if (mapping == null || !mapping.isObject()) {
            return null;
        }
        Limits limits = c.limits;
        if (mapping.size() > limits.maxMappingNodes()) {
            c.warn("A conversation with an unusually large message tree was skipped.");
            return null;
        }
        String cursor = text(conv, "current_node");
        if (cursor == null || !mapping.has(cursor)) {
            cursor = pickLeaf(mapping);
        }
        List<JsonNode> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        int steps = 0;
        boolean cyclic = false;
        while (cursor != null && steps++ <= limits.maxMappingNodes()) {
            if (!visited.add(cursor)) {
                cyclic = true;
                break;
            }
            JsonNode node = mapping.get(cursor);
            if (node == null || !node.isObject()) {
                break;
            }
            path.add(node);
            cursor = text(node, "parent");
        }
        if (cyclic) {
            c.warn(
                    "A conversation had a cyclic message tree; only the part that could be followed"
                        + " was imported.");
        }
        Collections.reverse(path);

        ChatImportMessageBuilder b = new ChatImportMessageBuilder(limits, c.keepMessages);
        for (JsonNode node : path) {
            JsonNode msg = node.get("message");
            if (msg == null || !msg.isObject()) {
                continue;
            }
            String role = text(msg.path("author"), "role");
            if (!ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
                continue;
            }
            JsonNode meta = msg.path("metadata");
            if (meta.path("is_visually_hidden_from_conversation").asBoolean(false)) {
                continue;
            }
            String recipient = text(msg, "recipient");
            if (ROLE_ASSISTANT.equals(role) && recipient != null && !"all".equals(recipient)) {
                continue;
            }
            List<String> attachments = new ArrayList<>();
            String body = ChatPortText.clean(body(msg.path("content"), attachments));
            JsonNode metaAttachments = meta.path("attachments");
            if (metaAttachments.isArray()) {
                for (JsonNode a : metaAttachments) {
                    addAttachment(attachments, text(a, "name"));
                }
            }
            if (body.isBlank() && attachments.isEmpty()) {
                continue;
            }
            if (!b.add(role, body, epochSeconds(msg.get("create_time")), attachments)) {
                break;
            }
        }
        if (b.isEmpty()) {
            return null;
        }
        String title = cleanTitle(text(conv, "title"), b.firstUserText());
        return b.build(
                key,
                SOURCE_CHATGPT,
                sourceId,
                title,
                epochSeconds(conv.get("create_time")),
                epochSeconds(conv.get("update_time")),
                false);
    }

    /**
     * The leaf to start from when {@code current_node} is absent: the newest message with no
     * children.
     */
    private static String pickLeaf(JsonNode mapping) {
        String best = null;
        double bestTime = Double.NEGATIVE_INFINITY;
        String last = null;
        Iterator<Map.Entry<String, JsonNode>> it = mapping.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            last = e.getKey();
            JsonNode node = e.getValue();
            JsonNode children = node.path("children");
            boolean leaf = !children.isArray() || children.isEmpty();
            if (!leaf) {
                continue;
            }
            JsonNode t = node.path("message").path("create_time");
            double time = t.isNumber() ? t.asDouble() : Double.NEGATIVE_INFINITY;
            if (best == null || time >= bestTime) {
                best = e.getKey();
                bestTime = time;
            }
        }
        return best != null ? best : last;
    }

    private static String body(JsonNode content, List<String> attachments) {
        if (content == null || !content.isObject()) {
            return "";
        }
        String type = text(content, "content_type");
        if (type == null) {
            type = "text";
        }
        switch (type) {
            case "text", "multimodal_text" -> {
                JsonNode parts = content.get("parts");
                if (parts == null || !parts.isArray()) {
                    return "";
                }
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : parts) {
                    String piece = null;
                    if (part.isString()) {
                        piece = part.asString();
                    } else if (part.isObject()) {
                        String pt = text(part, "content_type");
                        if ("image_asset_pointer".equals(pt)) {
                            addAttachment(attachments, "image");
                        } else if (pt != null && pt.endsWith("audio_asset_pointer")) {
                            addAttachment(attachments, "audio");
                        } else {
                            piece = text(part, "text");
                        }
                    }
                    if (piece != null && !piece.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(piece);
                        if (sb.length() > MAX_STRING_CHARS) {
                            break;
                        }
                    }
                }
                return sb.toString();
            }
            case "code" -> {
                String code = text(content, "text");
                if (code == null || code.isBlank()) {
                    return "";
                }
                String lang = text(content, "language");
                if (lang == null
                        || !lang.matches("[A-Za-z0-9+#._-]{1,20}")
                        || "unknown".equals(lang)) {
                    lang = "";
                }
                return "```" + lang + "\n" + code + "\n```";
            }
            default -> {
                // thoughts, reasoning_recap, execution_output, tether_*, user_editable_context ...:
                // not conversation text
                return "";
            }
        }
    }
}
