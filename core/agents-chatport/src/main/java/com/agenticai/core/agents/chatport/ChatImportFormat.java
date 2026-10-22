package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One importable conversation format. {@link #REGISTERED} is checked in order and the first format
 * whose {@link #matches} accepts a conversation object converts it, so the order is part of the
 * contract: a ChatGPT {@code mapping} wins over a Claude {@code chat_messages}, which wins over
 * this platform's own {@code messages}. A new format is one more implementation added to that list.
 */
interface ChatImportFormat {

    /** Every supported format, in matching order. */
    List<ChatImportFormat> REGISTERED =
            List.of(
                    new ChatGptImportFormat(),
                    new ClaudeImportFormat(),
                    new VegaDutaImportFormat());

    /** The first registered format that recognises {@code conv}, or null when none does. */
    static ChatImportFormat forConversation(JsonNode conv) {
        for (ChatImportFormat format : REGISTERED) {
            if (format.matches(conv)) {
                return format;
            }
        }
        return null;
    }

    /** Whether {@code conv} (a JSON object) is a conversation in this format. */
    boolean matches(JsonNode conv);

    /**
     * The source recorded on the imported chat ({@link ChatImportParser#SOURCE_CHATGPT} and so on).
     */
    String source(JsonNode conv);

    /** The source's own conversation id, cleaned, or null when it has none. */
    String sourceId(JsonNode conv);

    /**
     * Converts {@code conv} under the collector's caps.
     *
     * @return the chat, or null when it holds no importable message (or is skipped for size, with a
     *     warning)
     */
    ParsedChat convert(
            JsonNode conv, String key, String source, String sourceId, ChatImportCollector c);
}
