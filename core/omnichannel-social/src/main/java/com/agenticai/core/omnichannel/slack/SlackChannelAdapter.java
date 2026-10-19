package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE} - the Slack counterpart to {@code
 * TwilioWhatsAppChannelAdapter}/{@code MetaMessengerChannelAdapter}.
 */
@Component
public class SlackChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.slack.send";

    private static final Logger log = LoggerFactory.getLogger(SlackChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final SlackCredentialService credentialService;
    private final SlackApiClient slackApiClient;
    private final ChannelOutboundLogger outboundLogger;

    public SlackChannelAdapter(
            SlackCredentialService credentialService,
            SlackApiClient slackApiClient,
            ChannelOutboundLogger outboundLogger) {
        this.credentialService = credentialService;
        this.slackApiClient = slackApiClient;
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
            SlackCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            String result =
                    slackApiClient.sendMessage(credential.botToken(), payload.to(), payload.body());
            log.info(result);
            outboundLogger.recordSent(
                    ChannelType.SLACK, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "slack", "Slack send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("slack", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "slack", "Could not parse Slack send payload: " + e.getMessage(), e);
        }
    }

    /**
     * {@code to} is a Slack channel ID (e.g. {@code C0123...}) or a user/DM channel ID (e.g. {@code
     * D0123...}), whichever chat.postMessage accepts as {@code channel}.
     */
    public record SendPayload(String to, String body) {}
}
