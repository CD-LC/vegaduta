package com.agenticai.core.brief;

import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.brief.model.Brief.AgentSpec;
import com.agenticai.core.brief.model.Brief.ChannelSpec;
import com.agenticai.core.brief.model.Brief.EvalCase;
import com.agenticai.core.brief.model.Brief.KnowledgeSpec;
import com.agenticai.core.brief.model.Brief.McpServerSpec;
import com.agenticai.core.brief.model.Brief.NodeNarrative;
import com.agenticai.core.brief.model.Brief.Persona;
import com.agenticai.core.brief.model.Brief.ProcedureSpec;
import com.agenticai.core.brief.model.Brief.SecretSpec;
import com.agenticai.core.brief.model.Brief.ToolSpec;
import com.agenticai.core.brief.model.Brief.WorkflowSpec;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.agenticai.core.brief.PlannerText.orElse;
import static com.agenticai.core.brief.PlannerText.strings;
import static com.agenticai.core.brief.PlannerText.text;

/**
 * The planner's closing steps, run once the agents and workflows exist: trust tagging (what cannot be undone),
 * the eval set with rehearsal personas, and the per-item narrative. Split out of {@link BriefPlannerService};
 * every model answer is checked against the refs and node paths the plan really has.
 */
final class PlannerReview {

    private static final Logger log = LoggerFactory.getLogger(PlannerReview.class);

    /** Documented design default for {@code evalSet.gate.minScore}; the trust engine arms the gate from it. */
    static final double DEFAULT_GATE_MIN_SCORE = 0.7;
    private static final int MAX_EVAL_CASES = 12;
    private static final int MAX_PERSONAS = 3;
    private static final Set<String> HTTP_READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> IRREVERSIBLE_OUTPUT_CHANNELS = Set.of("email", "telegram", "whatsapp");

    private final PlannerLlm llm;

    PlannerReview(PlannerLlm llm) {
        this.llm = llm;
    }

    Brief.Trust trustOf(List<WorkflowSpec> workflows, String prompt, List<String> warnings) {
        Set<String> real = new LinkedHashSet<>();
        Set<String> irreversible = new LinkedHashSet<>();
        StringBuilder nodes = new StringBuilder();
        for (WorkflowSpec w : workflows) {
            for (WorkflowNodeDto n : w.graph().nodes()) {
                if (n.id() == null || !BriefRefs.NODE_PATH.matcher(w.ref() + "." + n.id()).matches()) {
                    continue;
                }
                String path = w.ref() + "." + n.id();
                real.add(path);
                if (isIrreversibleByRule(n)) {
                    irreversible.add(path);
                }
                nodes.append(path).append(" | type=").append(n.type()).append(" | ").append(n.label());
                Object method = n.config().get("method");
                Object channel = n.config().get("channel");
                if (method != null) {
                    nodes.append(" | method=").append(method);
                }
                if (channel != null) {
                    nodes.append(" | channel=").append(channel);
                }
                nodes.append('\n');
            }
        }
        List<String> humanMustDecide = new ArrayList<>();
        if (!real.isEmpty()) {
            JsonNode tagged = llm.callJson("trust", """
                You tag workflow nodes by how bad a mistake would be. Output ONE JSON object (no markdown fences):
                {"irreversible": ["node path", ...], "humanMustDecide": ["node path", ...]}.
                "irreversible": nodes whose action cannot be undone - sends money, deletes data, sends messages to
                people, bulk sends, writes to an external system. "humanMustDecide": the subset where a person
                should still decide even when the solution is fully trusted (money, legal, safety, irreversible
                deletion). Use ONLY paths from the list, copied exactly. Reading, searching, summarising and
                drafting are reversible.
                """, "Goal: " + prompt + "\nNodes:\n" + nodes);
            for (String p : strings(tagged.path("irreversible"))) {
                if (real.contains(p)) {
                    irreversible.add(p);
                } else {
                    warnings.add("Ignored an irreversible tag for '" + p + "': no such workflow node.");
                }
            }
            for (String p : strings(tagged.path("humanMustDecide"))) {
                if (real.contains(p)) {
                    humanMustDecide.add(p);
                    irreversible.add(p);
                }
            }
        }
        return new Brief.Trust("UNDER_WATCH", new ArrayList<>(irreversible), humanMustDecide);
    }

    /** Deterministic floor under the model's tagging: side effects that are unambiguous from the node config alone. */
    static boolean isIrreversibleByRule(WorkflowNodeDto node) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        Map<String, Object> cfg = node.config();
        switch (type) {
            case "http" -> {
                Object method = cfg.get("method");
                return method != null && !HTTP_READ_METHODS.contains(method.toString().toUpperCase(Locale.ROOT));
            }
            case "output" -> {
                Object channel = cfg.get("channel");
                return channel != null && IRREVERSIBLE_OUTPUT_CHANNELS.contains(channel.toString().toLowerCase(Locale.ROOT));
            }
            case "device_control" -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ================================================================== eval + personas

    record EvalAndPersonas(Brief.EvalSet evalSet, Brief.Rehearsal rehearsal) {
    }

    EvalAndPersonas evalOf(
        String prompt, String locale, List<AgentSpec> agents, List<WorkflowSpec> workflows, List<String> warnings
    ) {
        StringBuilder summary = new StringBuilder();
        agents.forEach(a -> summary.append("- agent ").append(a.name()).append(": ").append(orElse(a.description(), "")).append('\n'));
        workflows.forEach(w -> summary.append("- workflow ").append(w.name()).append('\n'));
        JsonNode out = llm.callJson("evalSet", """
            You write the test set for a solution on an AI-agent platform. Output ONE JSON object (no markdown
            fences):
            {"cases": [{"input": "what a real user would send", "expected": "what a good answer contains (optional)",
                        "rubric": "how to judge the answer, one or two sentences"}],
             "personas": [{"name": "", "traits": ["", ""], "opening": "the first message this person sends"}]}
            Write 6 to 12 cases covering typical requests, edge cases, missing information, and at least two that
            try to make the agent do something it should refuse. Write 2 to 3 personas of realistic, different
            users (one difficult). Write all text in the language with ISO code\s""" + locale + ".",
            "Goal: " + prompt + "\nThe solution:\n" + summary);
        List<EvalCase> cases = new ArrayList<>();
        for (JsonNode c : out.path("cases")) {
            String input = text(c, "input");
            String rubric = text(c, "rubric");
            if (input != null && rubric != null && cases.size() < MAX_EVAL_CASES) {
                cases.add(new EvalCase(input, text(c, "expected"), rubric));
            }
        }
        if (cases.size() < 4) {
            throw new BriefValidationException("evalSet", "The planner wrote only " + cases.size() + " usable test cases (at least 4 are needed).");
        }
        if (cases.size() < 6) {
            warnings.add("Only " + cases.size() + " test cases were written; add a few more for a steadier quality gate.");
        }
        List<Persona> personas = personasOf(out.path("personas"));
        if (personas.isEmpty()) {
            // The shared evalSet call sometimes returns cases but no personas; one focused retry before the floor.
            JsonNode retry = personasOnly(prompt, locale, summary);
            if (retry != null) {
                personas = personasOf(retry.path("personas"));
            }
        }
        if (personas.isEmpty()) {
            warnings.add("No rehearsal personas were written - the generic pair is used instead.");
            personas = new ArrayList<>(Brief.Rehearsal.fallbackPersonas());
        }
        Brief.EvalSet evalSet = new Brief.EvalSet(agents.get(0).ref(), cases, new Brief.Gate(DEFAULT_GATE_MIN_SCORE));
        return new EvalAndPersonas(evalSet, new Brief.Rehearsal(personas));
    }

    /** Parses the personas array of one model reply; entries with no name or no traits are dropped. */
    private List<Persona> personasOf(JsonNode array) {
        List<Persona> personas = new ArrayList<>();
        for (JsonNode p : array) {
            String name = text(p, "name");
            List<String> traits = strings(p.path("traits"));
            if (name != null && !traits.isEmpty() && personas.size() < MAX_PERSONAS) {
                personas.add(new Persona(name, traits, text(p, "opening")));
            }
        }
        return personas;
    }

    /** One personas-only call, used when the evalSet reply carried none; an unparseable reply just yields null. */
    private JsonNode personasOnly(String prompt, String locale, StringBuilder summary) {
        try {
            return llm.callJson("personas", """
                You write rehearsal personas for a solution on an AI-agent platform. Output ONE JSON object (no
                markdown fences):
                {"personas": [{"name": "", "traits": ["", ""], "opening": "the first message this person sends"}]}
                Write 2 to 3 personas of realistic, different users of this solution (one difficult). Write all
                text in the language with ISO code\s""" + locale + ".",
                "Goal: " + prompt + "\nThe solution:\n" + summary);
        } catch (BriefValidationException e) {
            log.warn("Brief planner: personas retry was unusable, falling back to the generic pair");
            return null;
        }
    }

    // ================================================================== narrative

    Brief.Narrative narrativeOf(
        String prompt, String locale, List<AgentSpec> agents, List<WorkflowSpec> workflows, List<ToolSpec> tools,
        List<McpServerSpec> mcp, List<KnowledgeSpec> knowledge, List<SecretSpec> secrets,
        List<ProcedureSpec> procedures, List<ChannelSpec> channels, Brief.Trust trust
    ) {
        Map<String, String> items = new LinkedHashMap<>();
        agents.forEach(a -> items.put(a.ref(), "agent \"" + a.name() + "\" - " + orElse(a.description(), "")));
        workflows.forEach(w -> items.put(w.ref(), "workflow \"" + w.name() + "\" with " + w.graph().nodes().size() + " nodes"));
        tools.forEach(t -> items.put(t.ref(), "tool \"" + orElse(t.name(), t.ref()) + "\" (" + t.kind() + ")"));
        mcp.forEach(m -> items.put(m.ref(), "MCP server \"" + orElse(m.name(), m.registryId()) + "\""));
        knowledge.forEach(k -> items.put(k.ref(), "knowledge collection \"" + k.name() + "\""));
        secrets.forEach(s -> items.put(s.ref(), "secret " + s.name() + " (" + s.why() + ")"));
        procedures.forEach(p -> items.put(p.ref(), "procedure \"" + p.name() + "\""));
        channels.forEach(c -> items.put(c.ref(), "channel " + c.kind() + " bound to " + c.bindTo()));
        Map<String, String> nodeLabels = new LinkedHashMap<>();
        for (WorkflowSpec w : workflows) {
            for (WorkflowNodeDto n : w.graph().nodes()) {
                nodeLabels.put(w.ref() + "." + n.id(), n.type() + " node \"" + n.label() + "\"");
            }
        }
        for (String path : trust.irreversible()) {
            items.put(path, "irreversible " + nodeLabels.getOrDefault(path, "workflow node"));
        }

        Brief.Narrative narrative = requestNarrative(prompt, locale, items, items.keySet());
        Set<String> missing = new LinkedHashSet<>(items.keySet());
        missing.removeAll(narrative.perNode().keySet());
        if (!missing.isEmpty()) {
            Map<String, String> subset = new LinkedHashMap<>();
            missing.forEach(k -> subset.put(k, items.get(k)));
            Brief.Narrative retry = requestNarrative(prompt, locale, subset, subset.keySet());
            Map<String, NodeNarrative> merged = new LinkedHashMap<>(narrative.perNode());
            retry.perNode().forEach(merged::putIfAbsent);
            narrative = new Brief.Narrative(narrative.whyAgentic(), merged);
            missing.removeAll(merged.keySet());
            if (!missing.isEmpty()) {
                throw new BriefValidationException("narrative", "The planner did not explain: " + String.join(", ", missing) + ".");
            }
        }
        return narrative;
    }

    private Brief.Narrative requestNarrative(String prompt, String locale, Map<String, String> items, Set<String> keys) {
        StringBuilder list = new StringBuilder();
        items.forEach((k, v) -> list.append(k).append(": ").append(v).append('\n'));
        JsonNode out = llm.callJson("narrative", """
            You explain a solution to the person who asked for it. Output ONE JSON object (no markdown fences):
            {"whyAgentic": "2 or 3 sentences: why this needs agents rather than a form or a script",
             "perNode": {"<key>": {"why": "why this exists", "tune": "what to change here if results are wrong,
             naming the real console control (for example the agent's system prompt in the Workbench, the max
             tokens field, the tool allow-list, the Knowledge toggle, the node on the Canvas, the Secrets page,
             the MCP connect dialog)", "proof": "how the user will know it worked"}}}
            Include EXACTLY one perNode entry for every key in the list, keys copied exactly. Plain language, no
            jargon, no prices. Write everything in the language with ISO code\s""" + locale + ".",
            "Goal: " + prompt + "\nKeys to explain:\n" + list);
        Map<String, NodeNarrative> perNode = new LinkedHashMap<>();
        JsonNode per = out.path("perNode");
        for (String key : keys) {
            JsonNode e = per.path(key);
            String why = text(e, "why");
            String tune = text(e, "tune");
            String proof = text(e, "proof");
            if (why != null && tune != null && proof != null) {
                perNode.put(key, new NodeNarrative(why, tune, proof));
            }
        }
        return new Brief.Narrative(orElse(text(out, "whyAgentic"), ""), perNode);
    }
}
