package com.agenticai.core.agents.tools.messaging;

import com.agenticai.core.agents.tools.media.GeneratedAttachmentResolver;
import com.agenticai.core.agents.tools.media.GeneratedAttachmentResolver.ResolvedAttachment;
import com.agenticai.core.json.JsonMappers;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Lets an agent forward an image or audio clip IT ALREADY GENERATED this conversation (via the
 * {@code generate_image} or {@code speak} tool) to a channel/recipient, instead of that artifact
 * only ever being viewable inline in this platform's own chat UI. Deliberately narrow: it forwards
 * an EXISTING artifact by its own {@code imageUrl}/{@code audioUrl} reference, it does not generate
 * anything itself and does not accept raw bytes from the model (which cannot produce them) - see
 * {@link GeneratedAttachmentResolver} for exactly which reference shapes are resolvable and why
 * video is not one of them yet.
 */
public class ForwardAttachmentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ForwardAttachmentToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final GeneratedAttachmentResolver resolver;
    private final ChannelMediaSendPort sendPort;

    public ForwardAttachmentToolCallback(
            GeneratedAttachmentResolver resolver, ChannelMediaSendPort sendPort) {
        this.resolver = resolver;
        this.sendPort = sendPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("forward_attachment")
                .description(
                        "Forwards an image or audio clip that a PRIOR call to generate_image or speak in THIS "
                                + "conversation actually produced to a channel/recipient. attachmentUrl MUST be the exact "
                                + "imageUrl/audioUrl string a real tool result returned earlier in this conversation - never "
                                + "construct, guess, or paraphrase one (the same rule the speak tool's own description gives "
                                + "for the URL it returns; this tool enforces it server-side too by only resolving real "
                                + "stored artifacts). If no such URL exists yet because nothing was actually generated, call "
                                + "generate_image or speak FIRST, then forward the URL it returns - do not call this tool on "
                                + "a made-up reference. channel is one of the connected channels (e.g. 'WHATSAPP', 'SLACK', "
                                + "'DISCORD', 'TELEGRAM', 'EMAIL') and to is the recipient in that channel's own address "
                                + "form (a phone number, a Slack channel id, an email address, etc.) - ask the user which "
                                + "channel/recipient if it isn't already clear from the conversation. Video attachments are "
                                + "not forwardable yet.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "attachmentUrl": {"type": "string", "description": "The exact imageUrl/audioUrl a real generate_image or speak call returned earlier this conversation"},
                        "channel": {"type": "string", "description": "Target channel, e.g. WHATSAPP, SLACK, DISCORD, TELEGRAM, EMAIL"},
                        "to": {"type": "string", "description": "Recipient in that channel's own address form"},
                        "caption": {"type": "string", "description": "Optional text to send alongside the attachment"}
                    },
                    "required": ["attachmentUrl", "channel", "to"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String attachmentUrl = asString(args.get("attachmentUrl"));
            String channel = asString(args.get("channel"));
            String to = asString(args.get("to"));
            String caption = asString(args.get("caption"));
            if (attachmentUrl == null || attachmentUrl.isBlank()) {
                return errorJson("attachmentUrl is required");
            }
            if (channel == null || channel.isBlank() || to == null || to.isBlank()) {
                return errorJson("channel and to are required");
            }

            Optional<ResolvedAttachment> resolved = resolver.resolve(attachmentUrl);
            if (resolved.isEmpty()) {
                return errorJson(
                        "No real generated attachment matches that URL - it must be the exact imageUrl/audioUrl a "
                                + "generate_image or speak call returned earlier this conversation, and that artifact "
                                + "must still exist. Nothing was forwarded.");
            }
            ResolvedAttachment attachment = resolved.get();
            ChannelMediaSendPort.SendResult result =
                    sendPort.sendMedia(
                            channel,
                            to,
                            attachment.bytes(),
                            attachment.contentType(),
                            attachment.filename(),
                            caption);

            if (!result.sent()) {
                return errorJson(result.detail());
            }
            return MAPPER.writeValueAsString(
                    Map.of(
                            "status",
                            "success",
                            "channel",
                            channel,
                            "to",
                            to,
                            "detail",
                            result.detail() == null ? "" : result.detail()));
        } catch (Exception e) {
            log.warn("forward_attachment call failed", e);
            return errorJson("Could not forward that attachment: " + e.getMessage());
        }
    }

    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
