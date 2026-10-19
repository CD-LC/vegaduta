package com.agenticai.core.agents.tools.messaging;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * #604 - direct fix for "when I asked to read all messages from telegram it is not choosing any
 * telegram tool" (Quick Task had nothing named telegram_* to pick from at all). Reads from the
 * platform's own persisted message log ({@code ChannelMessageLog} in {@code core/omnichannel},
 * reached through {@link ChannelMessageQueryPort} - see that interface's javadoc for why this can't
 * be a direct client call), not Telegram's Bot API: {@code TelegramApiClient} only exposes {@code
 * getMe}/{@code setWebhook}/{@code sendMessage}, and Telegram's own API rules make {@code
 * getUpdates} (long-polling history) mutually exclusive with webhook mode, which this platform
 * already uses for every tenant's Telegram bot.
 */
public class TelegramMessagesToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessagesToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ChannelMessageQueryPort messageQueryPort;

    public TelegramMessagesToolCallback(ChannelMessageQueryPort messageQueryPort) {
        this.messageQueryPort = messageQueryPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("telegram_read_messages")
                .description(
                        "Reads recent inbound Telegram messages received on this tenant's connected Telegram bot, "
                                + "across every chat, newest first. Use this to answer questions like 'what did people say on "
                                + "Telegram' or 'read all messages from telegram'. Each result includes the sender's chat id, "
                                + "the message text, and when it was received.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "limit": {"type": "integer", "description": "Max messages to return, newest first (default 20, max 50)"}
                    }
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            int limit = DEFAULT_LIMIT;
            if (toolInput != null && !toolInput.isBlank()) {
                Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
                Object rawLimit = args.get("limit");
                if (rawLimit != null) {
                    limit =
                            Math.max(
                                    1,
                                    Math.min(
                                            MAX_LIMIT, Integer.parseInt(String.valueOf(rawLimit))));
                }
            }
            List<ChannelMessageQueryPort.ChannelMessageRecord> messages =
                    messageQueryPort.recentMessages("TELEGRAM", limit);
            if (messages.isEmpty()) {
                return "{\"messages\": [], \"note\": \"No Telegram messages have been received yet.\"}";
            }
            return MAPPER.writeValueAsString(Map.of("messages", messages));
        } catch (Exception e) {
            log.warn("telegram_read_messages tool call failed", e);
            return errorJson("Could not read Telegram messages: " + e.getMessage());
        }
    }

    // Same bug class fixed in KnowledgeSearchToolCallback - hand-built
    // "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }
}
