package com.agenticai.core.agents.skills;

/**
 * Review state of a custom skill (migration V590). Only {@link #ACTIVE} skills reach an agent's
 * prompt: the runtime still reads the {@code enabled} column, and the application keeps {@code
 * enabled == (status == ACTIVE)}.
 */
public enum SkillStatus {
    /** Written by an agent (or the learning loop) and waiting for a human to read it. */
    PENDING,
    /** Reviewed and live: advertised to every agent whose scope allows it. */
    ACTIVE,
    /** A reviewer declined the draft. Kept (with a note) so the loop does not re-propose it. */
    REJECTED,
    /** Switched off. Content and history are kept; it can be approved again. */
    ARCHIVED
}
