package com.agenticai.core.workflow;

import com.agenticai.core.security.EditLockPolicy;
import com.agenticai.core.security.EditLockedException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loading and access/plan gates for {@link WorkflowDefinition}, split out of {@link
 * WorkflowService}: {@code requireAccess} (resource sharing), the free-tier edit lock and the
 * unattended-automation plan gate. Behaviour is unchanged from the original methods.
 */
@Service
class WorkflowAccessGuard {

    private final WorkflowDefinitionRepository definitionRepository;
    private final TenantLimits tenantLimits;
    // Cross-user resource sharing retrofit (Round 2) - see requireAccess's javadoc.
    private final ResourceAccessService resourceAccessService;

    WorkflowAccessGuard(
            WorkflowDefinitionRepository definitionRepository,
            TenantLimits tenantLimits,
            ResourceAccessService resourceAccessService) {
        this.definitionRepository = definitionRepository;
        this.tenantLimits = tenantLimits;
        this.resourceAccessService = resourceAccessService;
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition get(UUID id) {
        return definitionRepository
                .findById(id)
                .orElseThrow(() -> new WorkflowNotFoundException(id));
    }

    /**
     * Loads {@code id} (404s via {@link #get} if missing/cross-tenant) and then enforces {@code
     * core/sharing}'s access control at {@code required} level using the entity's own {@link
     * WorkflowDefinition#getCreatedBy()}. Deliberately NOT folded into {@link #get}: the run /
     * schedule / webhook / channel trigger paths have no tenant-member identity to check, so only
     * the human-only config methods call this.
     *
     * <p><b>KNOWN GAP - {@code createdBy == null} means "open to every tenant member", at EVERY
     * permission level</b> (security audit 2026-07-29; unresolved by design). {@code
     * ResourceAccessService#requireAccess} short-circuits to LEGACY_OPEN before it looks at the
     * caller or {@code required}, and V151 did not backfill existing rows, so every workflow created
     * before the sharing retrofit is effectively editable by any tenant member. The endpoint-level
     * {@code ROLE_tenant-admin} matchers in {@code SecurityConfig} are the real control for the
     * secret-bearing endpoints (webhook, schedule, trigger-channel); do not "simplify" one away in
     * favour of the other. Closing the gap is a data decision (backfill created_by to the tenant's
     * first admin, or make legacy rows admin-only) touching the shared Flyway namespace.
     */
    public WorkflowDefinition requireAccess(UUID id, PermissionLevel required) {
        WorkflowDefinition definition = get(id);
        resourceAccessService.requireAccess(
                ResourceType.WORKFLOW, id, definition.getCreatedBy(), required);
        return definition;
    }

    /** VIEWER-level funnel for the version/history endpoints. */
    public WorkflowDefinition requireViewer(UUID id) {
        return requireAccess(id, PermissionLevel.VIEWER);
    }

    /** EDITOR-level funnel for the publish/promote/rollback endpoints. */
    public WorkflowDefinition requireEditor(UUID id) {
        return requireAccess(id, PermissionLevel.EDITOR);
    }

    /**
     * Same reuse-not-duplicate reasoning as {@code AgentService#assertEditable} - the free-tier
     * grace-window edit lock.
     */
    public void assertEditable(WorkflowDefinition definition) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return;
        }
        TenantPlan plan = tenantLimits.plan(tenantId);
        if (EditLockPolicy.isLocked(plan, definition.getCreatedAt())) {
            throw new EditLockedException(
                    "Editing is a Premium feature - this workflow keeps running as-is (run, view,"
                            + " and delete are still available), or upgrade your plan to change it."
                            + " Free-tier workflows can be edited for "
                            + EditLockPolicy.FREE_TIER_GRACE_WINDOW.toHours()
                            + " hours after creation, or duplicated into "
                            + "a fresh editable copy at any time.",
                    "free_tier_grace_window_expired",
                    definition.getCreatedAt() == null
                            ? null
                            : definition
                                    .getCreatedAt()
                                    .plus(EditLockPolicy.FREE_TIER_GRACE_WINDOW));
        }
    }

    public boolean isEditable(WorkflowDefinition definition) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return true;
        }
        return !EditLockPolicy.isLocked(tenantLimits.plan(tenantId), definition.getCreatedAt());
    }

    /**
     * The caller's effective share level, for the response's {@code accessLevel} display hint.
     * Probes {@link #requireAccess}'s own rules from the top down so the answer can never disagree
     * with what the endpoints enforce.
     */
    public String accessLevelOf(WorkflowDefinition definition) {
        if (canAccess(definition, PermissionLevel.EDITOR)) {
            return "EDITOR";
        }
        // Levels are ordered RUNNER < VIEWER < EDITOR and a higher level satisfies a lower check, so
        // probe VIEWER before RUNNER; a VIEWER grantee passes the RUNNER probe too.
        return canAccess(definition, PermissionLevel.VIEWER) ? "VIEWER" : "RUNNER";
    }

    private boolean canAccess(WorkflowDefinition definition, PermissionLevel required) {
        try {
            resourceAccessService.requireAccess(
                    ResourceType.WORKFLOW, definition.getId(), definition.getCreatedBy(), required);
            return true;
        } catch (com.agenticai.core.sharing.ResourceAccessDeniedException e) {
            return false;
        }
    }

    /**
     * Schedules, webhooks and inbound-channel triggers make a workflow run with nobody present,
     * which the plan matrix reserves for Premium and above ({@code automation_allowed}, V470).
     * Applies only when there is a tenant context.
     */
    public void requireUnattendedAutomation() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.automationAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Scheduled, webhook and channel-triggered workflows run unattended and need the"
                            + " Premium plan or higher - upgrade to turn this on.");
        }
    }
}
