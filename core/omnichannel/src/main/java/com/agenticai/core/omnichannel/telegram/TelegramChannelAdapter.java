package com.agenticai.core.omnichannel.telegram;

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
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE} - the Telegram counterpart to {@code
 * DiscordChannelAdapter}.
 */
@Component
public class TelegramChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.telegram.send";

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final TelegramCredentialService credentialService;
    private final TelegramApiClient apiClient;
    private final ChannelOutboundLogger outboundLogger;

    public TelegramChannelAdapter(
            TelegramCredentialService credentialService,
            TelegramApiClient apiClient,
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
            TelegramCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            String result =
                    apiClient.sendMessage(credential.botToken(), payload.to(), payload.body());
            log.info(result);
            outboundLogger.recordSent(
                    ChannelType.TELEGRAM, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "telegram", "Telegram send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException(
                    "telegram", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "telegram", "Could not parse Telegram send payload: " + e.getMessage(), e);
        }
    }

    /** {@code to} is a Telegram chat id. */
    public record SendPayload(String to, String body) {}
}
