package com.agenticai.core.agents.tools.messaging;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code channel_session_action} - the trust actions an agent can take on a channel conversation:
 * approve a sender's pairing (grants them a live conversation with this tenant's agents), unpair
 * (revokes that grant and re-challenges the sender), or rebind the conversation to a different
 * agent. These are the same actions {@code ChannelPairingController} gives tenant admins - the tool
 * classification marks this irreversible so the approval gate treats it like the trust decision it
 * is.
 */
public class ChannelSessionActionToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(ChannelSessionActionToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ChannelControlPort controlPort;

    public ChannelSessionActionToolCallback(ChannelControlPort controlPort) {
        this.controlPort = controlPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("channel_session_action")
                .description(
                        "Performs a trust action on a channel conversation. Actions: 'approve_pairing' (approve a "
                                + "pending sender so they can talk to the tenant's agents), 'unpair' (revoke a sender's "
                                + "pairing; they must re-verify next message), 'rebind_agent' (move the conversation to "
                                + "a different agent - requires agentId). Get sessionId values from channel_list_sessions.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["approve_pairing", "unpair", "rebind_agent"], "description": "The trust action to perform"},
                        "sessionId": {"type": "string", "description": "The channel session UUID, from channel_list_sessions"},
                        "agentId": {"type": "string", "description": "Target agent UUID - required for rebind_agent only"}
                    },
                    "required": ["action", "sessionId"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            if (toolInput == null || toolInput.isBlank()) {
                return errorJson("Missing tool input - action and sessionId are required.");
            }
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object action = args.get("action");
            Object sessionId = args.get("sessionId");
            Object agentId = args.get("agentId");
            if (action == null || sessionId == null) {
                return errorJson("action and sessionId are required.");
            }
            ChannelControlPort.ChannelSessionActionResult result =
                    controlPort.actOnSession(
                            String.valueOf(action),
                            String.valueOf(sessionId),
                            agentId == null ? null : String.valueOf(agentId));
            return MAPPER.writeValueAsString(Map.of("ok", result.ok(), "detail", result.detail()));
        } catch (Exception e) {
            log.warn("channel_session_action tool call failed", e);
            return errorJson("Channel session action failed: " + e.getMessage());
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
