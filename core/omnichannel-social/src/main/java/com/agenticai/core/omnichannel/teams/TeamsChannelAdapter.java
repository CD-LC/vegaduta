package com.agenticai.core.omnichannel.teams;

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
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE} - the Teams counterpart to {@code
 * TelegramChannelAdapter}.
 */
@Component
public class TeamsChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.teams.send";

    private static final Logger log = LoggerFactory.getLogger(TeamsChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final TeamsCredentialService credentialService;
    private final TeamsApiClient apiClient;
    private final ChannelOutboundLogger outboundLogger;

    public TeamsChannelAdapter(
            TeamsCredentialService credentialService,
            TeamsApiClient apiClient,
            ChannelOutboundLogger outboundLogger) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
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
            TeamsCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            String result =
                    apiClient.sendActivity(
                            credential.appId(),
                            credential.appPassword(),
                            credential.aadTenantId(),
                            payload.serviceUrl(),
                            payload.conversationId(),
                            payload.body(),
                            payload.replyToId());
            log.info(result);
            outboundLogger.recordSent(
                    ChannelType.TEAMS,
                    payload.conversationId(),
                    payload.body(),
                    request.getRequestedBy());
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "teams", "Teams send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("teams", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "teams", "Could not parse Teams send payload: " + e.getMessage(), e);
        }
    }

    /**
     * {@code serviceUrl} + {@code conversationId} are the two coordinates a Bot Framework reply
     * needs (see {@code teams_conversation_route}); {@code replyToId} optionally threads the reply
     * under the inbound activity that prompted it.
     */
    public record SendPayload(
            String serviceUrl, String conversationId, String body, String replyToId) {}
}
