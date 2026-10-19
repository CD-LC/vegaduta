package com.agenticai.core.templates;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the new agent or workflow an import produces, through {@code AgentService.create} /
 * {@code WorkflowService.create} (never a raw insert), then applies the agent design that {@code
 * AgentCreateRequest} cannot carry. Split out of {@link CommunityTemplateService#importTemplate},
 * which keeps the access check, rate limit, import count and audit.
 */
final class TemplateInstantiator {

    private static final Logger log = LoggerFactory.getLogger(CommunityTemplateService.class);

    private final TemplateSnapshotService snapshotService;
    private final AgentService agentService;
    private final AgentGuardrailService agentGuardrailService;
    private final WorkflowService workflowService;

    TemplateInstantiator(
            TemplateSnapshotService snapshotService,
            AgentService agentService,
            AgentGuardrailService agentGuardrailService,
            WorkflowService workflowService) {
        this.snapshotService = snapshotService;
        this.agentService = agentService;
        this.agentGuardrailService = agentGuardrailService;
        this.workflowService = workflowService;
    }

    /** @return the id of the agent or workflow created from {@code template}. */
    UUID instantiate(CommunityTemplate template) {
        return switch (template.getKind()) {
            case AGENT -> {
                AgentResponse created =
                        agentService.create(
                                snapshotService.toAgentCreateRequest(
                                        template.getConfigJson(), template.getTitle()));
                applyPostCreateDesign(created.id(), template);
                yield created.id();
            }
            case WORKFLOW -> {
                WorkflowGraphDto graph =
                        snapshotService.toWorkflowGraph(template.getConfigJson());
                TemplateSnapshotService.WorkflowSnapshot snap =
                        snapshotService.readWorkflowSnapshot(template.getConfigJson());
                WorkflowDefinition created =
                        workflowService.create(
                                template.getTitle(), snap.description(), graph);
                yield created.getId();
            }
        };
    }

    /**
     * Applies the parts of an agent template that {@code AgentCreateRequest} cannot express: the
     * tool allow-list and the guardrail set.
     *
     * <p>Before this existed, a template could declare {@code "allowedToolNames": ["google_gmail",
     * "google_calendar"]} and the imported agent would arrive with an empty allow-list - which,
     * because an empty allow-list means "no restriction" (see {@code Agent.allowedToolNames}),
     * silently produced an agent with <i>broader</i> access than the template asked for, not
     * narrower. That is the wrong direction to fail in, and it is why every seeded template until
     * now declared at most {@code search_knowledge_base}.
     *
     * <p>Both steps go through the same public services the UI calls, so plan gating, access checks
     * and agent versioning all still apply.
     *
     * <p><b>Best-effort by design.</b> A guardrail the importing tenant's plan does not include, or
     * a tool that does not exist in this deployment, must not destroy an otherwise-successful
     * import - the agent is already created at this point, and throwing would leave it orphaned and
     * half-configured. The failure is logged and the import continues.
     */
    private void applyPostCreateDesign(UUID agentId, CommunityTemplate template) {
        TemplateSnapshotService.AgentSnapshot snapshot;
        try {
            snapshot = snapshotService.readAgentSnapshot(template.getConfigJson());
        } catch (RuntimeException e) {
            log.warn(
                    "Template {} has an unreadable agent snapshot - imported with defaults",
                    template.getId(),
                    e);
            return;
        }

        // Autonomy FIRST, deliberately. Everything below is best-effort and may
        // fail (a plan-gated guardrail, a tool that does not exist here); the
        // safety posture must already be on the agent when that happens, not
        // queued behind something that can throw.
        //
        // 2026-09-12 fix: this was missing entirely, and it was a live safety
        // bug rather than a cosmetic gap. AgentCreateRequest has no
        // autonomyTier field BY DESIGN (see TemplateSnapshotService
        // #toAgentCreateRequest's "not back-doored in" javadoc), and nothing
        // re-applied it afterwards - so every template advertising SUPERVISED
        // ("every send waits for your approval", "requires human approval
        // before tool calls execute") imported as the SEMI_AUTONOMOUS default
        // and did NOT wait. Seven platform templates made that promise,
        // including 'Financial / High-Risk Agent' and the whole V223
        // real-world-action set that sends mail and books meetings.
        String tierName = snapshot.autonomyTier();
        if (tierName != null && !tierName.isBlank()) {
            try {
                agentService.setAutonomyTier(agentId, AgentAutonomyTier.valueOf(tierName));
            } catch (IllegalArgumentException unknownTier) {
                // Snapshot from a different version of this enum - same
                // degrade-rather-than-fail posture as parseEnumNullable.
                log.warn(
                        "Template {} declares autonomyTier '{}', which is not a value of"
                                + " AgentAutonomyTier - agent {} keeps the default tier",
                        template.getId(),
                        tierName,
                        agentId);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not apply autonomyTier '{}' from template {} to agent {}",
                        tierName,
                        template.getId(),
                        agentId,
                        e);
            }
        }

        List<String> tools = snapshot.allowedToolNames();
        if (tools != null && !tools.isEmpty()) {
            try {
                agentService.setAllowedToolNames(agentId, new LinkedHashSet<>(tools));
            } catch (RuntimeException e) {
                log.warn(
                        "Could not apply the tool allow-list from template {} to agent {}",
                        template.getId(),
                        agentId,
                        e);
            }
        } else if (tools != null) {
            // FAIL-OPEN GUARD (2026-09-12). An EMPTY allowedToolNames on
            // Agent means NO RESTRICTION, not "no tools" - so a template that
            // ships [] hands the imported agent every tool in the deployment,
            // which is the exact opposite of what such a template usually
            // advertises. 'Financial / High-Risk Agent' shipped exactly this
            // and was repaired in data by V226. The skip itself stays correct
            // (an empty list genuinely cannot express "deny all" through this
            // API), but it must never again be SILENT: this is the only signal
            // that a high-risk template is importing unrestricted.
            log.warn(
                    "Template {} declares an EMPTY allowedToolNames - agent {} imports with NO tool"
                        + " restriction, because an empty allow-list means unrestricted. If this"
                        + " template is meant to be locked down, give it an explicit allow-list.",
                    template.getId(),
                    agentId);
        }

        List<String> guardrails = snapshot.enabledGuardrails();
        if (guardrails != null && !guardrails.isEmpty()) {
            try {
                agentGuardrailService.updateAgentGuardrails(
                        agentId, new LinkedHashSet<>(guardrails));
            } catch (RuntimeException e) {
                // Most commonly GuardrailNotAvailableForPlanException on a FREE
                // tenant importing a template designed on a paid plan.
                log.warn(
                        "Could not apply guardrails from template {} to agent {} - {}",
                        template.getId(),
                        agentId,
                        e.getMessage());
            }
        }
    }
}
