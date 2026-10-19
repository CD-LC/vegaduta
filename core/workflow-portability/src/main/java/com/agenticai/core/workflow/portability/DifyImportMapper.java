package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.WorkflowPortabilityRedactor;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.portability.DifyImportModel.Ctx;
import com.agenticai.core.workflow.portability.DifyImportModel.Exit;
import com.agenticai.core.workflow.portability.DifyImportModel.Level;
import com.agenticai.core.workflow.portability.DifyImportModel.Mapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort import of a Dify app DSL ({@code kind: app}, {@code workflow.graph.nodes/edges}, as
 * YAML or JSON) into a {@link PortableWorkflow}, with an honest account of what each node became.
 * Authored from the observed public DSL file format; no Dify code was copied (Dify's own licence
 * does not cover a data format, and none of its code is used here).
 *
 * <p>Mapping, by Dify node {@code data.type}:
 *
 * <ul>
 *   <li>{@code start} - Trigger; {@code llm}, {@code parameter-extractor}, {@code agent} - an Agent
 *       step whose agent the user must pick (the prompt is kept as text, not applied); {@code
 *       knowledge-retrieval} - Knowledge (the dataset is a placeholder to map); {@code if-else} - a
 *       chain of Conditions (one per case); {@code question-classifier} - a Supervisor with one
 *       route per class; {@code http-request} - HTTP; {@code template-transform} - Transform
 *       (template), only when it uses plain variable substitution; {@code answer} / {@code end} - a
 *       Transform that builds the text, then an Output; {@code iteration} - a Sub-Flow over the
 *       body nodes; {@code tool} - a Tool step on an agent to pick; {@code code} - a stub that
 *       keeps JavaScript as text and never runs it (Python is a placeholder).
 *   <li>everything else ({@code variable-aggregator}, {@code assigner}, {@code document-extractor},
 *       ...) - an inert pass-through placeholder that keeps its title, type and
 *       (credential-redacted) data.
 * </ul>
 *
 * <p>Dify variable references ({@code {{#node.var#}}}) are rewritten to {@code {{
 * $node["Title"].var }}} so the data still flows by name; every node that carried one is marked for
 * review because a VegaDuta node's output shape differs from Dify's. Secret environment variables
 * and API keys are never imported: only their names are kept, as placeholders.
 */
public final class DifyImportMapper {

    private static final int TEXT_CAP_CHARS = 20_000;

    record DNode(
            String id,
            String parent,
            String type,
            String title,
            JsonNode raw,
            JsonNode data,
            int index) {}

    private final ObjectMapper mapper;
    private final DifyReferences references;
    private final DifyNodeFactory factory;
    private final DifyBranchMapping branches;
    private final DifyOutputMapping outputs;
    private final PortableWorkflow wf = new PortableWorkflow();
    private final Map<String, String> titles = new HashMap<>();
    private final Set<String> iterationIds = new HashSet<>();
    private final Map<String, Boolean> envSecret = new LinkedHashMap<>();
    private int edgeSeq;

    /** Every edge of the graph, so an iteration body can find the edges between its own nodes. */
    private List<JsonNode> graphEdges = new ArrayList<>();

    private DifyImportMapper(ObjectMapper mapper) {
        this.mapper = mapper;
        this.references = new DifyReferences(mapper, titles, iterationIds, envSecret);
        this.factory = new DifyNodeFactory(mapper);
        this.branches = new DifyBranchMapping(wf, references, factory);
        this.outputs = new DifyOutputMapping(references, factory);
    }

    /**
     * @throws IllegalArgumentException when the document is not a Dify app with a workflow graph
     */
    public static PortableWorkflow map(ObjectMapper mapper, JsonNode root) {
        return new DifyImportMapper(mapper).run(root);
    }

    // ---- driver
    // ------------------------------------------------------------------------------------------

    private PortableWorkflow run(JsonNode root) {
        JsonNode app = AgentImportJson.obj(root, "app");
        JsonNode workflow = AgentImportJson.obj(root, "workflow");
        JsonNode graph = workflow == null ? null : AgentImportJson.obj(workflow, "graph");
        if (graph == null) {
            String mode = app == null ? null : AgentImportJson.text(app, "mode");
            throw new IllegalArgumentException(
                    "This Dify export has no workflow graph"
                            + (mode == null
                                    ? ""
                                    : " (app mode '" + AgentImportText.inline(mode, 30) + "')")
                            + ". Only Dify workflow and chatflow apps can be imported here; a plain chat or agent app is better "
                            + "recreated as an agent (Agents > Import).");
        }
        JsonNode nodes = AgentImportJson.arr(graph, "nodes");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("The Dify workflow graph has no nodes.");
        }
        if (nodes.size() > WorkflowDslImporter.MAX_NODES) {
            throw new IllegalArgumentException(
                    "The Dify workflow has "
                            + nodes.size()
                            + " nodes; the import limit is "
                            + WorkflowDslImporter.MAX_NODES
                            + ".");
        }
        JsonNode edges = AgentImportJson.arr(graph, "edges");
        if (edges != null && edges.size() > WorkflowDslImporter.MAX_EDGES) {
            throw new IllegalArgumentException(
                    "The Dify workflow has "
                            + edges.size()
                            + " connections; the import limit is "
                            + WorkflowDslImporter.MAX_EDGES
                            + ".");
        }
        wf.name =
                AgentImportText.sanitizeName(
                        app == null ? null : AgentImportJson.text(app, "name"),
                        "Imported Dify workflow");
        String description = app == null ? null : AgentImportJson.text(app, "description");
        wf.description = description == null ? null : AgentImportText.truncate(description, 2000);

        List<DNode> all = new ArrayList<>();
        int i = 0;
        for (JsonNode raw : nodes) {
            i++;
            if (!raw.isObject()) {
                wf.report.warn("Node #" + i + " is not an object and was skipped.");
                continue;
            }
            JsonNode data = AgentImportJson.obj(raw, "data");
            String id = idText(raw, "id");
            if (id == null || data == null) {
                wf.report.warn("Node #" + i + " has no id or data and was skipped.");
                continue;
            }
            String type = AgentImportJson.text(data, "type");
            String title = AgentImportJson.text(data, "title");
            String parent = idText(raw, "parentId");
            if (parent == null) {
                parent = idText(data, "iteration_id");
            }
            all.add(
                    new DNode(
                            id,
                            parent,
                            type == null ? "" : type,
                            title == null ? id : title,
                            raw,
                            data,
                            i));
        }
        Set<String> knownIds = new HashSet<>();
        for (DNode n : all) {
            knownIds.add(n.id());
            titles.put(n.id(), n.title());
            if ("iteration".equals(n.type())) {
                iterationIds.add(n.id());
            }
        }
        readEnvironment(workflow);

        List<DNode> top = new ArrayList<>();
        Map<String, List<DNode>> children = new LinkedHashMap<>();
        for (DNode n : all) {
            if (n.parent() != null && knownIds.contains(n.parent())) {
                children.computeIfAbsent(n.parent(), k -> new ArrayList<>()).add(n);
            } else {
                top.add(n);
            }
        }
        List<JsonNode> edgeList = new ArrayList<>();
        if (edges != null) {
            edges.forEach(edgeList::add);
        }
        wf.fidelity.sourceConnections = edgeList.size();
        graphEdges = edgeList;
        mapGraph(top, edgeList, children, 0, wf.nodes, wf.edges);

        wf.report.converted(
                "Draft only",
                "The workflow is created as a DRAFT. It is never activated or triggered by "
                        + "the import, and no secret, API key or credential is carried over.");
        Object version = root.get("version");
        if (version != null && version.toString().length() < 40) {
            wf.report.converted("Dify DSL version", version.toString().replace("\"", ""));
        }
        JsonNode conversation =
                workflow == null ? null : AgentImportJson.arr(workflow, "conversation_variables");
        if (conversation != null && !conversation.isEmpty()) {
            wf.report.unsupported(
                    "Conversation variables (" + conversation.size() + ")",
                    "Chatflow conversation memory has no equivalent here; references to it are left as written.");
        }
        return wf;
    }

    private void readEnvironment(JsonNode workflow) {
        JsonNode env =
                workflow == null ? null : AgentImportJson.arr(workflow, "environment_variables");
        if (env == null) {
            return;
        }
        for (JsonNode item : env) {
            String name = AgentImportJson.text(item, "name");
            if (name == null) {
                continue;
            }
            boolean secret = "secret".equals(AgentImportJson.text(item, "value_type"));
            envSecret.put(name, secret);
            if (secret) {
                wf.placeholders.registerExact(
                        "secret:" + name, PlaceholderRegistry.SECRET, name, "", "environment");
                wf.report.needsManual(
                        "Secret variable '" + AgentImportText.inline(name, 60) + "'",
                        "Dify secret values are never exported and never imported; only the name is kept.",
                        "Create a vault secret with this name; HTTP nodes refer to it as {{secret."
                                + name
                                + "}}.");
            } else {
                wf.report.unsupported(
                        "Environment variable '" + AgentImportText.inline(name, 60) + "'",
                        "Plain environment variables have no equivalent store here; references to it become {{ $vars."
                                + name
                                + " }} and need a value.");
            }
        }
    }

    // ---- graph
    // -------------------------------------------------------------------------------------------

    /** Maps one level of nodes (the top graph, or an iteration body) and the edges between them. */
    private void mapGraph(
            List<DNode> dnodes,
            List<JsonNode> allEdges,
            Map<String, List<DNode>> children,
            int depth,
            List<WorkflowNodeDto> outNodes,
            List<WorkflowEdgeDto> outEdges) {
        Map<String, Mapped> mapped = new LinkedHashMap<>();
        for (DNode n : dnodes) {
            if ("custom-note".equals(AgentImportJson.text(n.raw(), "type")) || n.type().isEmpty()) {
                wf.fidelity.sourceNodes++;
                wf.fidelity.skipped++;
                wf.report.unsupported(
                        "Note '" + AgentImportText.inline(n.title(), 60) + "'",
                        "Canvas notes are annotations, not steps, so they are not imported.");
                continue;
            }
            if ("iteration-start".equals(n.type()) || "loop-start".equals(n.type())) {
                continue;
            }
            Mapped m = mapNode(n, children, depth);
            mapped.put(n.id(), m);
            {
                wf.fidelity.sourceNodes++;
                switch (m.level) {
                    case EXACT -> {
                        wf.fidelity.exact++;
                        wf.report.converted(
                                "Node '" + AgentImportText.inline(n.title(), 80) + "'",
                                "Converted to a " + m.nodes.get(0).type() + " node.");
                    }
                    case APPROXIMATE -> {
                        wf.fidelity.approximate++;
                        wf.report.needsManual(
                                "Node '"
                                        + AgentImportText.inline(n.title(), 80)
                                        + "' ("
                                        + n.type()
                                        + ")",
                                String.join(" ", m.notes),
                                "Open the node on the canvas and check it before activating the workflow.");
                    }
                    default -> {
                        wf.fidelity.placeholders++;
                        wf.report.unsupported(
                                "Node '"
                                        + AgentImportText.inline(n.title(), 80)
                                        + "' ("
                                        + n.type()
                                        + ")",
                                m.reason
                                        + " Kept as an inert pass-through placeholder with its original title, type and "
                                        + "data (credentials removed).");
                    }
                }
            }
            outNodes.addAll(m.nodes);
            outEdges.addAll(m.internalEdges);
            for (WorkflowNodeDto node : m.nodes) {
                PlaceholderScanner.scan(
                        node.config(),
                        node.id(),
                        AgentImportText.inline(n.title(), 80),
                        wf.placeholders,
                        Map.of());
            }
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode edge : allEdges) {
            String source = idText(edge, "source");
            String target = idText(edge, "target");
            boolean sourceHere = source != null && mapped.containsKey(source);
            boolean targetHere = target != null && mapped.containsKey(target);
            if (!sourceHere || !targetHere) {
                // An edge of another level (or from an iteration-start marker) is that level's
                // business.
                if ((sourceHere != targetHere) && !isInternalMarker(source, target, children)) {
                    wf.fidelity.connectionsDropped++;
                    wf.report.warn(
                            "A connection involving '"
                                    + AgentImportText.inline(String.valueOf(source), 40)
                                    + "' and '"
                                    + AgentImportText.inline(String.valueOf(target), 40)
                                    + "' was not imported because one end is not a step here.");
                }
                continue;
            }
            Mapped from = mapped.get(source);
            Mapped to = mapped.get(target);
            Exit exit = from.exits.apply(idText(edge, "sourceHandle"));
            String key = exit.nodeId() + "\u0000" + exit.handle() + "\u0000" + to.entryId;
            if (!seen.add(key)) {
                continue;
            }
            outEdges.add(
                    PortableNodes.edge(
                            "e" + (++edgeSeq), exit.nodeId(), exit.handle(), to.entryId));
            wf.fidelity.connectionsKept++;
        }
    }

    /** A node or handle id as text: YAML often leaves ids unquoted, which parses as a number. */
    static String idText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            String s = v.asText().trim();
            return s.isEmpty() ? null : s;
        }
        return v.isIntegralNumber() ? v.asText() : null;
    }

    private boolean isInternalMarker(
            String source, String target, Map<String, List<DNode>> children) {
        for (List<DNode> list : children.values()) {
            for (DNode n : list) {
                if (n.id().equals(source) || n.id().equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Mapped mapNode(DNode n, Map<String, List<DNode>> children, int depth) {
        JsonNode data = WorkflowPortabilityRedactor.redact(mapper, n.data(), n.id() + "_").json();
        Ctx c = new Ctx(references, n, data);
        switch (n.type()) {
            case "start" -> {
                return start(c);
            }
            case "llm" -> {
                return agent(c, "llm");
            }
            case "parameter-extractor" -> {
                return agent(c, "parameter-extractor");
            }
            case "agent" -> {
                return agent(c, "agent");
            }
            case "knowledge-retrieval" -> {
                return knowledge(c);
            }
            case "if-else" -> {
                return branches.ifElse(c);
            }
            case "question-classifier" -> {
                return branches.classifier(c);
            }
            case "http-request" -> {
                return http(c);
            }
            case "template-transform" -> {
                return outputs.templateTransform(c);
            }
            case "answer" -> {
                return outputs.answer(c, c.text(AgentImportJson.text(data, "answer")));
            }
            case "end" -> {
                return outputs.end(c);
            }
            case "code" -> {
                return code(c);
            }
            case "tool" -> {
                return tool(c);
            }
            case "iteration" -> {
                return iteration(c, children, depth);
            }
            default -> {
                return factory.placeholder(
                        c, "No equivalent for the Dify node type '" + n.type() + "'.");
            }
        }
    }

    // ---- node mappers
    // ------------------------------------------------------------------------------------

    private Mapped start(Ctx c) {
        JsonNode variables = AgentImportJson.arr(c.data, "variables");
        if (variables != null && !variables.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonNode v : variables) {
                String name = AgentImportJson.text(v, "variable");
                if (name != null) {
                    names.add(name);
                }
            }
            c.lose(
                    "Input variables ("
                            + String.join(", ", names)
                            + ") are not declared here: the trigger's payload is "
                            + "the node output, and references to them were rewritten to $node[...] reads of its fields.");
        }
        return factory.single(c, "trigger", c.node.title(), new LinkedHashMap<>(), Level.EXACT);
    }

    private Mapped agent(Ctx c, String kind) {
        String key =
                wf.placeholders.register(
                        PlaceholderRegistry.AGENT,
                        c.node.title(),
                        c.node.title(),
                        c.node.id(),
                        "agentId");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("agentId", PlaceholderRegistry.token(key));
        JsonNode model = AgentImportJson.obj(c.data, "model");
        if (model != null) {
            String provider = AgentImportJson.text(model, "provider");
            String name = AgentImportJson.text(model, "name");
            if (name != null) {
                config.put(
                        "importedModel",
                        AgentImportText.inline(
                                (provider == null ? "" : provider + "/") + name, 120));
            }
        }
        if ("llm".equals(kind)) {
            StringBuilder prompt = new StringBuilder();
            JsonNode template = c.data.get("prompt_template");
            if (template != null && template.isArray()) {
                for (JsonNode part : template) {
                    String text = AgentImportJson.text(part, "text");
                    if (text != null) {
                        prompt.append(
                                        AgentImportJson.text(part, "role") == null
                                                ? ""
                                                : AgentImportJson.text(part, "role") + ": ")
                                .append(c.text(text))
                                .append('\n');
                    }
                }
            } else if (template != null && template.isObject()) {
                String text = AgentImportJson.text(template, "text");
                if (text != null) {
                    prompt.append(c.text(text));
                }
            }
            if (prompt.length() > 0) {
                config.put(
                        "importedPrompt",
                        AgentImportText.truncate(prompt.toString().strip(), TEXT_CAP_CHARS));
            }
        } else if ("parameter-extractor".equals(kind)) {
            StringBuilder sb = new StringBuilder("Extract these parameters as JSON:\n");
            JsonNode params = AgentImportJson.arr(c.data, "parameters");
            if (params != null) {
                for (JsonNode p : params) {
                    sb.append("- ")
                            .append(String.valueOf(AgentImportJson.text(p, "name")))
                            .append(" (")
                            .append(String.valueOf(AgentImportJson.text(p, "type")))
                            .append(")")
                            .append(p.path("required").asBoolean(false) ? " required" : "")
                            .append(": ")
                            .append(
                                    AgentImportJson.text(p, "description") == null
                                            ? ""
                                            : AgentImportJson.text(p, "description"))
                            .append('\n');
                }
            }
            String instruction = AgentImportJson.text(c.data, "instruction");
            if (instruction != null) {
                sb.append('\n').append(c.text(instruction));
            }
            config.put(
                    "importedInstructions",
                    AgentImportText.truncate(sb.toString().strip(), TEXT_CAP_CHARS));
        }
        c.lose(
                "Choose the agent that should run this step (placeholder "
                        + key
                        + "). The Dify prompt, model and "
                        + "settings were not applied: they are kept as importedPrompt / importedInstructions / importedModel. "
                        + "The agent receives this node's input.");
        return factory.single(c, "agent", c.node.title(), config, Level.APPROXIMATE);
    }

    private Mapped knowledge(Ctx c) {
        Map<String, Object> config = new LinkedHashMap<>();
        JsonNode datasets = AgentImportJson.arr(c.data, "dataset_ids");
        if (datasets != null && !datasets.isEmpty()) {
            String first = AgentImportJson.textOf(datasets.get(0));
            String key =
                    wf.placeholders.register(
                            PlaceholderRegistry.KNOWLEDGE_BASE,
                            first == null ? c.node.title() : first,
                            "Knowledge for " + c.node.title(),
                            c.node.id(),
                            "collectionId");
            config.put("collectionId", PlaceholderRegistry.token(key));
            c.lose("Map the knowledge base (placeholder " + key + ").");
            if (datasets.size() > 1) {
                c.lose(
                        "The node searched "
                                + datasets.size()
                                + " Dify datasets; a Knowledge node searches one "
                                + "collection, so only the first was carried over.");
            }
        } else {
            c.lose("No dataset was selected in the Dify node; choose a knowledge base.");
        }
        Double topK =
                AgentImportJson.number(
                        AgentImportJson.obj(c.data, "multiple_retrieval_config"), "top_k");
        if (topK != null && topK >= 1 && topK <= 50) {
            config.put("topK", topK.intValue());
        }
        c.lose(
                "The search query is this node's input, not the Dify query variable; reranking and score thresholds "
                        + "were not carried over.");
        return factory.single(c, "knowledge", c.node.title(), config, Level.APPROXIMATE);
    }

    // ---- branching, HTTP and text output (mapped by collaborators)
    // ----------------------------------------

    private Mapped http(Ctx c) {
        Map<String, Object> config = new DifyHttpMapping(mapper).config(c);
        if (config == null) {
            return factory.placeholder(c, "The HTTP Request node has no URL to carry over.");
        }
        return factory.single(c, "http", c.node.title(), config, Level.EXACT);
    }

    // ---- code / tool / iteration
    // -------------------------------------------------------------------------

    private Mapped code(Ctx c) {
        String language = AgentImportJson.text(c.data, "code_language");
        String source = AgentImportJson.text(c.data, "code");
        boolean javascript =
                language != null && language.toLowerCase(Locale.ROOT).startsWith("javascript");
        Map<String, Object> config = PortableNodes.passthroughConfig();
        config.put("importedLanguage", language == null ? "unknown" : language);
        if (source != null) {
            config.put("importedSource", AgentImportText.truncate(source, TEXT_CAP_CHARS));
        }
        if (!javascript) {
            return factory.placeholder(
                    c,
                    "Python code cannot run on this platform. The original source is kept in the "
                            + "node's importedSource field.",
                    config);
        }
        c.lose(
                "JavaScript is never executed on this platform. The original source is kept in the node's "
                        + "importedSource field and the node passes its input through unchanged until you replace it with a "
                        + "Transform, Agent or Tool step.");
        return factory.single(
                c, "code", "[Needs review] " + c.node.title(), config, Level.APPROXIMATE);
    }

    private Mapped tool(Ctx c) {
        String toolName = AgentImportJson.firstText(c.data, "tool_name", "provider_name");
        if (toolName == null) {
            return factory.placeholder(c, "The tool node does not name a tool.");
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
        config.put("toolName", AgentImportText.inline(toolName, 120));
        ObjectNode args = mapper.createObjectNode();
        JsonNode params = c.data.get("tool_parameters");
        if (params != null && params.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = params.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode value = entry.getValue().get("value");
                if (value == null || value.isNull()) {
                    continue;
                }
                if (value.isTextual()) {
                    args.put(entry.getKey(), c.text(value.asText()));
                } else if (value.isArray()) {
                    String expression = references.selectorExpression(c, value);
                    args.put(entry.getKey(), expression == null ? "" : "{{ " + expression + " }}");
                } else {
                    args.put(entry.getKey(), value.asText());
                }
            }
        }
        config.put("argsJson", args.toString());
        c.lose(
                "Pick the agent whose tool catalog should be used (placeholder "
                        + key
                        + "). The tool name was copied "
                        + "as written and may not exist under that name here; the Dify tool provider's credentials were not "
                        + "imported.");
        return factory.single(c, "tool", c.node.title(), config, Level.APPROXIMATE);
    }

    private Mapped iteration(Ctx c, Map<String, List<DNode>> children, int depth) {
        List<DNode> body = children.get(c.node.id());
        if (depth >= 1) {
            DifyIterationBody.reportUnmapped(wf, c.node, children, graphEdges);
            return factory.placeholder(c, "Nested iterations are not imported.");
        }
        if (body == null || body.stream().noneMatch(n -> !"iteration-start".equals(n.type()))) {
            DifyIterationBody.reportUnmapped(wf, c.node, children, graphEdges);
            return factory.placeholder(c, "The iteration has no body nodes to repeat.");
        }
        List<WorkflowNodeDto> bodyNodes = new ArrayList<>();
        List<WorkflowEdgeDto> bodyEdges = new ArrayList<>();
        // Body edges are those whose endpoints are both in the body; mapGraph keeps only what it
        // mapped.
        mapGraph(body, graphEdges, children, depth + 1, bodyNodes, bodyEdges);

        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> bodyConfig = new LinkedHashMap<>();
        // Plain maps, not DTO records: the placeholder resolver walks config as maps and lists.
        bodyConfig.put("nodes", mapper.convertValue(bodyNodes, List.class));
        bodyConfig.put("edges", mapper.convertValue(bodyEdges, List.class));
        config.put("body", bodyConfig);
        JsonNode iterator = c.data.get("iterator_selector");
        if (iterator != null
                && iterator.isArray()
                && iterator.size() >= 2
                && iterator.get(iterator.size() - 1).isTextual()) {
            config.put("itemsField", iterator.get(iterator.size() - 1).asText());
        }
        c.lose(
                "The Sub-Flow repeats the body once per item of the input field named by itemsField; check that "
                        + "field, the parallel/error settings (not carried) and how the body's result is collected.");
        return factory.single(c, "sub_flow", c.node.title(), config, Level.APPROXIMATE);
    }
}
