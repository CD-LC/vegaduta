package com.agenticai.core.omnichannel.agentcontrol;

import com.agenticai.core.agents.tools.messaging.ChannelSendPort;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The {@code core/omnichannel} side of {@link ChannelSendPort} - see that interface's javadoc for
 * why agents reach channel sends through a port at all. Every branch here ends in {@link
 * HumanApprovalGateService#evaluate} with the channel's own {@code channel.*.send} ACTION_TYPE and
 * its own SendPayload record, byte-for-byte the same call the console's {@code *SendController}s
 * make - the agent path and the human path are indistinguishable to the approval queue, which is
 * the point: governance cannot tell (or need to tell) who asked.
 */
@Component
public class ChannelSendPortImpl implements ChannelSendPort {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final ChannelTextSends textSends;

    public ChannelSendPortImpl(HumanApprovalGateService gateService, ChannelTextSends textSends) {
        this.gateService = gateService;
        this.textSends = textSends;
    }

    @Override
    public ChannelSendResult send(
            String channel, String to, String subject, String body, String requestedBy) {
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("channel-send");
        String normalized = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        ChannelTextSend send =
                channelType(normalized)
                        .flatMap(textSends::forChannel)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown channel '"
                                                        + channel
                                                        + "' - expected one of: whatsapp, telegram,"
                                                        + " slack, discord, teams, email,"
                                                        + " messenger, instagram, x"));
        String actionType = send.actionType();
        String summary = send.summary(to);
        Object payload;
        try {
            payload = send.payload(to, subject, body);
        } catch (ChannelTextSend.RecipientNotAddressableException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }

        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    normalized, "Failed to serialize " + normalized + " send payload", e);
        }

        ApprovalEvaluation evaluation =
                gateService.evaluate(actionType, summary, payloadJson, requestedBy);
        return new ChannelSendResult(
                evaluation.approved(),
                evaluation.pendingRequestId() == null
                        ? null
                        : evaluation.pendingRequestId().toString(),
                summary);
    }

    /**
     * The agent-facing channel names are the {@link ChannelType} names; VOICE has no queued send
     * path.
     */
    private static Optional<ChannelType> channelType(String normalized) {
        if (normalized.isEmpty() || normalized.equals("voice")) {
            return Optional.empty();
        }
        try {
            return Optional.of(ChannelType.valueOf(normalized.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
