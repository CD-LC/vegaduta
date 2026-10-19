package com.agenticai.core.agents.tools.classification;

import java.util.Locale;

/**
 * How hard the platform pushes back when an agent ends up holding all three
 * legs of the "lethal trifecta" (private data + untrusted content + egress, see
 * {@link LethalTrifectaDetector}). Configured by
 * {@code app.tools.classification.trifecta-enforcement}.
 *
 * <ul>
 *   <li>{@link #OFF} - no analysis, no warning, no enforcement.</li>
 *   <li>{@link #WARN} - log + count + surface the advisory (the behaviour before
 *       enforcement existed, and the default).</li>
 *   <li>{@link #REQUIRE_APPROVAL} - a save that leaves the agent with all three
 *       legs forces its autonomy tier to {@code SUPERVISED} (the tier that maps
 *       to {@code ApprovalGateMode.ALWAYS_GATE}).</li>
 *   <li>{@link #BLOCK} - a save that makes the trifecta worse is rejected with a 400.</li>
 * </ul>
 * An unrecognised configured value is treated as {@link #WARN}.
 */
public enum TrifectaEnforcementMode {
    OFF,
    WARN,
    REQUIRE_APPROVAL,
    BLOCK;

    /** The lower-case, hyphenated form used in config and on the wire ({@code require-approval}). */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** True for a blank value (use the default) or one of the four known spellings. */
    public static boolean isRecognised(String raw) {
        return raw == null || raw.isBlank() || lookup(raw) != null;
    }

    /** Never throws: null, blank and unknown values all resolve to {@link #WARN}. */
    public static TrifectaEnforcementMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return WARN;
        }
        TrifectaEnforcementMode found = lookup(raw);
        return found != null ? found : WARN;
    }

    private static TrifectaEnforcementMode lookup(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (TrifectaEnforcementMode mode : values()) {
            if (mode.wireName().equals(key)) {
                return mode;
            }
        }
        return null;
    }
}
