package com.agenticai.core.brief;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the apply engine and the trust ladder do to the platform, as one narrow port.
 * {@link ServiceEnvoyPorts} implements it by calling the REAL services (AgentService, WorkflowService,
 * KnowledgeCollectionService, ...), never repositories, so plan caps, access checks and entity
 * versioning all still apply. The seam exists so the engine's saga logic (ordering, ref resolution,
 * compensation, degrade) can be tested with a fake that records what it was asked to create and
 * delete and can be told to fail at a chosen step.
 *
 * <p>All ids cross this port as strings (they are stored as such in {@code brief_binding}).
 * A method that cannot do its job for a reason that is not a failure (a feature not enabled on this
 * deployment, a credential the vault does not hold) throws {@link ApplySkipException}; the engine
 * records the step as skipped and carries on. Any other exception fails the apply.
 */
public interface EnvoyPorts {

    // ------------------------------------------------------------------ environment

    /** True when the caller may create tools / MCP servers (PERM_MANAGE_INTEGRATIONS or platform admin). */
    boolean canManageIntegrations();

    /** True when the caller may author or remove tools (PERM_MANAGE_TOOLS or platform admin): code tools run generated code. */
    boolean canManageTools();

    /** True when the caller may route or unroute inbound channels (PERM_MANAGE_CHANNELS or platform admin). */
    boolean canManageChannels();

    /** False on a plan without eval runs (the quality gate and proposals then never arm). */
    boolean planAllowsEval();

    // ------------------------------------------------------------------ knowledge

    String createKnowledge(String name, String description);

    void updateKnowledge(String id, String name, String description);

    void deleteKnowledge(String id);

    // ------------------------------------------------------------------ procedures

    /** Creates the procedure and activates it. */
    String createProcedure(String name, List<String> steps);

    void updateProcedure(String id, String name, List<String> steps);

    void deleteProcedure(String id);

    // ------------------------------------------------------------------ tools

    /**
     * @param secretName vault name of the tool's credential (never its value), or null
     * @param nameSuffix short stable text to keep generated tool names unique per brief
     */
    ToolResult createTool(Brief.ToolSpec spec, String secretName, String nameSuffix);

    /** Re-fetches an OPENAPI source's spec; a no-op for CODE tools. */
    void refreshTool(String bindingKind, String id);

    void deleteTool(String bindingKind, String id);

    /** The exact tool names an agent allow-list must hold to use this tool. */
    List<String> toolNames(String bindingKind, String id);

    // ------------------------------------------------------------------ MCP

    /**
     * Registers and connects a public-registry / catalog MCP server.
     *
     * @param serverName the registration name (brief-scoped, [a-z0-9-])
     * @param envSecretNames server env var name to the vault secret name that supplies it
     */
    String connectMcp(String serverName, Brief.McpServerSpec spec, Map<String, String> envSecretNames);

    void deleteMcp(String id);

    /** Tool names the agent can see from {@code serverName} (waits briefly for a fresh connection); empty if none appeared. */
    List<String> discoverMcpTools(String agentId, String serverName);

    // ------------------------------------------------------------------ agents

    /** Creates the agent and applies its autonomy tier before anything else can fail. */
    String createAgent(Brief.AgentSpec spec, AgentAutonomyTier tier);

    void updateAgent(String id, Brief.AgentSpec spec, AgentAutonomyTier tier);

    /** Allow-list, knowledge scope, procedure links, guardrails. Returns human-readable warnings (plan-gated guardrails, ...). */
    List<String> wireAgent(String id, AgentWiring wiring);

    void deleteAgent(String id);

    AgentAutonomyTier agentTier(String id);

    void setAgentTier(String id, AgentAutonomyTier tier);

    // ------------------------------------------------------------------ workflows

    /** Creates the workflow as a DRAFT. */
    String createWorkflow(String name, String description, WorkflowGraphDto graph);

    /** Replaces name/description/graph and keeps the workflow's current status. */
    void updateWorkflow(String id, String name, String description, WorkflowGraphDto graph);

    /** Replaces only the graph (name, description and status untouched) - what a rung change does. */
    void updateWorkflowGraph(String id, WorkflowGraphDto graph);

    WorkflowGraphDto workflowGraph(String id);

    boolean workflowActive(String id);

    void activateWorkflow(String id);

    void deleteWorkflow(String id);

    void enableSchedule(String id, String cron);

    void disableSchedule(String id);

    void enableWebhook(String id, String nodeId);

    /** Binds an inbound channel (WHATSAPP, TELEGRAM, ...) to the workflow's trigger node. */
    void bindWorkflowChannel(String id, String nodeId, String channelKind);

    // ------------------------------------------------------------------ channels

    ChannelBind bindChannelToAgent(String channelKind, String agentId);

    /**
     * Clears the channel's default-agent routing, but only while it still routes to {@code expectedAgentId}: an admin
     * who re-pointed the channel elsewhere keeps their routing. A null {@code expectedAgentId} clears nothing.
     *
     * @return true when a routing was actually removed
     */
    boolean unbindChannel(String channelKind, String expectedAgentId);

    // ------------------------------------------------------------------ eval + gate

    String createEvalDataset(String agentId, String name, double passThreshold, List<Brief.EvalCase> cases);

    void updateEvalDataset(String datasetId, String name, double passThreshold, List<Brief.EvalCase> cases);

    void deleteEvalDataset(String datasetId);

    /** Turns the quality gate on for the agent against the dataset, with {@code minScore} as the pass threshold. */
    void armGate(String agentId, String datasetId, double minScore, Integer maxRunAgeHours);

    void disarmGate(String agentId);

    /** The agent's gate as it is now (null when none), so a failed change can put it back exactly. */
    default GateState gateState(String agentId) {
        return null;
    }

    /** Puts the gate back as {@link #gateState} captured it: none when {@code prior} is null. */
    default void restoreGate(String agentId, GateState prior) {
        if (prior == null) {
            disarmGate(agentId);
        } else {
            armGate(agentId, prior.datasetId(), prior.passThreshold(), prior.maxRunAgeHours());
        }
    }

    // ------------------------------------------------------------------ approval policy

    boolean approvalRequired(String actionType);

    void setApprovalRequired(String actionType, boolean required);

    // ------------------------------------------------------------------ types

    /** A quality gate's settings: the dataset it runs, that dataset's pass threshold, and the freshness window. */
    record GateState(String datasetId, double passThreshold, Integer maxRunAgeHours) {
    }

    /**
     * @param bindingKind TOOL_OPENAPI or TOOL_CODE (stored as the binding's kind so later steps know how to read names)
     */
    record ToolResult(String bindingKind, String id) {
    }

    /**
     * @param toolNames the exact allow-list; never empty (see {@link BriefApplyService#NO_TOOLS}) because an empty
     *     allow-list means "unrestricted" to the platform
     * @param knowledgeIds collection ids the agent may search
     * @param procedureIds procedure ids to attach, in order
     * @param detachProcedureIds procedures this brief attached earlier and no longer wants
     * @param guardrailKinds brief guardrail kinds (PII, PROFANITY, ...)
     */
    record AgentWiring(
        Set<String> toolNames,
        List<String> knowledgeIds,
        List<String> procedureIds,
        List<String> detachProcedureIds,
        List<String> guardrailKinds
    ) {
    }

    enum ChannelBind {
        /** This call created the routing (and compensation may remove it). */
        ROUTED,
        /** Already routed to this very agent - nothing was changed. */
        ALREADY,
        /** Routed to another agent; a brief never takes a channel over. */
        TAKEN,
        /** No routing exists for this kind (WEB, SMS, ...). */
        UNSUPPORTED
    }

    /** "Not a failure, just not doable here" - see the class comment. */
    class ApplySkipException extends RuntimeException {
        public ApplySkipException(String reason) {
            super(reason);
        }
    }
}
