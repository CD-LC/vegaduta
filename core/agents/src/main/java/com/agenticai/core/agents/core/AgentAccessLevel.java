package com.agenticai.core.agents.core;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;

/**
 * The caller's effective share level on one agent, for {@code AgentResponse.accessLevel}. Probes
 * {@link ResourceAccessService#requireAccess} (owner / tenant-admin / grant / legacy-open) from the
 * top down, so the answer can never disagree with what the endpoints enforce. Display hint only.
 */
final class AgentAccessLevel {

    private AgentAccessLevel() {}

    /** "EDITOR" (owner, admin or EDITOR grant), "VIEWER" or "RUNNER"; null when it cannot be resolved or the caller has no access. */
    static String of(ResourceAccessService access, Agent agent) {
        try {
            if (allows(access, agent, PermissionLevel.EDITOR)) {
                return "EDITOR";
            }
            // RUNNER < VIEWER < EDITOR and a higher level satisfies a lower check: probe top down.
            if (allows(access, agent, PermissionLevel.VIEWER)) {
                return "VIEWER";
            }
            return allows(access, agent, PermissionLevel.RUNNER) ? "RUNNER" : null;
        } catch (RuntimeException e) {
            // No request context / grant store unavailable: a display hint must never fail a read.
            return null;
        }
    }

    private static boolean allows(ResourceAccessService access, Agent agent, PermissionLevel required) {
        try {
            access.requireAccess(ResourceType.AGENT, agent.getId(), agent.getCreatedBy(), required);
            return true;
        } catch (ResourceAccessDeniedException e) {
            return false;
        }
    }
}
