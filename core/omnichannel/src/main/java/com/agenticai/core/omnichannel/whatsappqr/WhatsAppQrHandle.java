package com.agenticai.core.omnichannel.whatsappqr;

/**
 * What an ACTIVE {@link WhatsAppQrConnection} gives the send/receive paths: which bridge session
 * speaks and which number the tenant linked. It is deliberately just these two - no QR payload and
 * no credential material (which only ever lives on the bridge).
 */
public record WhatsAppQrHandle(String sessionKey, String phoneNumber) {}
