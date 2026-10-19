package com.agenticai.core.agents.tools.messaging;

/**
 * Place an outbound phone call that speaks a short message - the {@code voice} delivery channel for
 * scheduled tasks (the kernel-side port like {@link ChannelSendPort}: the implementation lives in
 * omnichannel on top of the tenant's voice credential).
 */
public interface VoiceDeliveryPort {

    /**
     * @param to E.164 phone number to call.
     * @param spokenMessage the sentence to say; implementations normalize it for speech.
     * @param requestedBy attribution, e.g. {@code "scheduled-task:<uuid>"}.
     * @return a short, human-readable line describing what happened.
     * @throws com.agenticai.core.omnichannel.ChannelDeliveryException-style RuntimeException when the
     *     provider rejects the call - kept untyped here so agents does not import omnichannel.
     */
    String call(String to, String spokenMessage, String requestedBy);
}
