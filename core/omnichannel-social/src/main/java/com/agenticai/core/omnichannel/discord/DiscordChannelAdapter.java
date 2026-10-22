package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE} - the Discord counterpart to {@code
 * SlackChannelAdapter}. Sends directly via {@link DiscordApiClient}'s plain REST call - this does
 * NOT need to round-trip through the {@code channels/discord} Node service, because outbound sends
 * need no persistent Gateway connection (see {@code DiscordApiClient}'s javadoc).
 */
@Component
public class DiscordChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.discord.send";

    private static final Logger log = LoggerFactory.getLogger(DiscordChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final DiscordCredentialService credentialService;
    private final DiscordApiClient discordApiClient;
    private final ChannelOutboundLogger outboundLogger;

    public DiscordChannelAdapter(
            DiscordCredentialService credentialService,
            DiscordApiClient discordApiClient,
            ChannelOutboundLogger outboundLogger) {
        this.credentialService = credentialService;
        this.discordApiClient = discordApiClient;
        this.outboundLogger = outboundLogger;
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        SendPayload payload = parsePayload(request.getPayloadJson());
        try {
            DiscordCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            String result =
                    discordApiClient.sendMessage(
                            credential.botToken(), payload.to(), payload.body());
            log.info(result);
            outboundLogger.recordSent(
                    ChannelType.DISCORD, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "discord", "Discord send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException(
                    "discord", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "discord", "Could not parse Discord send payload: " + e.getMessage(), e);
        }
    }

    /**
     * {@code to} is a Discord channel ID (including DM channel IDs, which are just channel IDs from
     * the bot's perspective).
     */
    public record SendPayload(String to, String body) {}
}
