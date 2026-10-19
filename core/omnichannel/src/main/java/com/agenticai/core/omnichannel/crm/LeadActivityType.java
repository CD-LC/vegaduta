package com.agenticai.core.omnichannel.crm;

import java.util.Locale;

public enum LeadActivityType {
    NOTE, EMAIL_SENT, EMAIL_REPLY, STAGE_CHANGE, CALL, TASK;

    public static LeadActivityType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
