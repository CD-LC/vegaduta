package com.agenticai.core.agents.memory.spi;

/**
 * Whether a memory may be recalled into a prompt. Same two states and the same fail-closed parse as
 * {@code agents.memory.MemoryTrust} in agents-turn (which is not visible to the kernel and is not
 * moved in this phase); an unknown stored value is {@link #QUARANTINED}.
 */
public enum MemoryTrustLevel {
    TRUSTED,
    QUARANTINED;

    public static MemoryTrustLevel parse(String stored) {
        return TRUSTED.name().equals(stored) ? TRUSTED : QUARANTINED;
    }

    /**
     * The lowest trust of the given levels: TRUSTED only if every one is TRUSTED, so a derived item
     * is never more trusted than its least trusted source. Empty input is QUARANTINED (fail
     * closed).
     */
    public static MemoryTrustLevel meet(java.util.Collection<MemoryTrustLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return QUARANTINED;
        }
        for (MemoryTrustLevel level : levels) {
            if (level != TRUSTED) {
                return QUARANTINED;
            }
        }
        return TRUSTED;
    }
}
