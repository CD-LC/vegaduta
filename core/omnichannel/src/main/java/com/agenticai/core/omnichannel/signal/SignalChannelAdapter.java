package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE}: the Signal counterpart to {@code
 * TelegramChannelAdapter}. Every agent reply, pairing notice and manual send reaches Signal only
 * through here, after the approval gate has let it through.
 */
@Component
public class SignalChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.signal.send";

    private static final Logger log = LoggerFactory.getLogger(SignalChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final SignalCredentialService credentialService;
    private final SignalApiClient apiClient;
    private final ChannelOutboundLogger outboundLogger;
    private final int maxMessageChars;

    @Autowired
    public SignalChannelAdapter(
            SignalCredentialService credentialService,
            SignalApiClient apiClient,
            ChannelOutboundLogger outboundLogger,
            @Value("${app.signal.max-message-chars:3500}") int maxMessageChars) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.outboundLogger = outboundLogger;
        this.maxMessageChars = Math.max(200, maxMessageChars);
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        SendPayload payload = parsePayload(request.getPayloadJson());
        try {
            String recipient = SignalAddress.toRecipient(payload.to());
            SignalConnection connection = credentialService.resolveActive();
            for (String part : chunks(payload.body(), maxMessageChars)) {
                apiClient.send(connection, recipient, part, List.of());
            }
            log.info("Sent Signal message to {}", SignalAddress.mask(payload.to()));
            outboundLogger.recordSent(
                    ChannelType.SIGNAL, payload.to(), payload.body(), request.getRequestedBy());
        } catch (SignalApiException e) {
            // 4xx (unknown recipient, bad request) can never succeed on retry; network and 5xx can.
            if (e.retryable()) {
                throw new RetryableChannelDeliveryException(
                        "signal", "Signal send failed: " + e.getMessage(), e);
            }
            throw new ChannelDeliveryException(
                    "signal", "Signal send failed: " + e.getMessage(), e);
        } catch (ChannelConfigurationException e) {
            throw new ChannelDeliveryException("signal", e.getMessage(), e);
        } catch (SignalNotConfiguredException e) {
            throw new ChannelDeliveryException("signal", e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("signal", "Approval request has no payload to send");
        }
        try {
            SendPayload payload = MAPPER.readValue(payloadJson, SendPayload.class);
            if (payload.to() == null || payload.body() == null || payload.body().isBlank()) {
                throw new ChannelDeliveryException(
                        "signal", "Signal send payload needs a recipient and a body");
            }
            return payload;
        } catch (ChannelDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "signal", "Could not parse Signal send payload: " + e.getMessage(), e);
        }
    }

    /**
     * Splits at paragraph, line or word boundaries so no part is longer than {@code max}; a single
     * unbroken run longer than that is cut hard.
     */
    static List<String> chunks(String text, int max) {
        List<String> parts = new ArrayList<>();
        String rest = text.strip();
        while (rest.length() > max) {
            int cut = lastBreak(rest, "\n\n", max);
            if (cut < max / 2) {
                cut = lastBreak(rest, "\n", max);
            }
            if (cut < max / 2) {
                cut = lastBreak(rest, " ", max);
            }
            if (cut < max / 2) {
                cut = max;
            }
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }

    private static int lastBreak(String text, String separator, int max) {
        return text.lastIndexOf(separator, max);
    }

    /** {@code to} is a number, a UUID or {@code group:<id>} (see {@link SignalAddress}). */
    public record SendPayload(String to, String body) {}
}
