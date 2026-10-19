package com.agenticai.core.brief;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.guardrails.procedures.Procedure;
import com.agenticai.core.guardrails.procedures.ProcedureService;
import com.agenticai.core.guardrails.procedures.ProcedureStatus;
import com.agenticai.core.guardrails.procedures.dto.ProcedureRequest;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionCreateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionUpdateRequest;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowScheduleRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link EnvoyPorts} over the real services. Nothing here reads or writes a repository. Secret VALUES
 * are never touched: a tool or MCP credential is passed as a {@code {{secret.NAME}}} reference that
 * the platform resolves at call time, exactly as the console's own forms do. Knowledge, procedures,
 * workflows, channels and the approval policy are served here; agents, tools/MCP and eval/gate are
 * delegated to {@link EnvoyAgentPorts}, {@link EnvoyToolPorts} and {@link EnvoyEvalPorts}.
 */
@Component
public class ServiceEnvoyPorts implements EnvoyPorts {

    private final KnowledgeCollectionService knowledgeService;
    private final ProcedureService procedureService;
    private final WorkflowService workflowService;
    private final ChannelSessionService channelSessionService;
    private final ApprovalPolicyService approvalPolicyService;
    private final TenantLimits tenantLimits;
    private final EnvoyAgentPorts agentPorts;
    private final EnvoyToolPorts toolPorts;
    private final EnvoyEvalPorts evalPorts;

    public ServiceEnvoyPorts(
            KnowledgeCollectionService knowledgeService,
            ProcedureService procedureService,
            WorkflowService workflowService,
            ChannelSessionService channelSessionService,
            ApprovalPolicyService approvalPolicyService,
            TenantLimits tenantLimits,
            EnvoyAgentPorts agentPorts,
            EnvoyToolPorts toolPorts,
            EnvoyEvalPorts evalPorts) {
        this.knowledgeService = knowledgeService;
        this.procedureService = procedureService;
        this.workflowService = workflowService;
        this.channelSessionService = channelSessionService;
        this.approvalPolicyService = approvalPolicyService;
        this.tenantLimits = tenantLimits;
        this.agentPorts = agentPorts;
        this.toolPorts = toolPorts;
        this.evalPorts = evalPorts;
    }

    // ------------------------------------------------------------------ environment

    @Override
    public boolean canManageTools() {
        return TenantContext.current()
                .map(
                        c ->
                                c.hasRole(BriefValidator.PERM_MANAGE_TOOLS)
                                        || c.hasRole("ROLE_platform-admin"))
                .orElse(false);
    }

    @Override
    public boolean canManageChannels() {
        return TenantContext.current()
                .map(
                        c ->
                                c.hasRole(BriefValidator.PERM_MANAGE_CHANNELS)
                                        || c.hasRole("ROLE_platform-admin"))
                .orElse(false);
    }

    @Override
    public boolean canManageIntegrations() {
        return TenantContext.current()
                .map(
                        c ->
                                c.hasRole(BriefValidator.PERM_MANAGE_INTEGRATIONS)
                                        || c.hasRole("ROLE_platform-admin"))
                .orElse(false);
    }

    @Override
    public boolean planAllowsEval() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return tenantId == null || tenantLimits.plan(tenantId) != TenantPlan.FREE;
    }

    // ------------------------------------------------------------------ knowledge

    @Override
    public String createKnowledge(String name, String description) {
        return knowledgeService
                .create(new KnowledgeCollectionCreateRequest(name, description, null, null, null))
                .id()
                .toString();
    }

    @Override
    public void updateKnowledge(String id, String name, String description) {
        knowledgeService.update(
                UUID.fromString(id), new KnowledgeCollectionUpdateRequest(name, description, null));
    }

    @Override
    public void deleteKnowledge(String id) {
        knowledgeService.delete(UUID.fromString(id));
    }

    // ------------------------------------------------------------------ procedures

    @Override
    public String createProcedure(String name, List<String> steps) {
        Procedure created =
                procedureService.create(procedureRequest(name, steps), BriefVersionStore.actor());
        // A new procedure is a DRAFT and never reaches an agent until it is ACTIVE.
        procedureService.setStatus(created.getId(), ProcedureStatus.ACTIVE);
        return created.getId().toString();
    }

    @Override
    public void updateProcedure(String id, String name, List<String> steps) {
        procedureService.update(UUID.fromString(id), procedureRequest(name, steps));
    }

    @Override
    public void deleteProcedure(String id) {
        procedureService.delete(UUID.fromString(id));
    }

    private static ProcedureRequest procedureRequest(String name, List<String> steps) {
        List<ProcedureRequest.RuleInput> rules =
                steps.stream()
                        .map(s -> new ProcedureRequest.RuleInput(s, null, null, null))
                        .toList();
        return new ProcedureRequest(name, null, rules);
    }

    // ------------------------------------------------------------------ tools

    @Override
    public ToolResult createTool(Brief.ToolSpec spec, String secretName, String nameSuffix) {
        return toolPorts.createTool(spec, secretName, nameSuffix);
    }

    @Override
    public void refreshTool(String bindingKind, String id) {
        toolPorts.refreshTool(bindingKind, id);
    }

    @Override
    public void deleteTool(String bindingKind, String id) {
        toolPorts.deleteTool(bindingKind, id);
    }

    @Override
    public List<String> toolNames(String bindingKind, String id) {
        return toolPorts.toolNames(bindingKind, id);
    }

    // ------------------------------------------------------------------ MCP

    @Override
    public String connectMcp(
            String serverName, Brief.McpServerSpec spec, Map<String, String> envSecretNames) {
        return toolPorts.connectMcp(serverName, spec, envSecretNames);
    }

    @Override
    public void deleteMcp(String id) {
        toolPorts.deleteMcp(id);
    }

    @Override
    public List<String> discoverMcpTools(String agentId, String serverName) {
        return toolPorts.discoverMcpTools(agentId, serverName);
    }

    // ------------------------------------------------------------------ agents

    @Override
    public String createAgent(Brief.AgentSpec spec, AgentAutonomyTier tier) {
        return agentPorts.createAgent(spec, tier);
    }

    @Override
    public void updateAgent(String id, Brief.AgentSpec spec, AgentAutonomyTier tier) {
        agentPorts.updateAgent(id, spec, tier);
    }

    @Override
    public List<String> wireAgent(String id, AgentWiring wiring) {
        return agentPorts.wireAgent(id, wiring);
    }

    @Override
    public void deleteAgent(String id) {
        agentPorts.deleteAgent(id);
    }

    @Override
    public AgentAutonomyTier agentTier(String id) {
        return agentPorts.agentTier(id);
    }

    @Override
    public void setAgentTier(String id, AgentAutonomyTier tier) {
        agentPorts.setAgentTier(id, tier);
    }

    // ------------------------------------------------------------------ workflows

    @Override
    public String createWorkflow(String name, String description, WorkflowGraphDto graph) {
        return workflowService.create(name, description, graph).getId().toString();
    }

    @Override
    public void updateWorkflow(String id, String name, String description, WorkflowGraphDto graph) {
        UUID uuid = UUID.fromString(id);
        WorkflowDefinition current = workflowService.get(uuid);
        workflowService.update(uuid, name, description, graph, current.getStatus());
    }

    @Override
    public void updateWorkflowGraph(String id, WorkflowGraphDto graph) {
        UUID uuid = UUID.fromString(id);
        WorkflowDefinition current = workflowService.get(uuid);
        workflowService.update(
                uuid, current.getName(), current.getDescription(), graph, current.getStatus());
    }

    @Override
    public WorkflowGraphDto workflowGraph(String id) {
        return workflowService.graph(UUID.fromString(id));
    }

    @Override
    public boolean workflowActive(String id) {
        return workflowService.get(UUID.fromString(id)).getStatus() == WorkflowStatus.ACTIVE;
    }

    @Override
    public void activateWorkflow(String id) {
        UUID uuid = UUID.fromString(id);
        WorkflowDefinition current = workflowService.get(uuid);
        workflowService.update(
                uuid,
                current.getName(),
                current.getDescription(),
                workflowService.graph(uuid),
                WorkflowStatus.ACTIVE);
    }

    @Override
    public void deleteWorkflow(String id) {
        workflowService.delete(UUID.fromString(id));
    }

    @Override
    public void enableSchedule(String id, String cron) {
        workflowService.enableSchedule(UUID.fromString(id), new WorkflowScheduleRequest(cron));
    }

    @Override
    public void disableSchedule(String id) {
        workflowService.disableSchedule(UUID.fromString(id));
    }

    @Override
    public void enableWebhook(String id, String nodeId) {
        workflowService.enableWebhook(UUID.fromString(id), nodeId);
    }

    @Override
    public void bindWorkflowChannel(String id, String nodeId, String channelKind) {
        WorkflowTriggerChannel channel = triggerChannel(channelKind);
        if (channel == null) {
            throw new ApplySkipException(channelKind + " cannot trigger a workflow from a brief.");
        }
        workflowService.setTriggerChannel(UUID.fromString(id), nodeId, channel, null);
    }

    static WorkflowTriggerChannel triggerChannel(String kind) {
        if (kind == null) {
            return null;
        }
        String name = "VOICE".equals(kind) ? "CALL" : kind;
        try {
            WorkflowTriggerChannel channel = WorkflowTriggerChannel.valueOf(name);
            return channel == WorkflowTriggerChannel.WEBHOOK ? null : channel;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ channels

    @Override
    public ChannelBind bindChannelToAgent(String channelKind, String agentId) {
        Optional<ChannelType> type = BriefReadinessService.channelType(channelKind);
        if (type.isEmpty()) {
            return ChannelBind.UNSUPPORTED;
        }
        if (type.get() == ChannelType.WHATSAPP) {
            // WhatsApp needs a declared task on the binding (docs/channels/whatsapp-policy.md).
            return ChannelBind.UNSUPPORTED;
        }
        Optional<ChannelDefaultAgent> routing = channelSessionService.routingFor(type.get());
        UUID agent = UUID.fromString(agentId);
        if (routing.isPresent() && routing.get().getAgentId() != null) {
            return agent.equals(routing.get().getAgentId())
                    ? ChannelBind.ALREADY
                    : ChannelBind.TAKEN;
        }
        channelSessionService.setRouting(type.get(), agent, false);
        return ChannelBind.ROUTED;
    }

    @Override
    public boolean unbindChannel(String channelKind, String expectedAgentId) {
        Optional<ChannelType> type = BriefReadinessService.channelType(channelKind);
        if (type.isEmpty() || expectedAgentId == null) {
            return false;
        }
        Optional<ChannelDefaultAgent> routing = channelSessionService.routingFor(type.get());
        if (routing.isEmpty()
                || routing.get().getAgentId() == null
                || !UUID.fromString(expectedAgentId).equals(routing.get().getAgentId())) {
            return false;
        }
        channelSessionService.clearRouting(type.get());
        return true;
    }

    // ------------------------------------------------------------------ eval + gate

    @Override
    public String createEvalDataset(
            String agentId, String name, double passThreshold, List<Brief.EvalCase> cases) {
        return evalPorts.createEvalDataset(agentId, name, passThreshold, cases);
    }

    @Override
    public void updateEvalDataset(
            String datasetId, String name, double passThreshold, List<Brief.EvalCase> cases) {
        evalPorts.updateEvalDataset(datasetId, name, passThreshold, cases);
    }

    @Override
    public void deleteEvalDataset(String datasetId) {
        evalPorts.deleteEvalDataset(datasetId);
    }

    @Override
    public void armGate(String agentId, String datasetId, double minScore, Integer maxRunAgeHours) {
        evalPorts.armGate(agentId, datasetId, minScore, maxRunAgeHours);
    }

    @Override
    public GateState gateState(String agentId) {
        return evalPorts.gateState(agentId);
    }

    @Override
    public void disarmGate(String agentId) {
        evalPorts.disarmGate(agentId);
    }

    // ------------------------------------------------------------------ approval policy

    @Override
    public boolean approvalRequired(String actionType) {
        return approvalPolicyService.requiresApproval(actionType);
    }

    @Override
    public void setApprovalRequired(String actionType, boolean required) {
        approvalPolicyService.setOverride(actionType, required);
    }
}
