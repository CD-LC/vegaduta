package com.agenticai.core.common.quicktask;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.SmsDispatchService;
import com.agenticai.core.omnichannel.VoiceCallService;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a finished Quick Task answer goes: nowhere for {@link QuickTaskChannel#LOG}, the
 * requester's own number for a self-only run (WhatsApp, SMS or a voice call, ungated - requester
 * and recipient are the same person), or, when the task text names a DIFFERENT number, through the
 * same {@link HumanApprovalGateService} gate every other third-party WhatsApp send uses (#598
 * follow-up). Created and owned by {@link QuickTaskService}; not a bean.
 */
final class QuickTaskDelivery {

    // #598 follow-up ("let Quick Task detect & send to a typed number") -
    // deliberately loose: matches a run of 8-15 digits, optionally
    // "+"-prefixed. Good enough to spot "send this to +9188869935431"; false
    // positives (matching some other long digit run in the task text) just
    // mean extractThirdPartyNumber's normalized-digit comparison against the
    // requester's own number fails to exempt it, which routes to the
    // approval gate below rather than silently mis-sending - the safe
    // direction to be wrong in.
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\+?\\d{8,15}");

    private final WhatsAppDispatchService whatsAppDispatchService;
    private final SmsDispatchService smsDispatchService;
    private final VoiceCallService voiceCallService;
    private final HumanApprovalGateService approvalGateService;
    private final ObjectMapper objectMapper;
    // WhatsApp's approval action type and SendPayload, owned by the channel
    // package (ADR-0011 rule a).
    private final ChannelTextSends channelTextSends;

    QuickTaskDelivery(
            WhatsAppDispatchService whatsAppDispatchService,
            SmsDispatchService smsDispatchService,
            VoiceCallService voiceCallService,
            HumanApprovalGateService approvalGateService,
            ObjectMapper objectMapper,
            ChannelTextSends channelTextSends) {
        this.whatsAppDispatchService = whatsAppDispatchService;
        this.smsDispatchService = smsDispatchService;
        this.voiceCallService = voiceCallService;
        this.approvalGateService = approvalGateService;
        this.objectMapper = objectMapper;
        this.channelTextSends = channelTextSends;
    }

    /**
     * Delivers {@code resultText} for one run and returns the human-readable delivery note.
     *
     * @param message the task's own text, scanned for a third-party number
     * @param phoneNumber the requester's own number (null only for {@code LOG})
     */
    String deliver(
            QuickTaskChannel channel,
            String message,
            String phoneNumber,
            String resultText,
            String keycloakUserId) {
        // #598 follow-up ("send a quick message to +91...") - a task
        // naming a DIFFERENT phone number than the requester's own is a
        // genuine third-party send, which is exactly the case
        // QuickTaskService's own class javadoc says never needs
        // HumanApprovalGateService, because "the requester and the
        // recipient are the same person." That reasoning only holds when
        // they actually are - once a task's text names someone else's
        // number, this is no longer that case, so it must go through the
        // same gate any other outbound-to-a-third-party send already
        // does (see dispatchToThirdParty's javadoc), never a direct
        // dispatch just because the destination arrived via free text
        // instead of a form field.
        // LOG never dispatches anywhere and never needs the third-party-
        // number check (that check exists purely to decide which gate a
        // real send goes through - moot when nothing is being sent) -
        // see QuickTaskChannel#LOG's javadoc.
        String deliveryNote;
        if (channel == QuickTaskChannel.LOG) {
            deliveryNote = "Logged only - result recorded, nothing sent to WhatsApp/SMS/call.";
        } else {
            Optional<String> thirdPartyNumber =
                    extractThirdPartyNumber(message, phoneNumber);
            deliveryNote =
                    thirdPartyNumber.isPresent()
                            ? dispatchToThirdParty(
                                    channel,
                                    thirdPartyNumber.get(),
                                    resultText,
                                    keycloakUserId)
                            : switch (channel) {
                                case WHATSAPP ->
                                        whatsAppDispatchService.send(phoneNumber, resultText);
                                case SMS -> smsDispatchService.send(phoneNumber, resultText);
                                case CALL -> voiceCallService.call(phoneNumber, resultText);
                                case LOG ->
                                        "Logged only - result recorded, nothing sent to WhatsApp/SMS/call.";
                            };
        }
        return deliveryNote;
    }

    /**
     * Routes a third-party send through the exact same {@link HumanApprovalGateService#evaluate}
     * call {@code WhatsAppSendController#send} already uses for arbitrary outbound WhatsApp sends -
     * so this tenant's own approval policy (auto-approve vs. require a human click in the Approvals
     * inbox) governs a Quick Task third-party send identically to any other
     * outbound-to-a-third-party send already in this platform. No special Quick Task bypass.
     *
     * <p>Scoped to WhatsApp only for now - {@code SmsDispatchService}/{@code VoiceCallService} have
     * no equivalent approval-gated "send to an arbitrary number" entry point in this codebase yet
     * (their only callers today are the self-only paths above), so a third-party number typed into
     * an SMS/call task gets a clear, honest error instead of either silently sending ungated or
     * silently doing nothing.
     */
    private String dispatchToThirdParty(
            QuickTaskChannel channel, String to, String body, String keycloakUserId) {
        if (channel != QuickTaskChannel.WHATSAPP) {
            throw new QuickTaskNotReadyException(
                    "\""
                            + to
                            + "\" looks like a different phone number than yours - Quick Task can only route a third-party "
                            + "send through WhatsApp today (SMS/phone call delivery is self-only). Switch the delivery channel to "
                            + "WhatsApp, or remove the number from your task text to send the answer to your own number instead.");
        }
        ChannelTextSend whatsApp =
                channelTextSends
                        .forChannel(ChannelType.WHATSAPP)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No WhatsApp send path is registered"));
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(whatsApp.payload(to, null, body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize Quick Task third-party WhatsApp payload", e);
        }
        ApprovalEvaluation evaluation =
                approvalGateService.evaluate(
                        whatsApp.actionType(),
                        "Quick Task: send WhatsApp message to " + to,
                        payloadJson,
                        keycloakUserId);
        return evaluation.approved()
                ? "Sent \"" + truncate(body, 160) + "\" to " + to + " via WhatsApp."
                : "Held for approval before sending to "
                        + to
                        + " - check the Approvals inbox (request "
                        + evaluation.pendingRequestId()
                        + ").";
    }

    /**
     * Scans the task's own text for something that looks like a phone number and returns it only if
     * it's a DIFFERENT number than the requester's own (comparing normalized digits only - see
     * {@link #PHONE_PATTERN}'s javadoc on why a false "different" is the safe failure direction
     * here, not a false "same"). Returns empty when the message has no such number, or the only
     * number found is the requester's own - both cases keep today's self-only, ungated delivery.
     */
    static Optional<String> extractThirdPartyNumber(String message, String ownPhoneNumber) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher matcher = PHONE_PATTERN.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String candidate = matcher.group();
        return normalizePhone(candidate).equals(normalizePhone(ownPhoneNumber))
                ? Optional.empty()
                : Optional.of(candidate);
    }

    private static String normalizePhone(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
