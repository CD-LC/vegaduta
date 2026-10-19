package com.agenticai.core.omnichannel;

/**
 * Outbound phone calls that speak one short message - the third delivery channel for Simple mode's
 * Quick Task feature. Kernel-owned (ADR-0011 rule a); implemented over the tenant's Twilio
 * credential by {@code twilio.TwilioVoiceCallService}.
 */
public interface VoiceCallService {

    /**
     * @param spokenMessage the sentence to say; implementations normalize it for speech first
     * @return a short, human-readable log line describing what happened.
     * @throws ChannelDeliveryException when the provider rejects the call
     */
    String call(String to, String spokenMessage);
}
