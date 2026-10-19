package com.agenticai.core.omnichannel;

/**
 * Plain SMS send - built for Simple mode's Quick Task feature ("type what you want, get the result
 * on WhatsApp, SMS, or a call"). Kernel-owned (ADR-0011 rule a); implemented over the tenant's
 * Twilio credential by {@code twilio.TwilioSmsDispatchService}. Meta's Cloud API is WhatsApp-only,
 * so Twilio is the only SMS path regardless of which WhatsApp provider a tenant has activated.
 */
public interface SmsDispatchService {

    /**
     * @return a short, human-readable log line describing what happened.
     * @throws ChannelDeliveryException when the provider rejects the send
     */
    String send(String to, String body);
}
