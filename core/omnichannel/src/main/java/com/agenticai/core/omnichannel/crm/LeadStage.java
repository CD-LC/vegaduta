package com.agenticai.core.omnichannel.crm;

import java.util.Locale;

/** Pipeline stages, in board order. WON and LOST are terminal. */
public enum LeadStage {
    NEW, CONTACTED, REPLIED, QUALIFIED, PROPOSAL, WON, LOST;

    /** Lenient parse for CSV / workflow config ("qualified", " Won "); null for blank, IllegalArgumentException otherwise. */
    public static LeadStage parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
