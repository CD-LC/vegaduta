package com.agenticai.core.agents.memory;

/**
 * Whether a long-term memory row may be recalled and injected into a prompt. A row written while
 * the turn had outsider-authored content in its context ({@link TurnTaintView#isTainted()}) is
 * {@link #QUARANTINED} until a person approves it: it is still attacker-authored when recalled
 * next month (OWASP ASI06 / LLM04).
 */
public enum MemoryTrust {
    TRUSTED,
    QUARANTINED;

    /** Parses a stored value; anything unknown is QUARANTINED (fail closed). */
    public static MemoryTrust parse(String stored) {
        return TRUSTED.name().equals(stored) ? TRUSTED : QUARANTINED;
    }
}
