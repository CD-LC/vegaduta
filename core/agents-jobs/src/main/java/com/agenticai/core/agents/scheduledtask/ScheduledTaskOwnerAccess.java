package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceGrant;
import com.agenticai.core.sharing.ResourceGrantRepository;
import com.agenticai.core.sharing.ResourceType;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Re-checks, at FIRING time, that a task's owner can still run the task's agent.
 *
 * <p>Creation checks {@code AgentService#requireAccess(.., RUNNER)} against the request's own {@code
 * TenantContext}; the tick has no such request, and a task keeps running with the owner's identity, budget and
 * external delivery target. Without this, revoking the owner's grant (or the agent changing hands) would not stop
 * an existing schedule. The rule mirrors {@code ResourceAccessService#requireAccess} for RUNNER minus the
 * admin-override branch (a task run has no roles to evaluate): allowed when the agent is a legacy ownerless row,
 * the owner created the agent, or the owner holds an unrevoked grant of at least RUNNER.
 *
 * <p>Known limit: tenant membership itself is not re-verified here (that lives in Keycloak); removing a user from
 * the workspace is covered only insofar as their grants are revoked. A tenant admin who scheduled a colleague's
 * agent purely through the admin override has no grant to find, so their task would be refused - set {@code
 * app.scheduled-tasks.recheck-owner-access=false} to disable the check.
 */
@Component
public class ScheduledTaskOwnerAccess {

    private final ResourceGrantRepository grants;
    private final boolean enabled;

    @Autowired
    public ScheduledTaskOwnerAccess(
        ResourceGrantRepository grants,
        @Value("${app.scheduled-tasks.recheck-owner-access:true}") boolean enabled
    ) {
        this.grants = grants;
        this.enabled = enabled;
    }

    /** Returns null when the owner may still run the agent, otherwise the reason to record on the failed run. */
    public String problem(ScheduledTask task, Agent agent) {
        if (!enabled) {
            return null;
        }
        String createdBy = agent.getCreatedBy();
        String owner = task.getOwnerSubject();
        if (createdBy == null || createdBy.equals(owner)) {
            return null;
        }
        Optional<ResourceGrant> grant = grants.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
            ResourceType.AGENT, agent.getId(), owner);
        if (grant.isPresent() && grant.get().getPermissionLevel().compareTo(PermissionLevel.RUNNER) >= 0) {
            return null;
        }
        return "The person who created this task no longer has access to the agent it runs, so it was stopped.";
    }
}
