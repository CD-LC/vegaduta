package com.agenticai.core.omnichannel.agentcontrol;

import com.agenticai.core.agents.tools.messaging.VoiceDeliveryPort;
import com.agenticai.core.omnichannel.VoiceCallService;
import org.springframework.stereotype.Service;

/**
 * The {@code core/omnichannel} side of {@link VoiceDeliveryPort}: places the call through the
 * kernel's {@link VoiceCallService} port, whose provider implementation (today Twilio) applies the
 * tenant's own voice credential and consent rules. The call is a normal channel send upstream of
 * this adapter - the scheduled-task path reaches it only after the approval gate, so a voice
 * delivery parks in approvals exactly like a WhatsApp one.
 */
@Service
public class VoiceDeliveryPortImpl implements VoiceDeliveryPort {

    private final VoiceCallService calls;

    public VoiceDeliveryPortImpl(VoiceCallService calls) {
        this.calls = calls;
    }

    @Override
    public String call(String to, String spokenMessage, String requestedBy) {
        return calls.call(to, spokenMessage);
    }
}
