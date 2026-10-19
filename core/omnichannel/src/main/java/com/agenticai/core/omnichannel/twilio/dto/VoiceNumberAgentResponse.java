package com.agenticai.core.omnichannel.twilio.dto;

import com.agenticai.core.omnichannel.twilio.VoiceNumberAgent;
import java.util.UUID;

/** One row of {@code GET /api/channels/voice/numbers} - {@code agentName} is denormalized in here for the same reason {@link VoiceDtmfMappingResponse} does it: the ChannelsPanel UI should not need a second round trip against {@code /api/agents} just to label each number. */
public record VoiceNumberAgentResponse(String phoneNumber, UUID agentId, String agentName, String label) {

    public static VoiceNumberAgentResponse from(VoiceNumberAgent mapping, String agentName) {
        return new VoiceNumberAgentResponse(mapping.getPhoneNumber(), mapping.getAgentId(), agentName, mapping.getLabel());
    }
}
