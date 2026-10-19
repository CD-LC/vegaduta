package com.agenticai.core.omnichannel.demo;

/**
 * The one WhatsApp Cloud API call {@link DemoWhatsAppBot} needs, owned by the demo package so the
 * shared demo bot does not import the Meta channel adapter (ADR-0011 rule a). Implemented in {@code
 * omnichannel.meta} by {@code MetaDemoWhatsAppCloudApi}.
 */
public interface DemoWhatsAppCloudApi {

    /** Sends a text message from the demo number; throws on failure. @return a short log line. */
    String sendTextMessage(String phoneNumberId, String accessToken, String toE164, String body);
}
