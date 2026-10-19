package com.agenticai.core.omnichannel.twilio;

/** No {@link VoiceNumberAgent} exists for the given number - {@code DELETE /api/channels/voice/numbers/{number}} on an unmapped number. */
public class VoiceNumberAgentNotFoundException extends RuntimeException {

    public VoiceNumberAgentNotFoundException(String phoneNumber) {
        super("No voice number mapping found for '" + phoneNumber + "'");
    }
}
