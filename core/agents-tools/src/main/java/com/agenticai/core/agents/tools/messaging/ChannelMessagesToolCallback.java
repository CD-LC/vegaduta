package com.agenticai.core.agents.tools.messaging;

import com.agenticai.core.json.JsonMappers;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code channel_read_messages} - the channel-generic version of {@code telegram_read_messages}
 * ({@link TelegramMessagesToolCallback}), which was hardcoded to TELEGRAM for the #604 fix. Same
 * source (the platform's own persisted {@code channel_message_log} via {@link
 * ChannelMessageQueryPort}), same posture - just with the channel as a parameter so "read our
 * WhatsApp messages" / "what came in on Slack" work the way the Telegram one already did. The
 * Telegram-only tool stays for compatibility.
 */
public class ChannelMessagesToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessagesToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ChannelMessageQueryPort messageQueryPort;

    public ChannelMessagesToolCallback(ChannelMessageQueryPort messageQueryPort) {
        this.messageQueryPort = messageQueryPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("channel_read_messages")
                .description(
                        "Reads recent inbound messages received on any of this tenant's connected channels, "
                                + "newest first: whatsapp, telegram, slack, discord, teams, email, messenger, instagram "
                                + "or x. Each result includes the sender's external id, the message text, and when it "
                                + "was received. Use this for questions like 'what did people say on WhatsApp' or "
                                + "'read recent Slack messages'.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "channel": {"type": "string", "description": "Channel to read: whatsapp, telegram, slack, discord, teams, email, messenger, instagram, x"},
                        "limit": {"type": "integer", "description": "Max messages to return, newest first (default 20, max 50)"}
                    },
                    "required": ["channel"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            String channel = null;
            int limit = DEFAULT_LIMIT;
            if (toolInput != null && !toolInput.isBlank()) {
                Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
                Object rawChannel = args.get("channel");
                if (rawChannel != null) {
                    channel = String.valueOf(rawChannel);
                }
                Object rawLimit = args.get("limit");
                if (rawLimit != null) {
                    limit =
                            Math.max(
                                    1,
                                    Math.min(
                                            MAX_LIMIT, Integer.parseInt(String.valueOf(rawLimit))));
                }
            }
            if (channel == null) {
                return errorJson(
                        "channel is required - one of whatsapp, telegram, slack, discord, teams, email, messenger, instagram, x.");
            }
            List<ChannelMessageQueryPort.ChannelMessageRecord> messages =
                    messageQueryPort.recentMessages(channel, limit);
            if (messages.isEmpty()) {
                return MAPPER.writeValueAsString(
                        Map.of(
                                "messages",
                                List.of(),
                                "note",
                                "No "
                                        + channel
                                        + " messages have been received yet - check the channel name or that it's connected."));
            }
            return MAPPER.writeValueAsString(Map.of("messages", messages));
        } catch (Exception e) {
            log.warn("channel_read_messages tool call failed", e);
            return errorJson("Could not read channel messages: " + e.getMessage());
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
