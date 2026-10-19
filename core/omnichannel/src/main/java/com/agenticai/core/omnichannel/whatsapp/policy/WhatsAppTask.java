package com.agenticai.core.omnichannel.whatsapp.policy;

import java.util.Locale;
import java.util.Optional;

/** The business task a tenant declares for an agent attached to a WhatsApp number. */
public enum WhatsAppTask {
    SUPPORT,
    BOOKING,
    ORDERS,
    LEAD_QUALIFICATION,
    REMINDERS,
    PAYMENTS_FOLLOWUP;

    public static Optional<WhatsAppTask> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
