package com.agenticai.core.agents.runtime;

import java.util.function.Supplier;

/**
 * The turn's resolved {@link AgentRuntimeSettings}, shared by reference between the stage that resolves
 * them (client selection, once the agent is loaded) and the stages that read them (tool resolution, the
 * stream and buffered loops, continuation). A mutable holder like {@code TurnToolActivity} so a {@code
 * TurnContext} copy made before the settings were resolved sees them afterwards.
 */
public final class TurnRuntime {

    private volatile AgentRuntimeSettings settings;

    /** Records the turn's settings; the first call wins so a retry of a stage cannot change a running turn. */
    public void set(AgentRuntimeSettings resolved) {
        if (resolved != null && settings == null) {
            settings = resolved;
        }
    }

    /** The resolved settings, or null before they were resolved. */
    public AgentRuntimeSettings settings() {
        return settings;
    }

    /** The resolved settings, or {@code fallback} when none were resolved (hand-built contexts, tests). */
    public AgentRuntimeSettings settingsOr(Supplier<AgentRuntimeSettings> fallback) {
        AgentRuntimeSettings resolved = settings;
        return resolved != null ? resolved : fallback.get();
    }
}
