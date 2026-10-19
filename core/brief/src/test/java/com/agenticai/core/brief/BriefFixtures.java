package com.agenticai.core.brief;

import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.brief.model.Brief.AgentSpec;
import com.agenticai.core.brief.model.Brief.EvalCase;
import com.agenticai.core.brief.model.Brief.KnowledgeSource;
import com.agenticai.core.brief.model.Brief.KnowledgeSpec;
import com.agenticai.core.brief.model.Brief.NodeNarrative;
import com.agenticai.core.brief.model.Brief.SecretSpec;
import com.agenticai.core.brief.model.Brief.Trigger;
import com.agenticai.core.brief.model.Brief.WorkflowSpec;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A valid brief plus a tiny mutable builder so each test breaks exactly one thing. */
final class BriefFixtures {

    private BriefFixtures() {
    }

    static final class B {
        String title = "Invoice chaser";
        String goal = "Chase overdue invoices politely and log the outcome.";
        String locale = "en";
        Brief.Trust trust = new Brief.Trust("UNDER_WATCH", List.of("w1.n3"), List.of());
        List<AgentSpec> agents = new ArrayList<>(List.of(agent("a1", List.of("k1"))));
        List<WorkflowSpec> workflows = new ArrayList<>(List.of(workflow("w1", "a1", "k1")));
        List<KnowledgeSpec> knowledge = new ArrayList<>(List.of(
            new KnowledgeSpec("k1", "Payment terms", List.of(new KnowledgeSource("UPLOAD_LATER", null, null, "Upload your terms.")))));
        List<SecretSpec> secrets = new ArrayList<>(List.of(
            new SecretSpec("s1", "TALLY_API_KEY", "TENANT", "Read invoices", "Tally > Settings > API")));
        List<Brief.McpServerSpec> mcp = new ArrayList<>();
        List<Brief.ChannelSpec> channels = new ArrayList<>();
        List<Brief.ToolSpec> tools = new ArrayList<>();
        Brief.EvalSet evalSet = new Brief.EvalSet("a1", cases(4), new Brief.Gate(0.7));
        Map<String, NodeNarrative> perNode = narrative("a1", "w1", "k1", "s1", "w1.n3");
        Brief.Narrative narrative = new Brief.Narrative("Because the reply depends on context.", perNode);

        Brief build() {
            return new Brief(1, title, goal, locale, List.of(), trust, agents, List.of(), workflows, tools, mcp,
                knowledge, secrets, channels, evalSet, null, narrative);
        }
    }

    static B valid() {
        return new B();
    }

    static AgentSpec agent(String ref, List<String> knowledgeRefs) {
        return new AgentSpec(ref, "Collector", "Chases invoices", "You chase overdue invoices politely.", "CAREFUL",
            "SIMPLE", null, List.of(), knowledgeRefs, List.of(), List.of(), null, false);
    }

    static WorkflowSpec workflow(String ref, String agentRef, String knowledgeRef) {
        return new WorkflowSpec(ref, "Chase", graph(agentRef, knowledgeRef), List.of(new Trigger("MANUAL", null, null)), "Invoice 42 is 30 days late");
    }

    /** trigger n1 -> agent n2 -> output n3 (log), optionally a knowledge node between. */
    static WorkflowGraphDto graph(String agentRef, String knowledgeRef) {
        WorkflowNodeDto trigger = node("n1", "trigger", Map.of("channel", "WEBHOOK"));
        WorkflowNodeDto agent = node("n2", "agent", Map.of("agentId", agentRef));
        WorkflowNodeDto output = node("n3", "output", Map.of("channel", "log"));
        return new WorkflowGraphDto(List.of(trigger, agent, output), List.of(
            new WorkflowEdgeDto("e1", "n1", "n2", null, null),
            new WorkflowEdgeDto("e2", "n2", "n3", null, null)));
    }

    static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", id);
        data.put("config", new LinkedHashMap<>(config));
        return new WorkflowNodeDto(id, type, Map.of("x", 0, "y", 0), data);
    }

    static List<EvalCase> cases(int n) {
        List<EvalCase> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new EvalCase("Invoice " + i + " is late", null, "Polite, states the amount and the due date."));
        }
        return out;
    }

    static Map<String, NodeNarrative> narrative(String... keys) {
        Map<String, NodeNarrative> m = new LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, new NodeNarrative("why " + k, "tune " + k, "proof " + k));
        }
        return m;
    }
}
