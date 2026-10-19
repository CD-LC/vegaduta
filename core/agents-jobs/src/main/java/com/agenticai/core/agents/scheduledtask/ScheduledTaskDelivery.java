package com.agenticai.core.agents.scheduledtask;

import java.util.Locale;

/**
 * Where a scheduled task's result goes. {@link #IN_APP} is the platform notification centre; the rest are outbound
 * channels reached through {@code ChannelSendPort} (the platform's approval gate and each adapter's own consent
 * rules - never a direct adapter call).
 */
public enum ScheduledTaskDelivery {

    IN_APP("in_app", null),
    EMAIL("email", "email"),
    WHATSAPP("whatsapp", "whatsapp"),
    WHATSAPP_QR("whatsapp_qr", "whatsapp_qr"),
    TELEGRAM("telegram", "telegram"),
    SLACK("slack", "slack"),
    SIGNAL("signal", "signal"),
    MATRIX("matrix", "matrix"),
    IMESSAGE("imessage", "imessage"),
    /** An external A2A agent (the "A2A bridge"), delivered through the outbound client, not ChannelSendPort. */
    A2A("a2a", "a2a"),
    /** An outbound phone call that speaks the result (VoiceCallService), not ChannelSendPort. */
    VOICE("voice", "voice");

    private final String key;
    private final String channelKey;

    ScheduledTaskDelivery(String key, String channelKey) {
        this.key = key;
        this.channelKey = channelKey;
    }

    /** The lower-case API/config spelling. */
    public String key() {
        return key;
    }

    /** The {@code ChannelSendPort} channel name, or {@code null} for {@link #IN_APP}. */
    public String channelKey() {
        return channelKey;
    }

    public boolean isExternal() {
        return channelKey != null;
    }

    /** Parses the API spelling ({@code in_app}, {@code whatsapp}, ...); blank means {@link #IN_APP}. */
    public static ScheduledTaskDelivery parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return IN_APP;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (ScheduledTaskDelivery d : values()) {
            if (d.key.equals(normalized)) {
                return d;
            }
        }
        throw new IllegalArgumentException(
            "Invalid delivery '" + raw + "': use in_app, email, whatsapp, whatsapp_qr, telegram, slack, signal, matrix, imessage, a2a or voice.");
    }
}
