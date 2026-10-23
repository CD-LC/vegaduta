package com.agenticai.core.omnichannel.x;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE} - the X (Twitter) counterpart to {@code
 * SlackChannelAdapter}/{@code DiscordChannelAdapter}. {@link XOAuthService#currentAccessToken}
 * mints a fresh access token (and transparently persists a rotated refresh token - see that
 * method's javadoc) on every send, the same "always mint fresh, never cache" posture {@code
 * GoogleOAuthService#currentAccessToken} uses.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class XChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.x.send";

    private static final Logger log = LoggerFactory.getLogger(XChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final XOAuthService oauthService;
    private final XApiClient apiClient;
    private final ChannelOutboundLogger outboundLogger;

    public XChannelAdapter(
            XOAuthService oauthService,
            XApiClient apiClient,
            ChannelOutboundLogger outboundLogger) {
        this.oauthService = oauthService;
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
            String accessToken = oauthService.currentAccessToken();
            String result = apiClient.sendDm(accessToken, payload.to(), payload.body());
            log.info(result);
            outboundLogger.recordSent(
                    ChannelType.X, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "x", "X DM send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("x", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "x", "Could not parse X send payload: " + e.getMessage(), e);
        }
    }

    /**
     * {@code to} is a {@code dm_conversation_id}, not a user id - see {@code XDmPollingJob}'s
     * javadoc for why sessions are keyed by conversation.
     */
    public record SendPayload(String to, String body) {}
}
