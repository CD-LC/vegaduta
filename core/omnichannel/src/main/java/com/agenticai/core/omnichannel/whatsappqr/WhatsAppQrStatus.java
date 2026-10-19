package com.agenticai.core.omnichannel.whatsappqr;

/**
 * Lifecycle of a {@link WhatsAppQrConnection} row:
 *
 * <ul>
 *   <li>{@link #PAIRING} - a bridge session exists and is showing (or about to show) its QR; nobody
 *       has scanned it yet.
 *   <li>{@link #ACTIVE} - the QR was scanned, WhatsApp reports the linked device connected, and
 *       inbound/outbound are live.
 *   <li>{@link #DISCONNECTED} - the tenant unlinked (or WhatsApp logged the device out); kept so the
 *       console can still show what was connected. Every consumer resolves ACTIVE-only.
 * </ul>
 */
public enum WhatsAppQrStatus {
    PAIRING,
    ACTIVE,
    DISCONNECTED
}
