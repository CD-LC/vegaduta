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
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE} - the Instagram DM counterpart to {@link
 * MetaMessengerChannelAdapter}, kept as its own bean/action type (rather than folding "which
 * platform" into one payload) so the Approvals inbox shows a distinct, correctly-labeled action for
 * a human reviewer, and so a tenant's guardrail policy can require sign-off for one platform
 * without the other if they ever want that granularity.
 */
@Component
public class MetaInstagramChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.instagram.send";

    private static final Logger log = LoggerFactory.getLogger(MetaInstagramChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final MetaSocialCredentialService credentialService;
    private final MetaGraphApiClient graphApiClient;
    private final ChannelOutboundLogger outboundLogger;

    public MetaInstagramChannelAdapter(
            MetaSocialCredentialService credentialService,
            MetaGraphApiClient graphApiClient,
            ChannelOutboundLogger outboundLogger) {
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
            MetaSocialCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            String result =
                    graphApiClient.sendInstagramMessage(
                            credential.pageAccessToken(), payload.to(), payload.body());
            log.info(result);
            outboundLogger.recordSent(
                    ChannelType.INSTAGRAM, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "instagram", "Instagram DM send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException(
                    "instagram", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "instagram", "Could not parse Instagram send payload: " + e.getMessage(), e);
        }
    }

    public record SendPayload(String to, String body) {}
}
