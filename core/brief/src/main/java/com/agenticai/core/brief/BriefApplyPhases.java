package com.agenticai.core.brief;

import static com.agenticai.core.brief.BriefApplyService.EVAL_REF;
import static com.agenticai.core.brief.BriefApplyService.NOTE;
import static com.agenticai.core.brief.BriefApplyService.NO_TOOLS;
import static com.agenticai.core.brief.BriefApplyService.mcpServerName;

import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.EnvoyPorts.AgentWiring;
import com.agenticai.core.brief.EnvoyPorts.ApplySkipException;
import com.agenticai.core.brief.EnvoyPorts.ChannelBind;
import com.agenticai.core.brief.EnvoyPorts.ToolResult;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The phases of an apply: one method per kind of artifact (knowledge, procedures, tools, MCP
 * connectors, agents, workflows, approval policy, triggers, channels, eval set, quality gate). The
 * prune of what the brief no longer declares is {@link BriefApplyPrune}. Split out of {@link
 * BriefApplyService}, which keeps the entry points, the job lifecycle and the compensation walk.
 * Stateless apart from its collaborators; per-apply state is in {@link ApplyCtx}.
 */
final class BriefApplyPhases {

    private static final Logger log = LoggerFactory.getLogger(BriefApplyPhases.class);

    private static final double DEFAULT_GATE_MIN_SCORE = 0.7;
    private static final int GATE_MAX_RUN_AGE_HOURS = 24 * 7;

    private final EnvoyPorts ports;
    private final BriefReadinessService readinessService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ProofSource> proofSource;
    private final ApplyStepRunner runner;

    BriefApplyPhases(
            EnvoyPorts ports,
            BriefApplyLedger ledger,
            BriefReadinessService readinessService,
            ObjectMapper objectMapper,
            ObjectProvider<ProofSource> proofSource) {
        this.ports = ports;
        this.readinessService = readinessService;
        this.objectMapper = objectMapper;
        this.proofSource = proofSource;
        this.runner = new ApplyStepRunner(ledger);
    }

    void knowledge(ApplyCtx c) {
        for (Brief.KnowledgeSpec k : c.brief.knowledge()) {
            Binding bound = c.bindings.get(k.ref());
            Brief.KnowledgeSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.knowledge(),
                            Brief.KnowledgeSpec::ref,
                            k.ref());
            if (bound == null) {
                runner.step(
                        c,
                        "KNOWLEDGE",
                        k.ref(),
                        h -> {
                            String id = ports.createKnowledge(k.name(), knowledgeDescription(k));
                            h.created("KNOWLEDGE", id, () -> ports.deleteKnowledge(id));
                            return null;
                        });
            } else if (changed(old, k)) {
                runner.step(
                        c,
                        "KNOWLEDGE_UPDATE",
                        k.ref(),
                        h -> {
                            if (old != null) {
                                h.willUpdate(
                                        () ->
                                                ports.updateKnowledge(
                                                        bound.entityId(),
                                                        old.name(),
                                                        knowledgeDescription(old)));
                            }
                            ports.updateKnowledge(
                                    bound.entityId(), k.name(), knowledgeDescription(k));
                            return null;
                        });
            }
        }
    }

    void procedures(ApplyCtx c) {
        for (Brief.ProcedureSpec p : c.brief.procedures()) {
            Binding bound = c.bindings.get(p.ref());
            Brief.ProcedureSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.procedures(),
                            Brief.ProcedureSpec::ref,
                            p.ref());
            if (bound == null) {
                runner.step(
                        c,
                        "PROCEDURE",
                        p.ref(),
                        h -> {
                            String id = ports.createProcedure(p.name(), p.steps());
                            h.created("PROCEDURE", id, () -> ports.deleteProcedure(id));
                            return null;
                        });
            } else if (changed(old, p)) {
                runner.step(
                        c,
                        "PROCEDURE_UPDATE",
                        p.ref(),
                        h -> {
                            if (old != null) {
                                h.willUpdate(
                                        () ->
                                                ports.updateProcedure(
                                                        bound.entityId(), old.name(), old.steps()));
                            }
                            ports.updateProcedure(bound.entityId(), p.name(), p.steps());
                            return null;
                        });
            }
        }
    }

    void tools(ApplyCtx c) {
        String suffix = c.briefId.toString().substring(0, 8);
        for (Brief.ToolSpec t : c.brief.tools()) {
            Binding bound = c.bindings.get(t.ref());
            Brief.ToolSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.tools(),
                            Brief.ToolSpec::ref,
                            t.ref());
            if (bound != null) {
                if (changed(old, t)) {
                    runner.step(
                            c,
                            "TOOL_UPDATE",
                            t.ref(),
                            h -> {
                                String skip = toolSkip(c, t.ref());
                                if (skip != null) {
                                    throw new ApplySkipException(skip);
                                }
                                ports.refreshTool(bound.kind(), bound.entityId());
                                return NOTE
                                        + "Only the API description was refreshed; change name or credentials in Tools.";
                            });
                }
                continue;
            }
            runner.step(
                    c,
                    "TOOL",
                    t.ref(),
                    h -> {
                        String skip = toolSkip(c, t.ref());
                        if (skip != null) {
                            throw new ApplySkipException(skip);
                        }
                        ToolResult made =
                                ports.createTool(
                                        t,
                                        secretName(
                                                c.brief,
                                                t.auth() == null ? null : t.auth().secretRef()),
                                        suffix);
                        h.created(
                                made.bindingKind(),
                                made.id(),
                                () -> ports.deleteTool(made.bindingKind(), made.id()));
                        return null;
                    });
        }
    }

    void mcp(ApplyCtx c) {
        for (Brief.McpServerSpec m : c.brief.mcpServers()) {
            Binding bound = c.bindings.get(m.ref());
            Brief.McpServerSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.mcpServers(),
                            Brief.McpServerSpec::ref,
                            m.ref());
            if (bound != null && !changed(old, m)) {
                continue;
            }
            String serverName = mcpServerName(c.briefId, m);
            runner.step(
                    c,
                    bound == null ? "MCP" : "MCP_UPDATE",
                    m.ref(),
                    h -> {
                        String skip = integrationSkip(c, m.ref());
                        if (skip != null) {
                            throw new ApplySkipException(skip);
                        }
                        Map<String, String> env = new LinkedHashMap<>();
                        m.env()
                                .forEach(
                                        (name, binding) -> {
                                            String secret =
                                                    secretName(c.brief, binding.secretRef());
                                            if (secret != null) {
                                                env.put(name, secret);
                                            }
                                        });
                        if (bound != null && old != null) {
                            Map<String, String> oldEnv = new LinkedHashMap<>();
                            old.env()
                                    .forEach(
                                            (name, binding) -> {
                                                String secret =
                                                        secretName(c.previous, binding.secretRef());
                                                if (secret != null) {
                                                    oldEnv.put(name, secret);
                                                }
                                            });
                            h.willUpdate(() -> ports.connectMcp(serverName, old, oldEnv));
                        }
                        // Registering under the same name updates the registration in place.
                        String id = ports.connectMcp(serverName, m, env);
                        if (bound == null) {
                            h.created("MCP", id, () -> ports.deleteMcp(id));
                        }
                        return null;
                    });
        }
    }

    void agents(ApplyCtx c) {
        String tier = c.rung.name();
        for (Brief.AgentSpec a : c.brief.agents()) {
            Binding bound = c.bindings.get(a.ref());
            Brief.AgentSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.agents(),
                            Brief.AgentSpec::ref,
                            a.ref());
            if (bound == null) {
                runner.step(
                        c,
                        "AGENT",
                        a.ref(),
                        h -> {
                            String id = ports.createAgent(a, c.rung.tier());
                            h.created("AGENT", id, () -> ports.deleteAgent(id));
                            return wire(c, a, id, null);
                        });
            } else if (changed(old, a)) {
                runner.step(
                        c,
                        "AGENT_UPDATE",
                        a.ref(),
                        h -> {
                            if (old != null) {
                                var previousTier = ports.agentTier(bound.entityId());
                                h.willUpdate(
                                        () -> {
                                            ports.updateAgent(bound.entityId(), old, previousTier);
                                            wire(c, old, bound.entityId(), null);
                                        });
                            }
                            ports.updateAgent(bound.entityId(), a, c.rung.tier());
                            return wire(c, a, bound.entityId(), old);
                        });
            } else {
                runner.step(c, "AGENT_WIRE", a.ref(), h -> wire(c, a, bound.entityId(), old));
            }
        }
        log.debug("Brief {} agents applied at rung {}", c.briefId, tier);
    }

    /**
     * Resolves refs to real ids and applies the agent's allow-list, scopes, procedures, guardrails.
     * Returns a step note.
     */
    private String wire(
            ApplyCtx c, Brief.AgentSpec a, String agentId, Brief.AgentSpec previousSpec) {
        List<String> notes = new ArrayList<>();
        Set<String> tools = new LinkedHashSet<>();
        for (String entry : a.toolAllowList()) {
            if (entry.startsWith("mcp:")) {
                String[] parts = entry.substring(4).split("/", 2);
                String mRef = parts[0];
                String toolPart = parts.length > 1 ? parts[1] : "*";
                Brief.McpServerSpec m = find(c.brief.mcpServers(), Brief.McpServerSpec::ref, mRef);
                if (m == null || !c.bindings.containsKey(mRef)) {
                    notes.add(a.ref() + " cannot use " + mRef + " yet: " + reasonFor(c, mRef));
                    continue;
                }
                String serverName = mcpServerName(c.briefId, m);
                List<String> found =
                        c.mcpTools.computeIfAbsent(
                                serverName, s -> ports.discoverMcpTools(agentId, s));
                List<String> matched =
                        "*".equals(toolPart)
                                ? found
                                : found.stream()
                                        .filter(
                                                n ->
                                                        n.equals(toolPart)
                                                                || n.endsWith("__" + toolPart))
                                        .toList();
                if (matched.isEmpty()) {
                    notes.add(
                            mRef
                                    + "'s tools were not visible yet, so "
                                    + a.ref()
                                    + " does not have them; apply again once it is connected.");
                }
                tools.addAll(matched);
            } else if (BriefRefs.isRef(entry)) {
                Binding b = c.bindings.get(entry);
                if (b == null) {
                    notes.add(a.ref() + " cannot use " + entry + " yet: " + reasonFor(c, entry));
                    continue;
                }
                tools.addAll(ports.toolNames(b.kind(), b.entityId()));
            } else {
                tools.add(entry);
            }
        }
        if (tools.isEmpty()) {
            tools.add(NO_TOOLS);
        }
        List<String> knowledge = new ArrayList<>();
        for (String ref : a.knowledgeRefs()) {
            Binding b = c.bindings.get(ref);
            if (b == null) {
                notes.add(a.ref() + " cannot search " + ref + " yet: " + reasonFor(c, ref));
            } else {
                knowledge.add(b.entityId());
            }
        }
        List<String> procedures = new ArrayList<>();
        for (String ref : a.procedureRefs()) {
            Binding b = c.bindings.get(ref);
            if (b == null) {
                notes.add(a.ref() + " cannot follow " + ref + " yet: " + reasonFor(c, ref));
            } else {
                procedures.add(b.entityId());
            }
        }
        List<String> detach = new ArrayList<>();
        if (previousSpec != null) {
            for (String ref : previousSpec.procedureRefs()) {
                Binding b = c.bindings.get(ref);
                if (b != null && !a.procedureRefs().contains(ref)) {
                    detach.add(b.entityId());
                }
            }
        }
        List<String> kinds = a.guardrails().stream().map(Brief.Guardrail::kind).toList();
        notes.addAll(
                ports.wireAgent(
                        agentId, new AgentWiring(tools, knowledge, procedures, detach, kinds)));
        return notes.isEmpty() ? null : NOTE + String.join(" ", notes);
    }

    void workflows(ApplyCtx c) {
        for (Brief.WorkflowSpec w : c.brief.workflows()) {
            Binding bound = c.bindings.get(w.ref());
            Brief.WorkflowSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.workflows(),
                            Brief.WorkflowSpec::ref,
                            w.ref());
            boolean graphChanged =
                    bound != null
                            && (old == null
                                    || !Objects.equals(old.graph(), w.graph())
                                    || !Objects.equals(old.name(), w.name()));
            if (bound != null && !graphChanged) {
                c.workflowGraphs.put(w.ref(), safeMaterialised(c, w));
                continue;
            }
            runner.step(
                    c,
                    bound == null ? "WORKFLOW" : "WORKFLOW_UPDATE",
                    w.ref(),
                    h -> {
                        WorkflowGraphDto graph = materialised(c, w);
                        c.workflowGraphs.put(w.ref(), graph);
                        String description = "Part of the solution '" + c.brief.title() + "'.";
                        if (bound == null) {
                            String id = ports.createWorkflow(w.name(), description, graph);
                            h.created("WORKFLOW", id, () -> ports.deleteWorkflow(id));
                        } else {
                            WorkflowGraphDto before = ports.workflowGraph(bound.entityId());
                            String beforeName = old == null ? w.name() : old.name();
                            h.willUpdate(
                                    () ->
                                            ports.updateWorkflow(
                                                    bound.entityId(),
                                                    beforeName,
                                                    description,
                                                    before));
                            ports.updateWorkflow(bound.entityId(), w.name(), description, graph);
                        }
                        return null;
                    });
        }
    }

    void trustPolicy(ApplyCtx c) {
        int approvals =
                c.workflowGraphs.values().stream()
                        .mapToInt(TrustGraphMaterializer::approvalCount)
                        .sum();
        if (approvals == 0) {
            return;
        }
        runner.step(
                c,
                "TRUST_POLICY",
                null,
                h -> {
                    String action = TrustGraphMaterializer.APPROVAL_ACTION_TYPE;
                    if (ports.approvalRequired(action)) {
                        return NOTE + "Approval was already required for workflow approval steps.";
                    }
                    // Tighten only, and only undo what this step tightened. The policy is
                    // tenant-wide: it is what makes an
                    // approval step actually pause a run, for every workflow in the workspace.
                    h.willUpdate(() -> ports.setApprovalRequired(action, false));
                    ports.setApprovalRequired(action, true);
                    return NOTE
                            + approvals
                            + " approval step(s) now pause runs for a person (workspace-wide policy).";
                });
    }

    void activateAndTriggers(ApplyCtx c) {
        for (Brief.WorkflowSpec w : c.brief.workflows()) {
            Binding bound = c.bindings.get(w.ref());
            if (bound == null) {
                continue;
            }
            boolean created = c.createdRefs.contains(w.ref());
            Brief.WorkflowSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.workflows(),
                            Brief.WorkflowSpec::ref,
                            w.ref());
            if (created) {
                runner.step(
                        c,
                        "WORKFLOW_ACTIVATE",
                        w.ref(),
                        h -> {
                            ports.activateWorkflow(bound.entityId());
                            return null;
                        });
            }
            List<Brief.Trigger> oldTriggers = old == null || created ? List.of() : old.triggers();
            for (Brief.Trigger t : w.triggers()) {
                if (oldTriggers.contains(t)
                        || (!"SCHEDULE".equals(t.kind()) && !"WEBHOOK".equals(t.kind()))) {
                    continue;
                }
                runner.step(
                        c,
                        "TRIGGER",
                        w.ref(),
                        h -> {
                            if (!ports.workflowActive(bound.entityId())) {
                                throw new ApplySkipException(
                                        "The workflow is not active, so its "
                                                + t.kind().toLowerCase(Locale.ROOT)
                                                + " trigger was not enabled.");
                            }
                            if ("SCHEDULE".equals(t.kind())) {
                                Brief.Trigger before =
                                        oldTriggers.stream()
                                                .filter(o -> "SCHEDULE".equals(o.kind()))
                                                .findFirst()
                                                .orElse(null);
                                h.willUpdate(
                                        () -> {
                                            if (before != null) {
                                                ports.enableSchedule(
                                                        bound.entityId(), before.cron());
                                            } else {
                                                ports.disableSchedule(bound.entityId());
                                            }
                                        });
                                ports.enableSchedule(bound.entityId(), t.cron());
                                return null;
                            }
                            String nodeId = triggerNodeId(w.graph());
                            if (nodeId == null) {
                                throw new ApplySkipException(
                                        "The workflow has no trigger node to attach a webhook to.");
                            }
                            ports.enableWebhook(bound.entityId(), nodeId);
                            return NOTE + "Webhook enabled; open the workflow to copy its URL.";
                        });
            }
            boolean hadSchedule = oldTriggers.stream().anyMatch(t -> "SCHEDULE".equals(t.kind()));
            boolean hasSchedule = w.triggers().stream().anyMatch(t -> "SCHEDULE".equals(t.kind()));
            if (hadSchedule && !hasSchedule) {
                runner.step(
                        c,
                        "TRIGGER_REMOVE",
                        w.ref(),
                        h -> {
                            oldTriggers.stream()
                                    .filter(o -> "SCHEDULE".equals(o.kind()))
                                    .findFirst()
                                    .ifPresent(
                                            o ->
                                                    h.willUpdate(
                                                            () ->
                                                                    ports.enableSchedule(
                                                                            bound.entityId(),
                                                                            o.cron())));
                            ports.disableSchedule(bound.entityId());
                            return null;
                        });
            }
        }
    }

    void channels(ApplyCtx c) {
        for (Brief.ChannelSpec ch : c.brief.channels()) {
            Binding bound = c.bindings.get(ch.ref());
            Brief.ChannelSpec old =
                    find(
                            c.previous == null ? List.of() : c.previous.channels(),
                            Brief.ChannelSpec::ref,
                            ch.ref());
            if (bound != null && !changed(old, ch)) {
                continue;
            }
            runner.step(
                    c,
                    bound == null ? "CHANNEL" : "CHANNEL_UPDATE",
                    ch.ref(),
                    h -> {
                        if ("WEB".equals(ch.kind())) {
                            return NOTE + "Web chat needs no channel setup.";
                        }
                        if (!c.canChannels) {
                            throw new ApplySkipException(
                                    "Needs the Manage channels permission - an admin can apply this part.");
                        }
                        Binding target = c.bindings.get(ch.bindTo());
                        if (target == null) {
                            throw new ApplySkipException(
                                    ch.bindTo()
                                            + " was not created, so "
                                            + ch.kind()
                                            + " was not connected to it.");
                        }
                        if (BriefRefs.isRef(ch.bindTo(), BriefRefs.WORKFLOW)) {
                            Brief.WorkflowSpec w =
                                    find(c.brief.workflows(), Brief.WorkflowSpec::ref, ch.bindTo());
                            String nodeId = w == null ? null : triggerNodeId(w.graph());
                            if (nodeId == null) {
                                throw new ApplySkipException(
                                        "The workflow has no trigger node to receive "
                                                + ch.kind()
                                                + ".");
                            }
                            if (!ports.workflowActive(target.entityId())) {
                                throw new ApplySkipException(
                                        "The workflow is not active, so "
                                                + ch.kind()
                                                + " was not connected to it.");
                            }
                            ports.bindWorkflowChannel(target.entityId(), nodeId, ch.kind());
                            return null;
                        }
                        if (bound != null
                                && old != null
                                && !"WEB".equals(old.kind())
                                && bound.kind().equals("CHANNEL")) {
                            // Only the routing this brief itself made is removed, and it is put
                            // back if the apply fails later.
                            String oldKind = bound.entityId();
                            Binding oldTarget =
                                    old.bindTo() == null ? null : c.bindings.get(old.bindTo());
                            if (oldTarget != null
                                    && ports.unbindChannel(oldKind, oldTarget.entityId())) {
                                h.willUpdate(
                                        () ->
                                                ports.bindChannelToAgent(
                                                        oldKind, oldTarget.entityId()));
                            }
                        }
                        ChannelBind result = ports.bindChannelToAgent(ch.kind(), target.entityId());
                        return switch (result) {
                            case ROUTED -> {
                                h.created(
                                        "CHANNEL",
                                        ch.kind(),
                                        () -> ports.unbindChannel(ch.kind(), target.entityId()));
                                yield null;
                            }
                            case ALREADY -> NOTE + ch.kind() + " already routes to this agent.";
                            case TAKEN ->
                                    throw new ApplySkipException(
                                            ch.kind()
                                                    + " is already routed to another agent; a brief never "
                                                    + "takes a channel over. Change the routing first.");
                            case UNSUPPORTED ->
                                    throw new ApplySkipException(
                                            ch.kind()
                                                    + " routing cannot be set up from a brief yet.");
                        };
                    });
        }
    }

    void eval(ApplyCtx c) {
        Brief.EvalSet e = c.brief.evalSet();
        if (e == null) {
            return;
        }
        Binding bound = c.bindings.get(EVAL_REF);
        Brief.EvalSet old = c.previous == null ? null : c.previous.evalSet();
        if (bound != null && !changed(old, e)) {
            return;
        }
        String name = "Brief: " + c.brief.title();
        double threshold =
                e.gate() != null && e.gate().minScore() != null
                        ? e.gate().minScore()
                        : DEFAULT_GATE_MIN_SCORE;
        runner.step(
                c,
                bound == null ? "EVAL_DATASET" : "EVAL_DATASET_UPDATE",
                EVAL_REF,
                h -> {
                    if (!ports.planAllowsEval()) {
                        throw new ApplySkipException(
                                "Eval sets are not available on your plan, so this brief stays under watch with no quality gate.");
                    }
                    Binding agent =
                            e.targetAgent() == null ? null : c.bindings.get(e.targetAgent());
                    if (agent == null) {
                        throw new ApplySkipException(
                                "The eval set's target agent was not created.");
                    }
                    if (bound == null) {
                        String id =
                                ports.createEvalDataset(
                                        agent.entityId(), name, threshold, e.cases());
                        h.created("EVAL_DATASET", id, () -> ports.deleteEvalDataset(id));
                    } else {
                        if (old != null) {
                            double oldThreshold =
                                    old.gate() != null && old.gate().minScore() != null
                                            ? old.gate().minScore()
                                            : DEFAULT_GATE_MIN_SCORE;
                            h.willUpdate(
                                    () ->
                                            ports.updateEvalDataset(
                                                    bound.entityId(),
                                                    name,
                                                    oldThreshold,
                                                    old.cases()));
                        }
                        ports.updateEvalDataset(bound.entityId(), name, threshold, e.cases());
                    }
                    return null;
                });
    }

    void trustGate(ApplyCtx c) {
        Brief.EvalSet e = c.brief.evalSet();
        if (e == null || c.rung == TrustRung.UNDER_WATCH) {
            return;
        }
        Binding dataset = c.bindings.get(EVAL_REF);
        Binding agent = e.targetAgent() == null ? null : c.bindings.get(e.targetAgent());
        if (dataset == null || agent == null) {
            return;
        }
        double minScore = TrustLadderService.gateMinScore(c.brief, snapshotQuietly(c.briefId));
        runner.step(
                c,
                "TRUST_GATE",
                EVAL_REF,
                h -> {
                    var prior = ports.gateState(agent.entityId());
                    h.willUpdate(() -> ports.restoreGate(agent.entityId(), prior));
                    ports.armGate(
                            agent.entityId(), dataset.entityId(), minScore, GATE_MAX_RUN_AGE_HOURS);
                    return NOTE
                            + "Quality gate armed at "
                            + String.format(Locale.ROOT, "%.2f", minScore)
                            + ".";
                });
    }

    // ================================================================== resolution + helpers

    private WorkflowGraphDto materialised(ApplyCtx c, Brief.WorkflowSpec w) {
        return TrustGraphMaterializer.materialise(
                resolveGraph(c, w), w.ref(), c.rung, c.brief.trust());
    }

    /**
     * As {@link #materialised}, for an unchanged workflow whose refs are all bound already; null on
     * any problem.
     */
    private WorkflowGraphDto safeMaterialised(ApplyCtx c, Brief.WorkflowSpec w) {
        try {
            return materialised(c, w);
        } catch (RuntimeException e) {
            return new WorkflowGraphDto(List.of(), List.of());
        }
    }

    /**
     * Copies the graph with every agent / collection / target-workflow ref in node config replaced
     * by the real id.
     */
    WorkflowGraphDto resolveGraph(ApplyCtx c, Brief.WorkflowSpec w) {
        return resolveGraph(w.graph(), w.ref(), c.bindings);
    }

    WorkflowGraphDto resolveGraph(
            WorkflowGraphDto graph, String workflowRef, Map<String, Binding> bindings) {
        try {
            JsonNode tree = objectMapper.valueToTree(graph);
            for (JsonNode node : tree.path("nodes")) {
                JsonNode config = node.path("data").path("config");
                if (!(config instanceof ObjectNode cfg)) {
                    continue;
                }
                for (String key : List.of("agentId", "collectionId", "targetWorkflowId")) {
                    JsonNode value = cfg.get(key);
                    if (value == null || !value.isTextual() || !BriefRefs.isRef(value.asText())) {
                        continue;
                    }
                    Binding b = bindings.get(value.asText());
                    if (b == null) {
                        throw new IllegalStateException(
                                "Workflow "
                                        + workflowRef
                                        + " node '"
                                        + node.path("id").asText()
                                        + "' needs "
                                        + value.asText()
                                        + ", which was not created.");
                    }
                    cfg.put(key, b.entityId());
                }
            }
            return objectMapper.treeToValue(tree, WorkflowGraphDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Workflow "
                            + workflowRef
                            + " graph could not be resolved: "
                            + e.getOriginalMessage(),
                    e);
        }
    }

    /**
     * Tools are also gated on Manage tools over HTTP: a code tool is generated code offered to
     * every agent.
     */
    private String toolSkip(ApplyCtx c, String ref) {
        if (!c.canTools) {
            return "Needs the Manage tools permission - an admin can apply this part.";
        }
        return integrationSkip(c, ref);
    }

    private String integrationSkip(ApplyCtx c, String ref) {
        if (!c.canIntegrate) {
            return "Needs the Manage integrations permission - an admin can apply this part.";
        }
        if (c.integrationRows == null) {
            c.integrationRows = new LinkedHashMap<>();
            try {
                Map<String, String> bound = new LinkedHashMap<>();
                c.bindings.forEach((r, b) -> bound.put(r, b.entityId()));
                for (ReadinessRow row : readinessService.compute(c.brief, bound)) {
                    if ("CONNECTOR".equals(row.kind()) && row.ref() != null) {
                        c.integrationRows.put(row.ref(), row);
                    }
                }
            } catch (RuntimeException e) {
                // Readiness is advisory here; the real service call below is what actually decides.
                log.warn(
                        "Brief {}: readiness could not be computed for the apply pre-check",
                        c.briefId,
                        e);
            }
        }
        ReadinessRow row = c.integrationRows.get(ref);
        return row != null && !ReadinessRow.READY.equals(row.state()) ? row.reason() : null;
    }

    private String reasonFor(ApplyCtx c, String ref) {
        return c.skipped.getOrDefault(ref, "it was not created.");
    }

    private ProofSource.ProofSnapshot snapshotQuietly(UUID briefId) {
        ProofSource source = proofSource.getIfAvailable();
        if (source == null) {
            return null;
        }
        try {
            return source.snapshot(briefId, Instant.now());
        } catch (RuntimeException e) {
            log.warn("Brief {}: proof snapshot unavailable while arming the gate", briefId, e);
            return null;
        }
    }

    private static String triggerNodeId(WorkflowGraphDto graph) {
        return graph == null
                ? null
                : graph.nodes().stream()
                        .filter(n -> "trigger".equalsIgnoreCase(n.type()))
                        .map(WorkflowNodeDto::id)
                        .findFirst()
                        .orElse(null);
    }

    private static String secretName(Brief brief, String secretRef) {
        if (secretRef == null) {
            return null;
        }
        return brief.secrets().stream()
                .filter(s -> secretRef.equals(s.ref()))
                .map(Brief.SecretSpec::name)
                .findFirst()
                .orElse(null);
    }

    private static String knowledgeDescription(Brief.KnowledgeSpec k) {
        return k.sources().stream()
                .map(Brief.KnowledgeSource::hint)
                .filter(h -> h != null && !h.isBlank())
                .findFirst()
                .orElse(null);
    }

    static <T> T find(List<T> list, Function<T, String> ref, String wanted) {
        return list.stream()
                .filter(x -> wanted != null && wanted.equals(ref.apply(x)))
                .findFirst()
                .orElse(null);
    }

    /** True when there is no earlier manifest to compare against or the spec differs from it. */
    private static boolean changed(Object oldSpec, Object newSpec) {
        return oldSpec == null || !oldSpec.equals(newSpec);
    }
}
