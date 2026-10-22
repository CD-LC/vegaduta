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
 * {@code channel_list_sessions} - the conversations behind a channel: every sender who has messaged
 * the tenant's connected accounts, whether they're paired (live) or still waiting on pairing
 * approval. This is what lets an agent answer "who is talking to us on WhatsApp", find the session
 * id it needs for {@code channel_session_action}, or spot senders stuck in the pending-pairing
 * queue.
 */
public class ChannelSessionsToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ChannelSessionsToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ChannelControlPort controlPort;

    public ChannelSessionsToolCallback(ChannelControlPort controlPort) {
        this.controlPort = controlPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("channel_list_sessions")
                .description(
                        "Lists channel conversations (sessions), newest activity first: the sender's external id, "
                                + "whether the sender is paired (approved) or still pending pairing approval, the agent "
                                + "currently bound to the conversation, and the last message preview. Optionally filter "
                                + "to one channel. Use this to find a sessionId for channel_session_action, or to answer "
                                + "'who has been messaging us'.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "channel": {"type": "string", "description": "Optional channel filter: whatsapp, telegram, slack, discord, teams, email, messenger, instagram, x, voice"},
                        "limit": {"type": "integer", "description": "Max sessions to return, newest first (default 20, max 50)"}
                    }
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
            List<ChannelControlPort.ChannelSessionInfo> sessions =
                    controlPort.sessions(channel, limit);
            if (sessions.isEmpty()) {
                return "{\"sessions\": [], \"note\": \"No channel conversations found.\"}";
            }
            return MAPPER.writeValueAsString(Map.of("sessions", sessions));
        } catch (Exception e) {
            log.warn("channel_list_sessions tool call failed", e);
            return errorJson("Could not list channel sessions: " + e.getMessage());
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
