package com.agenticai.core.agents.tools.messaging;

import com.agenticai.core.json.JsonMappers;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code channel_send_message} - the agent's outbound voice on every connected channel. Routes
 * through {@link ChannelSendPort}, which evaluates the send through the platform's own approval
 * gate ({@code HumanApprovalGateService}) using the channel's real {@code channel.*.send} action
 * type - so a tenant whose approval policy requires human sign-off on external messages gets
 * exactly that, identically to a console send, and an agent on an autonomous policy sends straight
 * through. Either way the delivery, retry and audit path is the platform's own, not a side door.
 */
public class ChannelSendMessageToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ChannelSendMessageToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ChannelSendPort sendPort;
    private final String requestedBy;

    /**
     * @param requestedBy attribution stamped on the approval request - supplied by the provider
     *     ({@code "agent:<uuid>"}), never by the model - same reason {@code
     *     RequestHumanToolCallback} threads sessionId through the constructor rather than the
     *     schema.
     */
    public ChannelSendMessageToolCallback(ChannelSendPort sendPort, String requestedBy) {
        this.sendPort = sendPort;
        this.requestedBy = requestedBy;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("channel_send_message")
                .description(
                        "Sends a message on one of the tenant's connected channels: whatsapp (auto-picks the "
                                + "connected provider), telegram, slack, discord, teams, email, messenger, "
                                + "instagram or x. 'to' uses the channel's own address form (phone number, chat/channel "
                                + "id, conversation id, email address). The send runs through the platform's approval "
                                + "gate - it may return pending with a request id instead of sending immediately, in "
                                + "which case a human must approve it first. Check channels_overview first if unsure "
                                + "which channels are connected.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "channel": {"type": "string", "description": "Channel to send on: whatsapp, telegram, slack, discord, teams, email, messenger, instagram, x"},
                        "to": {"type": "string", "description": "Recipient in the channel's own address form"},
                        "subject": {"type": "string", "description": "Email subject - required for channel=email, ignored otherwise"},
                        "body": {"type": "string", "description": "The message text to send"}
                    },
                    "required": ["channel", "to", "body"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            if (toolInput == null || toolInput.isBlank()) {
                return errorJson("Missing tool input - channel, to and body are required.");
            }
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object channel = args.get("channel");
            Object to = args.get("to");
            Object subject = args.get("subject");
            Object body = args.get("body");
            if (channel == null || to == null || body == null) {
                return errorJson("channel, to and body are required.");
            }
            ChannelSendPort.ChannelSendResult result =
                    sendPort.send(
                            String.valueOf(channel),
                            String.valueOf(to),
                            subject == null ? null : String.valueOf(subject),
                            String.valueOf(body),
                            requestedBy);
            return MAPPER.writeValueAsString(
                    Map.of(
                            "approved",
                            result.approved(),
                            "pendingRequestId",
                            result.pendingRequestId() == null ? "" : result.pendingRequestId(),
                            "summary",
                            result.summary(),
                            "note",
                            result.approved()
                                    ? "Send accepted by the approval gate."
                                    : "Waiting on a human approval - the message has NOT been delivered yet."));
        } catch (Exception e) {
            log.warn("channel_send_message tool call failed", e);
            return errorJson("Channel send failed: " + e.getMessage());
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
