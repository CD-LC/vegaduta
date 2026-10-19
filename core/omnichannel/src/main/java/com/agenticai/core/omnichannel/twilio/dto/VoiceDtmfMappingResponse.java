package com.agenticai.core.omnichannel.twilio.dto;

import com.agenticai.core.omnichannel.twilio.VoiceDtmfMapping;
import java.util.UUID;

/** One row of {@code GET /api/channels/voice/dtmf-menu} - {@code agentName} is denormalized in here purely so the ChannelsPanel UI doesn't need a second round trip against {@code /api/agents} just to label each digit. */
public record VoiceDtmfMappingResponse(String digit, UUID agentId, String agentName, String label) {

    public static VoiceDtmfMappingResponse from(VoiceDtmfMapping mapping, String agentName) {
        return new VoiceDtmfMappingResponse(mapping.getDigit(), mapping.getAgentId(), agentName, mapping.getLabel());
    }
}
