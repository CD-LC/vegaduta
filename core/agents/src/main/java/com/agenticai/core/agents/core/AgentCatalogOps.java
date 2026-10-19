package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceGrant;
import com.agenticai.core.sharing.ResourceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The per-caller agent list and hard delete behind {@link AgentService#listAll} and {@link
 * AgentService#delete}. Not a Spring bean: {@link AgentService} builds it, and the transaction
 * boundaries stay on its methods.
 */
final class AgentCatalogOps {

    private final AgentRepository repository;
    private final AgentAccessService access;
    private final AgentResponseAssembler assembler;

    AgentCatalogOps(
            AgentRepository repository,
            AgentAccessService access,
            AgentResponseAssembler assembler) {
        this.repository = repository;
        this.access = access;
        this.assembler = assembler;
    }

    /**
     * Cross-user resource sharing retrofit (Round 2) - list-level filter, not a per-item {@code
     * requireAccess} throw/catch: a caller should simply never see a private agent they have no
     * access to IN the list (matching the anti-enumeration posture everywhere else in this
     * feature), rather than getting a full list back and 404ing on some entries. Included: every
     * legacy agent ({@code createdBy == null}), every agent this caller owns, every agent for a
     * tenant-admin/platform-admin caller, and every agent this caller has ANY active grant on
     * (RUNNER included) - matches the design doc's literal "createdBy IS NULL OR createdBy ==
     * currentUser OR (has an active grant)" rule, not narrowed to VIEWER+ grants only, despite
     * {@link AgentResponse#from} including full config; refining that further (redacting fields for
     * a RUNNER-only grantee's list row) isn't something this pass builds - simple in-memory filter
     * after one list query plus one {@code listSharedWithMe} query, not N per-item DB grant
     * lookups, which is more than adequate at this codebase's actual scale (a tenant's agent count,
     * capped in the tens/low hundreds by {@code TenantLimits#maxAgents}).
     */
    List<AgentResponse> listAll() {
        List<Agent> all = repository.findAll();
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        // Granular RBAC (2026-09-23): "sees every agent" is the
        // MANAGE_AGENTS scope now, not the blanket tenant-admin role.
        boolean admin =
                TenantContext.current()
                        .map(
                                ctx ->
                                        ctx.hasRole("PERM_MANAGE_AGENTS")
                                                || ctx.hasRole("ROLE_platform-admin"))
                        .orElse(false);
        Set<UUID> grantedAgentIds =
                subject == null
                        ? Set.of()
                        : access.listSharedWithMe().stream()
                                .filter(grant -> grant.getResourceType() == ResourceType.AGENT)
                                .map(ResourceGrant::getResourceId)
                                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        return all.stream()
                // A/B experiments (V265): a hidden treatment clone is
                // implementation detail for AgentChatService's traffic routing,
                // never a customer-visible agent in its own right - excluded
                // regardless of ownership/admin/grant, unlike every other filter
                // branch below.
                .filter(agent -> agent.getExperimentCloneOf() == null)
                .filter(
                        agent ->
                                agent.getCreatedBy() == null
                                        || agent.getCreatedBy().equals(subject)
                                        || admin
                                        || grantedAgentIds.contains(agent.getId()))
                .map(assembler::toResponse)
                .toList();
    }

    /**
     * Tenant-scoped lookup with no per-user ownership check - the public, DTO-returning sibling of
     * {@code getEntity} for callers outside this package, same "check-free sibling" reasoning
     * {@link #requireExists} documents just below for its own boolean-only need.
     *
     * <p>2026-08-15 fix, same failure class as {@code requireExists}'s own documented incident:
     * {@code core/mcpserver}'s {@code McpListAgentsToolCallback#resolveExposedAgents} (shared with
     * {@code DeveloperApiController}'s {@code GET /api/dev/v1/agents}) called {@code get}, whose
     * {@code requireAccess} per-user ownership check requires a real {@code createdBy}-matching
     * subject, an admin role, or an explicit {@code ResourceGrant} - none of which the MCP
     * synthetic subject ({@code "mcp-external:<grantId>"}, set by {@code McpApiKeyAuthFilter}) ever
     * has. Every exposed agent id 403'd, and {@code resolveExposedAgents}' catch-and-omit posture
     * (deliberately built to survive a genuinely deleted agent) silently turned that into an empty
     * list with no error surfaced to the caller - confirmed live: a grant with 2 valid,
     * correctly-configured exposed agent ids returned {@code list_my_agents: []} while the same
     * grant's {@code list_my_workflows} (which never went through a per-user check) worked
     * correctly. A tenant-admin already explicitly authorized exposing this specific agent id to
     * this specific grant when they configured {@code McpServerAccessGrant#exposedAgentIds} - that
     * membership check IS the authorization decision for this call path, so an additional per-user
     * ownership check on top of it is wrong here, not missing.
     */
    AgentResponse grantedAgent(UUID agentId) {
        return assembler.toResponseWithoutAccessLevel(access.getEntity(agentId));
    }

    /**
     * Tenant-scoped existence check ONLY - deliberately no per-user {@code requireAccess} gate.
     * For a structural-validation caller ({@code
     * com.agenticai.core.workflow.OrchestratorService#validate}) that only needs "does this agentId
     * still resolve to a real agent", never the agent's data itself.
     *
     * <p>Direct feedback (2026-08-04): "even after enabling trigger for inbound whatsapp message
     * it's not saving, not triggering." Root cause - {@code WorkflowExecutionEngine#execute} runs
     * {@code OrchestratorService#validate} as defense-in-depth on EVERY run, not just save/activate
     * (see that call site's own comment), and {@code validate}'s Agent/Tool/Supervisor node checks
     * called the PUBLIC, per-user-access-checked {@code get} - which requires a real,
     * grant-holding {@code TenantContext} subject. A channel-triggered run (WhatsApp/SMS/Voice/etc.
     * - see {@code WorkflowTriggerPortImpl}'s "synthetic per-channel identity") or a
     * scheduled/webhook-triggered one has no such subject, so {@code requireAccess} always fell
     * through to {@code ResourceAccessDeniedException} ("Resource not found.") for any agent it
     * didn't personally own/get granted - i.e. essentially every real deployment - aborting the run
     * before a single node executed, with nothing user-visible beyond a server-side WARN log.
     * {@code AgentChatService#chat} already had the correct fix for this exact class of problem
     * (uses the unchecked {@code getEntity}, see its own javadoc) - {@code getEntity} just isn't
     * reachable from {@code OrchestratorService}'s different package, hence this public,
     * intentionally check-free sibling instead of exposing {@code getEntity} itself.
     */
    void requireExists(UUID agentId) {
        if (!repository.existsById(agentId)) {
            throw new AgentNotFoundException(agentId);
        }
    }

    /**
     * Unlimited-plan capability ("should be able to...delete also") - hard delete. Deliberately
     * does NOT call {@code assertEditable}: the existing edit-lock error message itself already
     * promises "chat, run, and delete are still available" for a locked FREE-tier agent, so delete
     * has to stay reachable regardless of lock state or that promise is false. Every agent-owned
     * child table (allowed_tool, allowed_knowledge_collection, agent_memory, agent_collaborator)
     * cascades at the DB level (see V12/V16/V18/V56 migrations); {@code channel_session}/{@code
     * channel_default_agent} deliberately do NOT - an agent still bound to a live channel shouldn't
     * silently vanish out from under it. {@code repository}.flush() forces that FK check to happen
     * inside this try block (JPA would otherwise defer the DELETE statement to end-of-transaction
     * flush, past the catch).
     */
    void delete(UUID agentId) {
        // EDITOR: deletion is the most privileged mutation on this resource -
        // same posture as every other config-changing method above, even
        // though (unlike them) this one deliberately isn't also gated by
        // assertEditable/the FREE-tier edit-lock - see this method's own
        // javadoc for why delete stays reachable regardless of lock state.
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        // WB-001 fix: this method deliberately skips assertEditable (see javadoc above -
        // delete must stay reachable regardless of the time-based edit-lock state), but it
        // had no FREE-plan check at all, unlike updateModel/updateVoiceModel/updateVideoModel
        // - a FREE tenant could delete an agent via a direct API call despite the UI
        // paywalling the Delete button. This is a separate axis from the edit-lock above.
        TenantPlan plan = access.currentPlan();
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Deleting agents is a Premium feature - upgrade your plan to delete this"
                            + " agent.");
        }
        try {
            repository.delete(agent);
            repository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new AgentInUseException(
                    "This agent can't be deleted while it's still connected to a channel (WhatsApp,"
                            + " Telegram, etc.) or set as a channel's default agent. Disconnect or"
                            + " reassign it first, then try again.");
        }
    }
}
