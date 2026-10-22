package com.agenticai.core.omnichannel.matrix;

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
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE}: the Matrix counterpart to the Telegram
 * adapter.
 */
@Component
public class MatrixChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.matrix.send";

    private static final Logger log = LoggerFactory.getLogger(MatrixChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final MatrixCredentialService credentialService;
    private final MatrixApiClient apiClient;
    private final ChannelOutboundLogger outboundLogger;

    public MatrixChannelAdapter(
            MatrixCredentialService credentialService,
            MatrixApiClient apiClient,
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
            MatrixCredentialService.ActiveConnection connection = credentialService.resolveActive();
            // The transaction id makes a retry of this same approval idempotent on the homeserver.
            String txnId = "vd-" + request.getId();
            apiClient.sendText(
                    connection.endpoint(),
                    payload.to(),
                    txnId,
                    payload.body(),
                    payload.replyToEventId(),
                    payload.threadRootEventId());
            log.info("Sent Matrix message to room ***{}", lastFour(payload.to()));
            outboundLogger.recordSent(
                    ChannelType.MATRIX, payload.to(), payload.body(), request.getRequestedBy());
        } catch (MatrixNotConfiguredException e) {
            throw new ChannelDeliveryException("matrix", e.getMessage(), e);
        } catch (MatrixApiException e) {
            if (e.isPermanentClientError()) {
                // The homeserver will answer the same way next time (not in the room, forbidden).
                throw new ChannelDeliveryException(
                        "matrix", "Matrix send failed: " + e.getMessage(), e);
            }
            throw new RetryableChannelDeliveryException(
                    "matrix", "Matrix send failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "matrix", "Matrix send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("matrix", "Approval request has no payload to send");
        }
        try {
            SendPayload payload = MAPPER.readValue(payloadJson, SendPayload.class);
            if (payload.to() == null || payload.to().isBlank() || payload.body() == null) {
                throw new ChannelDeliveryException(
                        "matrix", "Matrix send payload needs a room id and a body");
            }
            return payload;
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "matrix", "Could not parse Matrix send payload: " + e.getMessage(), e);
        }
    }

    private static String lastFour(String value) {
        return value == null || value.length() < 4 ? "***" : value.substring(value.length() - 4);
    }

    /**
     * {@code to} is a Matrix room id ({@code !abc:example.org}). {@code replyToEventId} and {@code
     * threadRootEventId} are optional: set when answering a specific message or a thread.
     */
    public record SendPayload(
            String to, String body, String replyToEventId, String threadRootEventId) {}
}
