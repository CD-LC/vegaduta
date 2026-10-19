package com.agenticai.core.omnichannel.whatsappqr;

/**
 * What the console's WhatsApp QR card shows. {@code qr} is the raw pairing payload ONLY while the
 * session waits for a scan - it lets anyone who sees it complete a link as this tenant, so it is
 * returned only over the authenticated channel endpoints, never logged, and never stored in the
 * row. {@code phoneNumber} is the linked account's number once connected.
 *
 * @param status {@code PAIRING}, {@code ACTIVE} or {@code DISCONNECTED}
 * @param bridgeStatus the bridge's live word ({@code QR_PENDING}, {@code CONNECTED},
 *     {@code RECONNECTING}, {@code LOGGED_OUT}) - the console can tell "reconnecting" from "dead"
 * @param qr the current pairing payload while pairing, else null
 */
public record WhatsAppQrStatusResponse(
        String status, String bridgeStatus, String qr, String phoneNumber, boolean configured) {}
