package com.agenticai.core.agents.memory.spi;

/**
 * Whether a memory may be recalled into a prompt. Same two states and the same fail-closed parse
 * as {@code agents.memory.MemoryTrust} in agents-turn (which is not visible to the kernel and is
 * not moved in this phase); an unknown stored value is {@link #QUARANTINED}.
 */
public enum MemoryTrustLevel {
    TRUSTED,
    QUARANTINED;

    public static MemoryTrustLevel parse(String stored) {
        return TRUSTED.name().equals(stored) ? TRUSTED : QUARANTINED;
    }
}
