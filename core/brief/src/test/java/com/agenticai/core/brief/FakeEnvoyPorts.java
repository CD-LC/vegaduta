package com.agenticai.core.brief;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A recording stand-in for the real services. Everything it "creates" lives in a map, so a test can assert
 * that nothing is left behind after a rolled-back apply; {@link #failOn} makes one named operation throw and
 * {@link #failDelete} makes one kind of delete throw (a compensation that cannot complete).
 */
class FakeEnvoyPorts implements EnvoyPorts {

    final List<String> calls = new CopyOnWriteArrayList<>();
    final Map<String, String> knowledge = new LinkedHashMap<>();
    final Map<String, String> procedures = new LinkedHashMap<>();
    final Map<String, String> tools = new LinkedHashMap<>();
    final Map<String, String> mcp = new LinkedHashMap<>();
    final Map<String, Brief.AgentSpec> agents = new LinkedHashMap<>();
    final Map<String, AgentAutonomyTier> tiers = new LinkedHashMap<>();
    final Map<String, AgentWiring> wirings = new LinkedHashMap<>();
    final Map<String, WorkflowGraphDto> graphs = new LinkedHashMap<>();
    final Set<String> activeWorkflows = new LinkedHashSet<>();
    final Map<String, String> datasets = new LinkedHashMap<>();
    final Map<String, String> gates = new LinkedHashMap<>();
    final Set<String> channels = new LinkedHashSet<>();
    final Set<String> approvalRequired = new LinkedHashSet<>();
    final Set<String> failOn = new HashSet<>();
    final Set<String> failDelete = new HashSet<>();
    volatile boolean canIntegrate = true;
    volatile boolean canTools = true;
    volatile boolean canChannels = true;
    volatile boolean evalAllowed = true;
    volatile String tenantSeenOnCreateKnowledge;
    private final AtomicInteger counter = new AtomicInteger();

    private String id(String prefix) {
        return prefix + "-" + counter.incrementAndGet();
    }

    private void op(String name) {
        calls.add(name);
        if (failOn.contains(name)) {
            throw new IllegalStateException("boom in " + name);
        }
    }

    private void del(String name) {
        calls.add(name);
        if (failDelete.contains(name)) {
            throw new IllegalStateException("cannot " + name);
        }
    }

    /** Entities still alive (knowledge, procedures, tools, MCP, agents, workflows, datasets, channels). */
    int liveEntities() {
        return knowledge.size() + procedures.size() + tools.size() + mcp.size() + agents.size() + graphs.size()
            + datasets.size() + channels.size();
    }

    long count(String call) {
        return calls.stream().filter(call::equals).count();
    }

    @Override
    public boolean canManageIntegrations() {
        return canIntegrate;
    }

    @Override
    public boolean canManageTools() {
        return canTools;
    }

    @Override
    public boolean canManageChannels() {
        return canChannels;
    }

    @Override
    public boolean planAllowsEval() {
        return evalAllowed;
    }

    @Override
    public String createKnowledge(String name, String description) {
        op("createKnowledge");
        tenantSeenOnCreateKnowledge = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String id = id("k");
        knowledge.put(id, name);
        return id;
    }

    @Override
    public void updateKnowledge(String id, String name, String description) {
        op("updateKnowledge");
        knowledge.put(id, name);
    }

    @Override
    public void deleteKnowledge(String id) {
        del("deleteKnowledge");
        knowledge.remove(id);
    }

    @Override
    public String createProcedure(String name, List<String> steps) {
        op("createProcedure");
        String id = id("p");
        procedures.put(id, name);
        return id;
    }

    @Override
    public void updateProcedure(String id, String name, List<String> steps) {
        op("updateProcedure");
        procedures.put(id, name);
    }

    @Override
    public void deleteProcedure(String id) {
        del("deleteProcedure");
        procedures.remove(id);
    }

    @Override
    public ToolResult createTool(Brief.ToolSpec spec, String secretName, String nameSuffix) {
        op("createTool");
        String id = id("t");
        tools.put(id, spec.name());
        return new ToolResult("TOOL_OPENAPI", id);
    }

    @Override
    public void refreshTool(String bindingKind, String id) {
        op("refreshTool");
    }

    @Override
    public void deleteTool(String bindingKind, String id) {
        del("deleteTool");
        tools.remove(id);
    }

    @Override
    public List<String> toolNames(String bindingKind, String id) {
        return List.of("tool_" + id);
    }

    @Override
    public String connectMcp(String serverName, Brief.McpServerSpec spec, Map<String, String> envSecretNames) {
        op("connectMcp");
        String id = id("m");
        mcp.put(id, serverName);
        return id;
    }

    @Override
    public void deleteMcp(String id) {
        del("deleteMcp");
        mcp.remove(id);
    }

    @Override
    public List<String> discoverMcpTools(String agentId, String serverName) {
        return mcp.containsValue(serverName) ? List.of("mcp__" + serverName + "__search") : List.of();
    }

    @Override
    public String createAgent(Brief.AgentSpec spec, AgentAutonomyTier tier) {
        op("createAgent");
        String id = id("a");
        agents.put(id, spec);
        tiers.put(id, tier);
        return id;
    }

    @Override
    public void updateAgent(String id, Brief.AgentSpec spec, AgentAutonomyTier tier) {
        op("updateAgent");
        agents.put(id, spec);
        tiers.put(id, tier);
    }

    @Override
    public List<String> wireAgent(String id, AgentWiring wiring) {
        op("wireAgent");
        wirings.put(id, wiring);
        return new ArrayList<>();
    }

    @Override
    public void deleteAgent(String id) {
        del("deleteAgent");
        agents.remove(id);
        tiers.remove(id);
        wirings.remove(id);
    }

    @Override
    public AgentAutonomyTier agentTier(String id) {
        return tiers.get(id);
    }

    @Override
    public void setAgentTier(String id, AgentAutonomyTier tier) {
        op("setAgentTier");
        tiers.put(id, tier);
    }

    @Override
    public String createWorkflow(String name, String description, WorkflowGraphDto graph) {
        op("createWorkflow");
        String id = id("w");
        graphs.put(id, graph);
        return id;
    }

    @Override
    public void updateWorkflow(String id, String name, String description, WorkflowGraphDto graph) {
        op("updateWorkflow");
        graphs.put(id, graph);
    }

    @Override
    public void updateWorkflowGraph(String id, WorkflowGraphDto graph) {
        op("updateWorkflowGraph");
        graphs.put(id, graph);
    }

    @Override
    public WorkflowGraphDto workflowGraph(String id) {
        return graphs.get(id);
    }

    @Override
    public boolean workflowActive(String id) {
        return activeWorkflows.contains(id);
    }

    @Override
    public void activateWorkflow(String id) {
        op("activateWorkflow");
        activeWorkflows.add(id);
    }

    @Override
    public void deleteWorkflow(String id) {
        del("deleteWorkflow");
        graphs.remove(id);
        activeWorkflows.remove(id);
    }

    @Override
    public void enableSchedule(String id, String cron) {
        op("enableSchedule");
    }

    @Override
    public void disableSchedule(String id) {
        op("disableSchedule");
    }

    @Override
    public void enableWebhook(String id, String nodeId) {
        op("enableWebhook");
    }

    @Override
    public void bindWorkflowChannel(String id, String nodeId, String channelKind) {
        op("bindWorkflowChannel");
    }

    @Override
    public ChannelBind bindChannelToAgent(String channelKind, String agentId) {
        op("bindChannel");
        channels.add(channelKind);
        return ChannelBind.ROUTED;
    }

    @Override
    public boolean unbindChannel(String channelKind, String expectedAgentId) {
        del("unbindChannel");
        return channels.remove(channelKind);
    }

    @Override
    public String createEvalDataset(String agentId, String name, double passThreshold, List<Brief.EvalCase> cases) {
        op("createEvalDataset");
        String id = id("d");
        datasets.put(id, agentId);
        return id;
    }

    @Override
    public void updateEvalDataset(String datasetId, String name, double passThreshold, List<Brief.EvalCase> cases) {
        op("updateEvalDataset");
    }

    @Override
    public void deleteEvalDataset(String datasetId) {
        del("deleteEvalDataset");
        datasets.remove(datasetId);
    }

    @Override
    public void armGate(String agentId, String datasetId, double minScore, Integer maxRunAgeHours) {
        op("armGate");
        gates.put(agentId, datasetId + "@" + minScore);
    }

    @Override
    public void disarmGate(String agentId) {
        op("disarmGate");
        gates.remove(agentId);
    }

    @Override
    public boolean approvalRequired(String actionType) {
        return approvalRequired.contains(actionType);
    }

    @Override
    public void setApprovalRequired(String actionType, boolean required) {
        op("setApprovalRequired:" + required);
        if (required) {
            approvalRequired.add(actionType);
        } else {
            approvalRequired.remove(actionType);
        }
    }
}
