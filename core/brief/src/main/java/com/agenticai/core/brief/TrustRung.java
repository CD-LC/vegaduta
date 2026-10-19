package com.agenticai.core.brief;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import java.util.Locale;

/**
 * The three rungs of earned autonomy (contract section 1) and their deterministic mapping onto the
 * platform's REAL agent autonomy tiers. The mapping is by position in {@link AgentAutonomyTier}
 * (lowest / middle / highest value the enum offers) rather than by name, so it is the enum - not a
 * copy of its names in this file - that decides what "lowest" is. Today that resolves to
 * SUPERVISED / SEMI_AUTONOMOUS / FULLY_AUTONOMOUS; a test pins it so a change to the enum is seen.
 */
public enum TrustRung {
    UNDER_WATCH,
    TRUSTED,
    AUTONOMOUS;

    /** Tier applied to every agent of an envoy at this rung. */
    public AgentAutonomyTier tier() {
        AgentAutonomyTier[] tiers = AgentAutonomyTier.values();
        return switch (this) {
            case UNDER_WATCH -> tiers[0];
            case TRUSTED -> tiers[tiers.length / 2];
            case AUTONOMOUS -> tiers[tiers.length - 1];
        };
    }

    /** The rung one step up, or null at the top. */
    public TrustRung up() {
        return this == AUTONOMOUS ? null : values()[ordinal() + 1];
    }

    /** The rung one step down, or null at the bottom. */
    public TrustRung down() {
        return this == UNDER_WATCH ? null : values()[ordinal() - 1];
    }

    public boolean isAbove(TrustRung other) {
        return ordinal() > other.ordinal();
    }

    /** Lenient parse (case-insensitive); null for anything that is not a rung. */
    public static TrustRung parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Parse with the safe default: anything unreadable is the lowest rung. */
    public static TrustRung parseOrLowest(String raw) {
        TrustRung rung = parse(raw);
        return rung == null ? UNDER_WATCH : rung;
    }
}
