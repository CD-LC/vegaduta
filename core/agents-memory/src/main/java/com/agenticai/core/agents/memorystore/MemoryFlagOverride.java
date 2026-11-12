package com.agenticai.core.agents.memorystore;

import java.util.UUID;

/**
 * One lower level (tenant, agent) of the unified-memory flag. It can only say no: a level that
 * returns {@code false} switches unified memory off for that agent; returning {@code true} never
 * turns anything on that the platform did not. Registered as beans; the settings-backed
 * implementations arrive with the recall wiring (WP4).
 */
@FunctionalInterface
public interface MemoryFlagOverride {

    boolean allows(UUID agentId);
}
