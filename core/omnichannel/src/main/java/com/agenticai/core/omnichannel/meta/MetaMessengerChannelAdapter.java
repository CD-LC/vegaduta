package com.agenticai.core.omnichannel.meta;

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
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE}, registered
 * automatically with {@code ApprovalActionExecutorRegistry} - same pattern
 * as {@code TwilioWhatsAppChannelAdapter}. Callers never invoke this
 * directly; they call {@code HumanApprovalGateService.evaluate(ACTION_TYPE,
 * ...)} (see {@link MetaSocialWebhookController}'s auto-reply path).
 */
@Component
public class MetaMessengerChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.messenger.send";

    private static final Logger log = LoggerFactory.getLogger(MetaMessengerChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final MetaSocialCredentialService credentialService;
    private final MetaGraphApiClient graphApiClient;
    private final ChannelOutboundLogger outboundLogger;

    public MetaMessengerChannelAdapter(
        MetaSocialCredentialService credentialService, MetaGraphApiClient graphApiClient, ChannelOutboundLogger outboundLogger
    ) {
        this.credentialService = credentialService;
        this.graphApiClient = graphApiClient;
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
            MetaSocialCredentialService.DecryptedCredential credential = credentialService.resolveActive();
            String result = graphApiClient.sendMessengerMessage(credential.pageAccessToken(), payload.to(), payload.body());
            log.info(result);
            outboundLogger.recordSent(ChannelType.MESSENGER, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            // Retryable - same reasoning as TwilioWhatsAppChannelAdapter:
            // an upstream Graph API failure is plausibly transient.
            throw new RetryableChannelDeliveryException("messenger", "Messenger send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("messenger", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException("messenger", "Could not parse Messenger send payload: " + e.getMessage(), e);
        }
    }

    public record SendPayload(String to, String body) {
    }
}
