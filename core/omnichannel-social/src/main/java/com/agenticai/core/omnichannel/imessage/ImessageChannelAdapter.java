package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE}: every iMessage the platform sends
 * (agent auto-replies, pairing notices, manual and operator sends) funnels through the approval
 * gate and lands here, where it is rate limited per tenant and handed to the tenant's BlueBubbles
 * server.
 */
@Component
public class ImessageChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.imessage.send";

    private static final Logger log = LoggerFactory.getLogger(ImessageChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ImessageCredentialService credentialService;
    private final ImessageApiClient apiClient;
    private final ChannelOutboundLogger outboundLogger;
    private final ImessageRateLimiter rateLimiter;

    public ImessageChannelAdapter(
            ImessageCredentialService credentialService,
            ImessageApiClient apiClient,
            ChannelOutboundLogger outboundLogger,
            ImessageRateLimiter rateLimiter) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.outboundLogger = outboundLogger;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        SendPayload payload = parsePayload(request.getPayloadJson());
        if (!rateLimiter.tryOutbound(tenantKey())) {
            throw new RetryableChannelDeliveryException(
                    "imessage", "iMessage send rate limit reached - will retry", null);
        }
        ImessageCredentialService.DecryptedCredential credential;
        try {
            credential = credentialService.resolveActive();
        } catch (ImessageNotConfiguredException e) {
            throw new ChannelDeliveryException("imessage", e.getMessage(), e);
        }
        try {
            String result =
                    apiClient.sendText(credential.serverUrl(), credential.password(), payload.to(), payload.body());
            log.info(result);
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException("imessage", "iMessage send failed: " + e.getMessage(), e);
        }
        outboundLogger.recordSent(ChannelType.IMESSAGE, payload.to(), payload.body(), request.getRequestedBy());
    }

    static String tenantKey() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("");
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("imessage", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "imessage", "Could not parse iMessage send payload: " + e.getMessage(), e);
        }
    }

    /** {@code to} is a BlueBubbles chat GUID (what a session stores) or a bare phone number / email. */
    public record SendPayload(String to, String body) {}
}
