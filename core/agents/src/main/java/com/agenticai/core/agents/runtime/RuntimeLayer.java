package com.agenticai.core.agents.runtime;

/**
 * Where one effective runtime setting came from. Layers are applied in this order, each overriding
 * the one before it: {@link #PLATFORM} (the operator's {@code application*.yml}), {@link #TENANT}
 * (the tenant admin's defaults for every agent of the tenant), {@link #AGENT} (the agent's own
 * overrides) and {@link #REQUEST} (a cap one run carries with it, such as an agent job's remaining
 * tool-call budget; it can only tighten a setting, never loosen it).
 */
public enum RuntimeLayer {
    PLATFORM,
    TENANT,
    AGENT,
    REQUEST
}
