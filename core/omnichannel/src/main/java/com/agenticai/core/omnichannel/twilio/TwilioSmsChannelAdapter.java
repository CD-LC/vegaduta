package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.SmsDispatchService;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Approval-gated plain-SMS send (2026-10-14) - the SMS sibling of {@link
 * TwilioWhatsAppChannelAdapter}, registered for {@link #ACTION_TYPE} with the executor registry
 * like every other channel. {@code TwilioSmsWebhookController} never sends inline TwiML: an agent's
 * reply goes through {@code HumanApprovalGateService.evaluate(ACTION_TYPE, ...)} and reaches this
 * class only once approved (or auto-approved by policy), so the Stage 1 "no side channel around the
 * gate" rule holds for SMS too. {@code channel.*.send} is a sensitive pattern by default, so a
 * fresh tenant's first SMS reply waits for an approval unless the tenant auto-approves the action
 * type.
 *
 * <p><b>Why the body is cut here, not in the agent.</b> A carrier concatenates at most about ten
 * segments (1,600 GSM-7 characters; Indic and other non-Latin scripts fall back to UCS-2, 70
 * characters per segment, so the same limit holds fewer characters) and the operator pays per
 * segment. Twilio rejects a longer body outright, which would turn a verbose model into a silent
 * failure. The limit is {@code app.omnichannel.sms.max-reply-chars} (default 1,500, leaving room
 * for the AI-disclosure prefix); the cut lands at the last sentence or word boundary before the
 * limit and ends with an ellipsis so the reader knows text was dropped. The webhook also tells the
 * agent it is on SMS, so a well-behaved model rarely hits this.
 */
@Component
public class TwilioSmsChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.sms.send";

    /** Twilio's hard ceiling for one (concatenated) message body. */
    static final int TWILIO_MAX_BODY_CHARS = 1600;

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String ELLIPSIS = "…";

    private final SmsDispatchService dispatchService;
    private final ChannelOutboundLogger outboundLogger;
    private final int maxReplyChars;

    public TwilioSmsChannelAdapter(
            SmsDispatchService dispatchService,
            ChannelOutboundLogger outboundLogger,
            @Value("${app.omnichannel.sms.max-reply-chars:1500}") int maxReplyChars) {
        this.dispatchService = dispatchService;
        this.outboundLogger = outboundLogger;
        this.maxReplyChars = Math.max(80, Math.min(maxReplyChars, TWILIO_MAX_BODY_CHARS));
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        SendPayload payload = parsePayload(request.getPayloadJson());
        String body = fitToSms(payload.body(), maxReplyChars);
        try {
            String result = dispatchService.send(payload.to(), body);
            log.info("{} (recipient {})", result, maskRecipient(payload.to()));
            outboundLogger.recordSent(
                    ChannelType.SMS, payload.to(), body, request.getRequestedBy());
        } catch (RuntimeException e) {
            // Same retry posture (and the same honest double-send caveat) as the
            // WhatsApp adapter: a carrier or Twilio hiccup is plausibly transient.
            throw new RetryableChannelDeliveryException(
                    "sms", "SMS send failed: " + e.getMessage(), e);
        }
    }

    /**
     * Cuts {@code text} to at most {@code max} characters at the last sentence end, else the last
     * word boundary, in the final third of the allowed length, and appends an ellipsis. Text that
     * already fits is returned unchanged. Also collapses runs of blank lines, which an SMS reader
     * pays for and gains nothing from.
     */
    static String fitToSms(String text, int max) {
        if (text == null) {
            return "";
        }
        String compact = text.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
        if (compact.length() <= max) {
            return compact;
        }
        int room = max - ELLIPSIS.length();
        String head = compact.substring(0, room);
        int floor = room * 2 / 3;
        int cut = -1;
        for (int i = head.length() - 1; i >= floor; i--) {
            char c = head.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '।' /* devanagari danda */) {
                cut = i + 1;
                break;
            }
        }
        if (cut < 0) {
            int space = head.lastIndexOf(' ');
            cut = space >= floor ? space : room;
        }
        return head.substring(0, cut).stripTrailing() + ELLIPSIS;
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("sms", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "sms", "Could not parse SMS send payload: " + e.getMessage(), e);
        }
    }

    // Never logs a full phone number.
    private static String maskRecipient(String to) {
        if (to == null || to.length() < 4) {
            return "***";
        }
        return "***" + to.substring(to.length() - 4);
    }

    public record SendPayload(String to, String body) {}
}
