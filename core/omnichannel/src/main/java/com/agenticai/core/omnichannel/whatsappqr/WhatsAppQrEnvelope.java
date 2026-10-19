package com.agenticai.core.omnichannel.whatsappqr;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * One inbound message as the bridge queues it (see {@code services/whatsapp-qr/server.js}):
 * a direct chat's text (or an attachment note). Groups, broadcasts and the account's own echoes
 * are dropped on the bridge; anything still malformed or unsendable-to is dropped here too, so
 * the rest of the pipeline only sees a message a person sent to the linked number.
 *
 * @param externalId session/send key - digits, or {@code <digits>@lid} for a hidden-number sender
 * @param senderNumber the dialable digits when known (null for a LID sender)
 * @param pushName the sender's profile name, as WhatsApp gives it (display only, may be spoofed)
 */
public record WhatsAppQrEnvelope(
        String externalId,
        String senderNumber,
        String pushName,
        String text,
        String messageId,
        long timestampMs) {

    /** Longest text handed to the agent; the bridge caps at 8000, this is a second abuse cap. */
    static final int MAX_TEXT_CHARS = 8_000;

    /** Idempotency key: the bridge drains its queue once, but Baileys can redeliver on reconnect. */
    public String dedupKey() {
        return externalId + ":" + messageId;
    }

    public static Optional<WhatsAppQrEnvelope> parse(JsonNode event) {
        if (event == null || !event.isObject()) {
            return Optional.empty();
        }
        String externalId = WhatsAppQrAddress.externalId(event.path("chatJid").asText(null));
        if (externalId == null) {
            return Optional.empty();
        }
        String text = sanitize(event.path("text").asText(""));
        if (text.isBlank()) {
            return Optional.empty();
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        String messageId = event.path("id").asText("");
        if (messageId.isBlank() || messageId.length() > 80) {
            // No stable id means no dedup and the message is replayed on every reconnect - drop it.
            return Optional.empty();
        }
        String senderNumber = null;
        String raw = event.path("senderNumber").asText(null);
        if (raw != null && raw.matches("[0-9]{5,20}")) {
            senderNumber = raw;
        }
        String pushName = event.path("pushName").asText(null);
        if (pushName != null) {
            pushName = cap(sanitize(pushName).replace('\n', ' '), 100);
            if (pushName.isBlank()) {
                pushName = null;
            }
        }
        long ts = event.path("timestampMs").asLong(0);
        return Optional.of(
                new WhatsAppQrEnvelope(externalId, senderNumber, pushName, text, messageId, ts));
    }

    private static String cap(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Drops control characters other than newline and tab (a sender controls this text). */
    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString().strip();
    }
}
