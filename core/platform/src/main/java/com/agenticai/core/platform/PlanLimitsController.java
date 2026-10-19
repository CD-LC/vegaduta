package com.agenticai.core.platform;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlanLimitsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin "Plan Limits" editor - {@code /api/platform/plan-limits[/{plan}]}
 * (A2-PR1 task 4, roadmap §8; contract confirmed in
 * {@code docs/AGENT-COORDINATION-2026-07-18.md} §12). Gated by the same
 * {@code SecurityConfig} {@code .requestMatchers("/api/platform/**")
 * .hasAuthority("ROLE_platform-admin")} rule as every other controller under
 * this path prefix - see {@link PlatformController}'s own javadoc for the
 * same posture - no separate authorization check inside this class itself.
 *
 * <p>Kept as its own small controller rather than a new method on {@link
 * PlatformController} - that class is long-lived and under active
 * concurrent edits by other work in this repo (see that class's own
 * javadoc); a self-contained feature with its own single-purpose service
 * ({@link PlanLimitsService}) has nothing to gain from living in that file
 * and a merge-conflict surface to lose - same reasoning {@link
 * NewsletterController}'s javadoc already documents for itself.
 *
 * <p>No {@code @Profile("security")} here - unlike {@link PlatformController},
 * {@link PlanLimitsService} depends only on JPA + Redis, not {@code
 * KeycloakAdminClient}, so this controller wires cleanly with or without the
 * security profile active (in practice still unreachable without it, since
 * {@code /api/platform/**} itself is gated at that layer).
 *
 * <p>Web-admin's {@code plans/page.tsx} sends its full draft row (a copy of
 * the last {@code GET} row with edited fields applied) as the {@code PUT}
 * body - that row also carries a {@code plan} field the update DTO doesn't
 * declare, which Spring Boot's default Jackson config (unknown properties
 * not configured to fail) simply ignores.
 */
@RestController
@RequestMapping("/api/platform/plan-limits")
public class PlanLimitsController {

    private final PlanLimitsService planLimitsService;

    public PlanLimitsController(PlanLimitsService planLimitsService) {
        this.planLimitsService = planLimitsService;
    }

    /** All 3 plans' current caps in one call - the editor's page-load read. */
    @GetMapping
    public List<PlanLimitsResponse> listPlanLimits() {
        return planLimitsService.getAllPlanLimits().stream().map(PlanLimitsResponse::from).toList();
    }

    /** Replaces one plan's caps wholesale; {@code plan} comes from the path, same convention {@code /settings/{key}} uses. */
    @PutMapping("/{plan}")
    public PlanLimitsResponse updatePlanLimits(@PathVariable TenantPlan plan, @RequestBody PlanLimitsUpdateRequest request) {
        // Every boxed Boolean is nullable (see the request record's own
        // comment): an omitted value keeps the row's current setting rather
        // than coercing to false. One getLimitsForPlan read resolves any of
        // them that are missing.
        if (request.liveVisionAllowed() == null
            || request.externalChannelsAllowed() == null
            || request.developerApiAllowed() == null
            || request.ssoAllowed() == null
            || request.scimAllowed() == null
            || request.workflowExportAllowed() == null
            || request.automationAllowed() == null) {
            var current = planLimitsService.getLimitsForPlan(plan);
            return PlanLimitsResponse.from(planLimitsService.update(
                plan,
                request.maxUsers(),
                request.maxAgents(),
                request.maxWorkflows(),
                request.maxKbCollections(),
                request.byokAllowed(),
                request.whatsappAllowed(),
                request.whiteLabel(),
                request.liveVisionAllowed() != null ? request.liveVisionAllowed() : current.liveVisionAllowed(),
                request.externalChannelsAllowed() != null ? request.externalChannelsAllowed() : current.externalChannelsAllowed(),
                request.developerApiAllowed() != null ? request.developerApiAllowed() : current.developerApiAllowed(),
                request.ssoAllowed() != null ? request.ssoAllowed() : current.ssoAllowed(),
                request.scimAllowed() != null ? request.scimAllowed() : current.scimAllowed(),
                request.workflowExportAllowed() != null ? request.workflowExportAllowed() : current.workflowExportAllowed(),
                request.automationAllowed() != null ? request.automationAllowed() : current.automationAllowed()
            ));
        }
        return PlanLimitsResponse.from(planLimitsService.update(
            plan,
            request.maxUsers(),
            request.maxAgents(),
            request.maxWorkflows(),
            request.maxKbCollections(),
            request.byokAllowed(),
            request.whatsappAllowed(),
            request.whiteLabel(),
            request.liveVisionAllowed(),
            request.externalChannelsAllowed(),
            request.developerApiAllowed(),
            request.ssoAllowed(),
            request.scimAllowed(),
            request.workflowExportAllowed(),
            request.automationAllowed()
        ));
    }
}
