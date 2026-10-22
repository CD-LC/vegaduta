package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The first real {@link ApprovalActionExecutor} - Omnichannel Build Plan Stage 2. Registered
 * automatically (implements the interface, is a Spring bean) with {@link
 * com.agenticai.core.approval.ApprovalActionExecutorRegistry} for action type {@link #ACTION_TYPE};
 * nothing in {@code HumanApprovalGateService} changed to add this - that's the whole point of Stage
 * 1's design.
 *
 * <p>Callers never invoke this class directly - they call {@code
 * HumanApprovalGateService.evaluate(ACTION_TYPE, summary, payloadJson)} (see {@link
 * WhatsAppSendController}). This class only runs once a human has approved the request (or the
 * policy auto-approves it), via {@code HumanApprovalGateService.approve}.
 *
 * <p><b>Class name is now slightly historical</b>: the actual send is delegated to {@link
 * WhatsAppDispatchService}, which sends via a tenant's direct Meta Cloud API connection if one is
 * configured, falling back to Twilio otherwise (see that class's javadoc). {@code ACTION_TYPE}
 * stays {@code "channel.whatsapp.send"} - a provider-agnostic action name that predates the Meta
 * integration and needs no change, since "send a WhatsApp message" is the actual capability being
 * approval-gated here, not "send via Twilio specifically." Renaming this class was judged not worth
 * the churn across {@code HumanApprovalGateService}/test references for a cosmetic-only change.
 */
@Component
public class TwilioWhatsAppChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.whatsapp.send";

    private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final WhatsAppDispatchService dispatchService;
    private final ChannelOutboundLogger outboundLogger;

    public TwilioWhatsAppChannelAdapter(
            WhatsAppDispatchService dispatchService, ChannelOutboundLogger outboundLogger) {
        this.dispatchService = dispatchService;
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
            String result = dispatchService.send(payload.to(), payload.body());
            log.info("{} (recipient {})", result, maskRecipient(payload.to()));
            outboundLogger.recordSent(
                    ChannelType.WHATSAPP, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            // Retryable (Autonomy/Guardrails Strategy Stage C): unlike the
            // payload-validation failures in parsePayload() below, an
            // upstream API call itself failing (Twilio or Meta) is very
            // plausibly transient (network blip, rate limit, momentary
            // outage) - worth HumanApprovalGateService scheduling a retry
            // rather than giving up on the first attempt. Known limitation,
            // stated honestly: neither provider path here uses idempotency-
            // key support, so a retry after a false-negative (the message
            // actually sent, but the success response was lost before we
            // saw it) could in principle double-send. Real follow-up work,
            // not blocking this stage - the alternative (never retrying a
            // transient failure at all) is worse in practice.
            throw new RetryableChannelDeliveryException(
                    "whatsapp", "WhatsApp send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException(
                    "whatsapp", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "whatsapp", "Could not parse WhatsApp send payload: " + e.getMessage(), e);
        }
    }

    // Never logs a full phone number - the last 4 digits are enough to
    // recognize a specific test recipient without putting a real number in
    // application logs.
    private String maskRecipient(String to) {
        if (to == null || to.length() < 4) {
            return "***";
        }
        return "***" + to.substring(to.length() - 4);
    }

    public record SendPayload(String to, String body) {}
}
