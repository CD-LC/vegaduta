package com.agenticai.core.agents.tools;

import java.util.Optional;
import java.util.UUID;

/**
 * Saves a finished {@code local_desktop_*} run as a re-runnable desktop recipe. Implemented by the
 * desktop feature (agents-tools); the learning loops (agents-jobs) reach it through this port so
 * the jobs shape works when the desktop feature is not on the classpath or is disabled.
 *
 * <p>Runs on the approving person's thread with their tenant context in place; the implementation
 * enforces the usual editor permission on the agent.
 */
public interface DesktopRecipeSavePort {

    /**
     * Turns the session's recorded {@code local_desktop_*} calls into a saved recipe.
     *
     * @param name the recipe's display name
     * @return the stored recipe's id, or empty when the run held nothing recordable
     */
    Optional<UUID> saveRunAsRecipe(UUID agentId, String sessionId, String name);
}
