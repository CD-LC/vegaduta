package com.agenticai.core.workflow.portability;

import static com.agenticai.core.workflow.portability.DifyImportMapper.idText;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.portability.DifyImportModel.Ctx;
import com.agenticai.core.workflow.portability.DifyImportModel.Exit;
import com.agenticai.core.workflow.portability.DifyImportModel.Level;
import com.agenticai.core.workflow.portability.DifyImportModel.Mapped;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Dify's branching nodes for {@link DifyImportMapper}: a question classifier becomes a
 * Supervisor with one route per class, and an if/else becomes a chain of Condition nodes (one per
 * case).
 */
final class DifyBranchMapping {

    private final PortableWorkflow wf;
    private final DifyReferences references;
    private final DifyNodeFactory factory;

    DifyBranchMapping(PortableWorkflow wf, DifyReferences references, DifyNodeFactory factory) {
        this.wf = wf;
        this.references = references;
        this.factory = factory;
    }

    Mapped classifier(Ctx c) {
        JsonNode classes = AgentImportJson.arr(c.data, "classes");
        if (classes == null || classes.isEmpty()) {
            return factory.placeholder(c, "The classifier has no classes to route between.");
        }
        List<Map<String, Object>> routes = new ArrayList<>();
        for (JsonNode cls : classes) {
            String id = idText(cls, "id");
            String name = AgentImportJson.text(cls, "name");
            if (id == null) {
                continue;
            }
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("handle", id);
            route.put("label", name == null ? id : AgentImportText.inline(c.text(name), 120));
            routes.add(route);
        }
        if (routes.isEmpty()) {
            return factory.placeholder(c, "The classifier's classes have no ids to route by.");
        }
        String key =
                wf.placeholders.register(
                        PlaceholderRegistry.AGENT,
                        c.node.title(),
                        c.node.title(),
                        c.node.id(),
                        "agentId");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("agentId", PlaceholderRegistry.token(key));
        config.put("routes", routes);
        String instruction = AgentImportJson.text(c.data, "instruction");
        if (instruction != null) {
            config.put("instructions", AgentImportText.truncate(c.text(instruction), 2000));
        }
        c.lose(
                "An LLM picks the route, as in Dify, but with the agent you choose (placeholder "
                        + key
                        + ") instead "
                        + "of the model in the export.");
        Mapped m = factory.single(c, "supervisor", c.node.title(), config, Level.APPROXIMATE);
        m.exits =
                handle ->
                        new Exit(
                                c.node.id(),
                                handle == null || "source".equals(handle) ? null : handle);
        return m;
    }

    Mapped ifElse(Ctx c) {
        JsonNode cases = AgentImportJson.arr(c.data, "cases");
        if (cases == null || cases.isEmpty()) {
            return factory.placeholder(c, "The If/Else node has no cases.");
        }
        List<String> expressions = new ArrayList<>();
        List<String> caseIds = new ArrayList<>();
        for (JsonNode kase : cases) {
            String joiner =
                    "or".equalsIgnoreCase(AgentImportJson.text(kase, "logical_operator"))
                            ? " || "
                            : " && ";
            JsonNode conditions = AgentImportJson.arr(kase, "conditions");
            if (conditions == null || conditions.isEmpty()) {
                return factory.placeholder(c, "A case of the If/Else node has no conditions.");
            }
            List<String> parts = new ArrayList<>();
            for (JsonNode cond : conditions) {
                String expression = references.condition(c, cond);
                if (expression == null) {
                    return factory.placeholder(
                            c,
                            "The If/Else node uses an operator or operand the Condition node cannot "
                                    + "express ("
                                    + AgentImportText.inline(
                                            String.valueOf(
                                                    AgentImportJson.text(
                                                            cond, "comparison_operator")),
                                            30)
                                    + ").");
                }
                parts.add("(" + expression + ")");
            }
            expressions.add(String.join(joiner, parts));
            String caseId =
                    idText(kase, "case_id") != null ? idText(kase, "case_id") : idText(kase, "id");
            caseIds.add(caseId == null ? "case" + caseIds.size() : caseId);
        }
        Mapped m = new Mapped();
        m.level = Level.APPROXIMATE;
        m.label = c.node.title();
        List<String> nodeIds = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            String id = i == 0 ? c.node.id() : c.node.id() + "__case" + (i + 1);
            nodeIds.add(id);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("mode", "expression");
            config.put("expression", expressions.get(i));
            String label = i == 0 ? c.node.title() : c.node.title() + " (else if " + (i + 1) + ")";
            Map<String, Object> position = factory.position(c.node);
            position.put("y", ((Number) position.get("y")).longValue() + 110L * i);
            m.nodes.add(
                    PortableNodes.node(
                            id,
                            "condition",
                            AgentImportText.truncate(label, 200),
                            position,
                            config,
                            new ArrayList<>(),
                            true));
            if (i > 0) {
                m.internalEdges.add(
                        PortableNodes.edge(
                                "ec_" + c.node.id() + "_" + i, nodeIds.get(i - 1), "false", id));
            }
        }
        m.entryId = c.node.id();
        List<String> ids = nodeIds;
        List<String> handles = caseIds;
        m.exits =
                handle -> {
                    int idx = handle == null ? -1 : handles.indexOf(handle);
                    if (idx >= 0) {
                        return new Exit(ids.get(idx), "true");
                    }
                    return new Exit(ids.get(ids.size() - 1), "false");
                };
        c.lose(
                "Each case became a Condition node (the 'false' output of one feeds the next case); comparison details "
                        + "such as type coercion are not preserved.");
        m.notes.addAll(c.notes);
        if (c.refs) {
            m.notes.add(DifyNodeFactory.REFS_NOTE);
        }
        for (WorkflowNodeDto node : m.nodes) {
            node.data().put("importNotes", new ArrayList<>(m.notes));
        }
        return m;
    }
}
