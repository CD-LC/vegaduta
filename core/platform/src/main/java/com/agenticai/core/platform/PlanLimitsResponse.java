package com.agenticai.core.platform;

import com.agenticai.core.tenancy.PlanLimitsService.ResolvedPlanLimits;

/**
 * Wire shape for the plan_limits platform-admin CRUD editor (A2-PR1 task 4,
 * roadmap §8) - a thin projection of {@link ResolvedPlanLimits}, same
 * "never expose a JPA entity/internal record straight out of a controller"
 * posture {@link PlatformSettingResponse} already follows. Field names
 * match {@code web-admin}'s already-built consumer exactly (its {@code
 * lib/api.ts}/{@code plans/page.tsx} were wired against this best-guess
 * contract ahead of this endpoint existing - see that file's own comment)
 * so no frontend change is needed alongside this.
 */
public record PlanLimitsResponse(
    String plan,
    int maxUsers,
    int maxAgents,
    int maxWorkflows,
    int maxKbCollections,
    boolean byokAllowed,
    boolean whatsappAllowed,
    boolean whiteLabel,
    boolean liveVisionAllowed,
    boolean externalChannelsAllowed,
    boolean developerApiAllowed,
    boolean ssoAllowed,
    boolean scimAllowed,
    boolean workflowExportAllowed,
    boolean automationAllowed
) {
    public static PlanLimitsResponse from(ResolvedPlanLimits limits) {
        return new PlanLimitsResponse(
            limits.plan().name(),
            limits.maxUsers(),
            limits.maxAgents(),
            limits.maxWorkflows(),
            limits.maxKbCollections(),
            limits.byokAllowed(),
            limits.whatsappAllowed(),
            limits.whiteLabel(),
            limits.liveVisionAllowed(),
            limits.externalChannelsAllowed(),
            limits.developerApiAllowed(),
            limits.ssoAllowed(),
            limits.scimAllowed(),
            limits.workflowExportAllowed(),
            limits.automationAllowed()
        );
    }
}
