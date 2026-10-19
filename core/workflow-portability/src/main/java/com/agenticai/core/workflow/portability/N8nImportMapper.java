package com.agenticai.core.workflow.portability;

import static com.agenticai.core.workflow.portability.N8nImportModel.firstNonBlank;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.WorkflowPortabilityRedactor;
import com.agenticai.core.workflow.portability.N8nImportModel.AiLink;
import com.agenticai.core.workflow.portability.N8nImportModel.Built;
import com.agenticai.core.workflow.portability.N8nImportModel.Conn;
import com.agenticai.core.workflow.portability.N8nImportModel.Level;
import com.agenticai.core.workflow.portability.N8nImportModel.NodeCtx;
import com.agenticai.core.workflow.portability.N8nImportModel.Outputs;
import com.agenticai.core.workflow.portability.N8nImportModel.Src;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort import of an n8n workflow export (JSON) into a {@link PortableWorkflow}, with an
 * honest account of what each source node became.
 *
 * <p>The mapping tables below were authored from the observed public n8n file format ({@code
 * nodes[]} with {@code type}/{@code parameters}/{@code position}/{@code credentials}, and {@code
 * connections} keyed by node NAME with one list per output index) - no n8n code was copied, and
 * n8n's own licence does not apply to a data format. Each source node ends up as exactly one of:
 *
 * <ul>
 *   <li><b>exact</b> - a direct equivalent (a manual trigger, a pass-through NoOp);
 *   <li><b>approximate</b> - mapped, but a person must check it (an IF whose comparison semantics
 *       differ, an HTTP request whose options were not all carried, anything that carried an n8n
 *       expression);
 *   <li><b>placeholder</b> - no honest equivalent. It becomes an inert pass-through Transform node
 *       that keeps the original name, type and (credential-redacted) parameters, so nothing is
 *       silently dropped and the graph stays connected. Switch, Set/Edit Fields, Merge, Wait,
 *       integration nodes and Python code land here.
 * </ul>
 *
 * <p>Credentials are never imported. n8n exports only a credential's id and name; the name is kept
 * as a named placeholder to re-map, the id is not read. Credential-shaped values typed into
 * parameters (an API key in a header, a token in a URL) are replaced with {@code
 * [[CREDENTIAL_n..]]} by the shared redactor before anything is copied. JavaScript from a Code node
 * is kept as text in the stub's config and is never run: this platform has no script engine, and
 * the stub passes its input through unchanged.
 */
public final class N8nImportMapper {

    private static final String PLATFORM = "n8n";
    private static final int PARAMS_CAP_CHARS = 30_000;
    private static final int CODE_CAP_CHARS = 20_000;

    private final ObjectMapper mapper;
    private final PortableWorkflow wf = new PortableWorkflow();
    private final Map<String, Src> byName = new LinkedHashMap<>();
    private final Map<String, Built> built = new LinkedHashMap<>();
    private final Map<String, Integer> outputCount = new HashMap<>();

    private N8nImportMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @throws IllegalArgumentException when the document has no {@code nodes} list or is too large
     */
    public static PortableWorkflow map(ObjectMapper mapper, JsonNode root) {
        return new N8nImportMapper(mapper).run(root);
    }

    // ---- driver
    // ------------------------------------------------------------------------------------------

    private PortableWorkflow run(JsonNode root) {
        JsonNode nodes = AgentImportJson.arr(root, "nodes");
        if (nodes == null) {
            throw new IllegalArgumentException("The n8n export has no \"nodes\" list.");
        }
        if (nodes.size() > WorkflowDslImporter.MAX_NODES) {
            throw new IllegalArgumentException(
                    "The n8n workflow has "
                            + nodes.size()
                            + " nodes; the import limit is "
                            + WorkflowDslImporter.MAX_NODES
                            + ".");
        }
        wf.name =
                AgentImportText.sanitizeName(
                        AgentImportJson.text(root, "name"), "Imported n8n workflow");

        readSources(nodes);
        List<Conn> main = new ArrayList<>();
        List<AiLink> ai = new ArrayList<>();
        readConnections(AgentImportJson.obj(root, "connections"), main, ai);

        Set<String> inMain = new HashSet<>();
        for (Conn c : main) {
            inMain.add(c.from());
            inMain.add(c.to());
        }
        Map<String, List<AiLink>> subNodes = new LinkedHashMap<>();
        for (AiLink link : ai) {
            if (!inMain.contains(link.from()) && byName.containsKey(link.from())) {
                subNodes.computeIfAbsent(link.from(), k -> new ArrayList<>()).add(link);
            }
        }

        for (Src s : byName.values()) {
            if (!subNodes.containsKey(s.name())) {
                built.put(s.name(), mapNode(s));
            }
        }
        attachSubNodes(subNodes);
        new N8nEdgeBuilder(wf, byName, built).build(main);
        settleReport();
        workflowLevelNotes(root);
        wf.report.converted(
                "Draft only",
                "The workflow is created as a DRAFT. It is never activated or triggered "
                        + "by the import, and no webhook, schedule or credential is created.");
        return wf;
    }

    private void readSources(JsonNode nodes) {
        int i = 0;
        for (JsonNode raw : nodes) {
            i++;
            if (!raw.isObject()) {
                wf.report.warn("Node #" + i + " is not an object and was skipped.");
                continue;
            }
            String type = AgentImportJson.text(raw, "type");
            if (type == null) {
                wf.report.warn("Node #" + i + " has no type and was skipped.");
                continue;
            }
            String name = AgentImportJson.text(raw, "name");
            if (name == null) {
                name = "Node " + i;
            }
            wf.fidelity.sourceNodes++;
            if (type.endsWith(".stickyNote")) {
                wf.fidelity.skipped++;
                wf.report.unsupported(
                        "Sticky note '" + AgentImportText.inline(name, 60) + "'",
                        "Sticky notes are canvas annotations, not steps, so they are not imported.");
                continue;
            }
            if (byName.containsKey(name)) {
                String renamed = name + " (" + i + ")";
                wf.report.warn(
                        "Two nodes are named '"
                                + AgentImportText.inline(name, 60)
                                + "'; the second was renamed '"
                                + AgentImportText.inline(renamed, 70)
                                + "'. Connections that name it are attached to the first.");
                name = renamed;
            }
            JsonNode params = AgentImportJson.obj(raw, "parameters");
            Double version = AgentImportJson.number(raw, "typeVersion");
            byName.put(
                    name,
                    new Src(
                            i,
                            "n" + i,
                            name,
                            type,
                            version == null ? 1.0 : version,
                            raw,
                            params == null ? mapper.createObjectNode() : params));
        }
    }

    private void readConnections(JsonNode connections, List<Conn> main, List<AiLink> ai) {
        if (connections == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> sources = connections.fields();
        while (sources.hasNext()) {
            Map.Entry<String, JsonNode> source = sources.next();
            if (!source.getValue().isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> kinds = source.getValue().fields();
            while (kinds.hasNext()) {
                Map.Entry<String, JsonNode> kind = kinds.next();
                JsonNode outputs = kind.getValue();
                if (!outputs.isArray()) {
                    continue;
                }
                if ("main".equals(kind.getKey())) {
                    outputCount.merge(source.getKey(), outputs.size(), Math::max);
                }
                for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                    JsonNode targets = outputs.get(outputIndex);
                    if (!targets.isArray()) {
                        continue;
                    }
                    for (JsonNode target : targets) {
                        wf.fidelity.sourceConnections++;
                        if (wf.fidelity.sourceConnections > WorkflowDslImporter.MAX_EDGES) {
                            throw new IllegalArgumentException(
                                    "The n8n workflow has more than "
                                            + WorkflowDslImporter.MAX_EDGES
                                            + " connections; the import limit is "
                                            + WorkflowDslImporter.MAX_EDGES
                                            + ".");
                        }
                        String to = AgentImportJson.text(target, "node");
                        if (to == null) {
                            wf.fidelity.connectionsDropped++;
                            continue;
                        }
                        if ("main".equals(kind.getKey())) {
                            main.add(new Conn(source.getKey(), outputIndex, to));
                        } else {
                            ai.add(new AiLink(source.getKey(), kind.getKey(), to));
                        }
                    }
                }
            }
        }
    }

    /**
     * AI components (models, tools, memory) hang off an agent through non-"main" connections; they
     * are not steps.
     */
    private void attachSubNodes(Map<String, List<AiLink>> subNodes) {
        N8nSubNodeLimits limits = new N8nSubNodeLimits();
        for (Map.Entry<String, List<AiLink>> entry : subNodes.entrySet()) {
            Src sub = byName.get(entry.getKey());
            wf.fidelity.placeholders++;
            // Redacted once per sub-node (not per link). A model or tool component carries the
            // credential name that
            // matters most (the LLM key): list it.
            JsonNode redacted = redactParams(sub);
            registerCredentials(new NodeCtx(sub, redacted));
            Object params = null;
            for (AiLink link : entry.getValue()) {
                wf.fidelity.connectionsDropped++;
                Built parent = built.get(link.to());
                N8nSubNodeLimits.Verdict verdict =
                        parent == null
                                ? N8nSubNodeLimits.Verdict.SKIP
                                : limits.admit(link.to(), sub.name(), link.connectionType());
                if (verdict.name().startsWith("WARN")) {
                    wf.report.warn(N8nSubNodeLimits.warning(verdict, link.to()));
                }
                if (verdict != N8nSubNodeLimits.Verdict.KEEP) {
                    continue;
                }
                if (params == null) {
                    params = capParams(PortableNodes.plain(mapper, redacted));
                }
                @SuppressWarnings("unchecked")
                List<Object> kept =
                        (List<Object>)
                                parent.node
                                        .config()
                                        .computeIfAbsent(
                                                "importedSubNodes", k -> new ArrayList<Object>());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", sub.name());
                item.put("type", sub.type());
                item.put("connection", link.connectionType());
                item.put("parameters", params);
                kept.add(item);
            }
            wf.report.unsupported(
                    "AI component '"
                            + AgentImportText.inline(sub.name(), 60)
                            + "' ("
                            + sub.type()
                            + ")",
                    "Attached to another node as '"
                            + entry.getValue().get(0).connectionType()
                            + "', which has no equivalent: it is not imported as a step. Its name, type and parameters "
                            + "(credentials removed) are kept on the node it was attached to.");
        }
    }

    // ---- node mapping
    // ------------------------------------------------------------------------------------

    private JsonNode redactParams(Src s) {
        return WorkflowPortabilityRedactor.redact(mapper, s.params(), s.id() + "_").json();
    }

    private Built mapNode(Src s) {
        NodeCtx c = new NodeCtx(s, redactParams(s));
        registerCredentials(c);
        String type = s.type();
        int dot = type.lastIndexOf('.');
        String shortType = dot >= 0 ? type.substring(dot + 1) : type;
        boolean langchain = type.contains("langchain");

        if (s.raw().path("disabled").asBoolean(false)) {
            c.lose(
                    "The node was disabled in n8n (n8n passes data straight through a disabled node), so it was "
                            + "imported as a pass-through.");
            return passthrough(c, "[Disabled] ", Level.APPROXIMATE);
        }
        switch (shortType) {
            case "manualTrigger", "start" -> {
                return finish(
                        c, "trigger", s.name(), new LinkedHashMap<>(), Level.EXACT, Outputs.PLAIN);
            }
            case "webhook" -> {
                return webhook(c);
            }
            case "scheduleTrigger", "cron", "interval" -> {
                return schedule(c);
            }
            case "httpRequest" -> {
                return http(c);
            }
            case "if" -> {
                return conditional(c);
            }
            case "code", "function", "functionItem" -> {
                return code(c);
            }
            case "respondToWebhook" -> {
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("channel", "log");
                c.lose(
                        "The HTTP response (status, headers, body shaping) is not reproduced; this platform returns "
                                + "the run's result. The step was imported as a log Output node.");
                return finish(c, "output", s.name(), config, Level.APPROXIMATE, Outputs.PLAIN);
            }
            case "splitInBatches" -> {
                c.lose(
                        "n8n walks the nodes after the 'loop' output once per batch and returns to this node; here the "
                                + "next node runs once per item and results are collected, then flow continues. Batch size and "
                                + "multi-step loop bodies are not preserved.");
                return finish(
                        c,
                        "loop",
                        s.name(),
                        new LinkedHashMap<>(),
                        Level.APPROXIMATE,
                        Outputs.LOOP);
            }
            case "noOp" -> {
                return passthrough(c, "", Level.EXACT);
            }
            case "executeWorkflow" -> {
                return subWorkflow(c);
            }
            case "agent", "openAi", "chainLlm" -> {
                if (langchain || "openAi".equals(shortType)) {
                    return agent(c);
                }
            }
            case "chatTrigger" -> {
                return finish(
                        c,
                        "trigger",
                        s.name(),
                        new LinkedHashMap<>(),
                        Level.APPROXIMATE,
                        Outputs.PLAIN);
            }
            case "switch" -> {
                return placeholder(
                        c,
                        "This platform has no Switch node (only a true/false Condition and an "
                                + "LLM-routed Supervisor), so the routing rules were not guessed.",
                        Outputs.INDEXED);
            }
            case "set", "editFields" -> {
                return placeholder(
                        c,
                        "Set / Edit Fields assigns typed fields; the Transform node cannot reproduce "
                                + "that exactly, so it was not guessed.",
                        Outputs.PLAIN);
            }
            default -> {
                // fall through to the generic handling below
            }
        }
        if (shortType.endsWith("Trigger") || shortType.endsWith("trigger")) {
            c.lose(
                    "This trigger type ("
                            + type
                            + ") has no direct equivalent; it was imported as a plain trigger. "
                            + "Bind a channel or schedule to it on this platform.");
            return finish(
                    c,
                    "trigger",
                    s.name(),
                    new LinkedHashMap<>(),
                    Level.APPROXIMATE,
                    Outputs.PLAIN);
        }
        int outputs = outputCount.getOrDefault(s.name(), 1);
        return placeholder(
                c,
                "No equivalent for the n8n node type '" + type + "'.",
                outputs > 1 ? Outputs.INDEXED : Outputs.PLAIN);
    }

    private void registerCredentials(NodeCtx c) {
        JsonNode credentials = AgentImportJson.obj(c.src.raw(), "credentials");
        if (credentials == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = credentials.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            // Only the NAME is read. The id points at the source instance and is never needed.
            String name = AgentImportJson.text(entry.getValue(), "name");
            String label =
                    entry.getKey() + (name == null ? "" : ": " + AgentImportText.inline(name, 60));
            String key =
                    wf.placeholders.register(
                            PlaceholderRegistry.CREDENTIAL,
                            entry.getKey() + "\u0000" + (name == null ? "" : name),
                            label,
                            c.src.id(),
                            "credentials");
            c.notes.add("Uses the n8n credential " + label + "; credentials are never imported.");
            String what = "n8n credential " + label;
            if (!wf.report.hasNeedsManualFor(what)) {
                wf.report.needsManual(
                        what,
                        "n8n exports only a credential's name and id, never its secret, and the import never carries one.",
                        "Create the matching integration or vault secret on this platform and attach it to the node "
                                + "(placeholder "
                                + key
                                + ").");
            }
        }
    }

    private Built webhook(NodeCtx c) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("channel", "WEBHOOK");
        String path = c.text("path");
        String method = c.text("httpMethod");
        c.notes.add(
                "n8n webhook"
                        + (path == null ? "" : " path '" + AgentImportText.inline(path, 60) + "'")
                        + (method == null ? "" : " (" + method + ")")
                        + " is not reproduced: a new webhook URL is created when you enable the webhook trigger on the "
                        + "activated workflow.");
        return finish(c, "trigger", c.src.name(), config, Level.EXACT, Outputs.PLAIN);
    }

    private Built schedule(NodeCtx c) {
        JsonNode rule = c.params.get("rule");
        String summary = rule == null ? "" : AgentImportText.truncate(rule.toString(), 200);
        c.lose(
                "Schedules are not imported"
                        + (summary.isEmpty() ? "" : " (n8n rule: " + summary + ")")
                        + ". Set the schedule on the workflow after activating it.");
        return finish(
                c,
                "trigger",
                c.src.name(),
                new LinkedHashMap<>(),
                Level.APPROXIMATE,
                Outputs.PLAIN);
    }

    private Built subWorkflow(NodeCtx c) {
        JsonNode idNode = c.params.get("workflowId");
        String identity =
                idNode == null
                        ? null
                        : idNode.isObject()
                                ? AgentImportJson.text(idNode, "value")
                                : AgentImportJson.textOf(idNode);
        String key =
                wf.placeholders.register(
                        PlaceholderRegistry.WORKFLOW,
                        identity == null ? c.src.name() : identity,
                        "Sub-workflow called by " + c.src.name(),
                        c.src.id(),
                        "targetWorkflowId");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("targetWorkflowId", PlaceholderRegistry.token(key));
        c.lose(
                "The sub-workflow it calls must exist on this platform; map it (placeholder "
                        + key
                        + ").");
        return finish(c, "workflow", c.src.name(), config, Level.APPROXIMATE, Outputs.PLAIN);
    }

    private Built agent(NodeCtx c) {
        String key =
                wf.placeholders.register(
                        PlaceholderRegistry.AGENT,
                        c.src.name(),
                        c.src.name(),
                        c.src.id(),
                        "agentId");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("agentId", PlaceholderRegistry.token(key));
        JsonNode options = c.params.path("options");
        String prompt = c.tx(c.text("text", "prompt"));
        String instructions =
                c.tx(
                        firstNonBlank(
                                AgentImportJson.text(options, "systemMessage"),
                                c.text("systemMessage")));
        String model = modelName(c.params.get("modelId"), c.params.get("model"));
        if (prompt != null) {
            config.put("importedPrompt", AgentImportText.truncate(prompt, CODE_CAP_CHARS));
        }
        if (instructions != null) {
            config.put(
                    "importedInstructions", AgentImportText.truncate(instructions, CODE_CAP_CHARS));
        }
        if (model != null) {
            config.put("importedModel", AgentImportText.inline(model, 120));
        }
        c.lose(
                "Choose the agent that should run this step (placeholder "
                        + key
                        + "). The n8n prompt and system message "
                        + "were not applied: they are kept as importedPrompt / importedInstructions to copy into the agent. "
                        + "The agent receives this node's input.");
        return finish(c, "agent", c.src.name(), config, Level.APPROXIMATE, Outputs.PLAIN);
    }

    private static String modelName(JsonNode... candidates) {
        for (JsonNode n : candidates) {
            if (n == null) {
                continue;
            }
            String s = n.isObject() ? AgentImportJson.text(n, "value") : AgentImportJson.textOf(n);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    // ---- HTTP and IF (mapped by collaborators)
    // ------------------------------------------------------------

    private Built http(NodeCtx c) {
        Map<String, Object> config = new N8nHttpMapping(mapper).config(c);
        if (config == null) {
            return placeholder(c, "The HTTP Request node has no URL to carry over.", Outputs.PLAIN);
        }
        return finish(c, "http", c.src.name(), config, Level.EXACT, Outputs.PLAIN);
    }

    private Built conditional(NodeCtx c) {
        N8nConditionMapping.Translation translation = N8nConditionMapping.translate(c);
        if (translation.expression() == null) {
            return placeholder(
                    c,
                    "The IF node uses "
                            + translation.failure()
                            + ", which the Condition node cannot express.",
                    Outputs.INDEXED);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "expression");
        config.put("expression", translation.expression());
        c.lose(
                "Comparison details (type coercion, case sensitivity) are not preserved: the condition was rewritten "
                        + "as an expression, and the true/false outputs keep their order.");
        return finish(c, "condition", c.src.name(), config, Level.APPROXIMATE, Outputs.CONDITION);
    }

    // ---- Code
    // --------------------------------------------------------------------------------------------

    private Built code(NodeCtx c) {
        String language = firstNonBlank(c.text("language"), "javaScript");
        boolean javascript = language.toLowerCase(Locale.ROOT).startsWith("javascript");
        String source =
                firstNonBlank(
                        c.text("jsCode"),
                        firstNonBlank(c.text("functionCode"), c.text("pythonCode")));
        Map<String, Object> config = PortableNodes.passthroughConfig();
        config.put("importedLanguage", javascript ? "javascript" : language);
        if (source != null) {
            // Kept as text only. Nothing on this platform executes it.
            config.put("importedSource", AgentImportText.truncate(source, CODE_CAP_CHARS));
        }
        if (!javascript) {
            return placeholder(
                    c,
                    "Python code cannot run on this platform. The original source is kept in the node's "
                            + "importedSource field.",
                    Outputs.PLAIN,
                    config);
        }
        c.lose(
                "JavaScript is never executed on this platform. The original source is kept in the node's "
                        + "importedSource field and the node passes its input through unchanged until you replace it with a "
                        + "Transform, Agent or Tool step.");
        Built b =
                finish(
                        c,
                        "code",
                        "[Needs review] " + c.src.name(),
                        config,
                        Level.APPROXIMATE,
                        Outputs.PLAIN);
        return b;
    }

    // ---- generic stand-ins
    // -------------------------------------------------------------------------------

    private Built passthrough(NodeCtx c, String labelPrefix, Level level) {
        Map<String, Object> config = PortableNodes.passthroughConfig();
        config.put(
                "importedFrom",
                PortableNodes.importedFrom(
                        PLATFORM,
                        c.src.type(),
                        c.src.name(),
                        capParams(PortableNodes.plain(mapper, c.params))));
        return finish(c, "code", labelPrefix + c.src.name(), config, level, Outputs.PLAIN);
    }

    private Built placeholder(NodeCtx c, String reason, Outputs outputs) {
        return placeholder(c, reason, outputs, PortableNodes.passthroughConfig());
    }

    private Built placeholder(
            NodeCtx c, String reason, Outputs outputs, Map<String, Object> config) {
        config.put("placeholder", Boolean.TRUE);
        config.put(
                "importedFrom",
                PortableNodes.importedFrom(
                        PLATFORM,
                        c.src.type(),
                        c.src.name(),
                        capParams(PortableNodes.plain(mapper, c.params))));
        c.notes.add(reason);
        Built b =
                finish(
                        c,
                        "code",
                        "[Unsupported] " + c.src.name(),
                        config,
                        Level.PLACEHOLDER,
                        outputs);
        b.reason = reason;
        return b;
    }

    private Object capParams(Object plain) {
        return PortableNodes.capped(mapper, plain, PARAMS_CAP_CHARS);
    }

    private Built finish(
            NodeCtx c,
            String type,
            String label,
            Map<String, Object> config,
            Level level,
            Outputs outputs) {
        Level effective = level;
        if (level == Level.EXACT && (c.approximate || c.expression)) {
            effective = Level.APPROXIMATE;
        }
        List<String> notes = new ArrayList<>(c.notes);
        if (c.expression) {
            notes.add(
                    "It carried n8n expressions. They were translated where VegaDuta has the same accessor "
                            + "($json, $node[...]) and are otherwise kept as written; n8n expressions are JavaScript, so check them.");
        }
        Built b = new Built();
        b.level = effective;
        b.outputs = outputs;
        b.notes.addAll(notes);
        b.node =
                PortableNodes.node(
                        c.src.id(),
                        type,
                        AgentImportText.truncate(label, 200),
                        position(c.src),
                        config,
                        notes,
                        effective != Level.EXACT);
        return b;
    }

    private Map<String, Object> position(Src s) {
        JsonNode p = s.raw().get("position");
        if (p != null
                && p.isArray()
                && p.size() >= 2
                && p.get(0).isNumber()
                && p.get(1).isNumber()) {
            return PortableNodes.position(p.get(0).asDouble(), p.get(1).asDouble());
        }
        return PortableNodes.position((s.index() % 5) * 260.0, (s.index() / 5) * 160.0);
    }

    // ---- report
    // ------------------------------------------------------------------------------------------

    private void settleReport() {
        for (Built b : built.values()) {
            wf.nodes.add(b.node);
            String name = AgentImportText.inline(String.valueOf(b.node.data().get("label")), 80);
            switch (b.level) {
                case EXACT -> {
                    wf.fidelity.exact++;
                    wf.report.converted(
                            "Node '" + name + "'", "Converted to a " + b.node.type() + " node.");
                }
                case APPROXIMATE -> {
                    wf.fidelity.approximate++;
                    wf.report.needsManual(
                            "Node '" + name + "'",
                            String.join(" ", b.notes),
                            "Open the node on the canvas and check it before activating the workflow.");
                }
                default -> {
                    wf.fidelity.placeholders++;
                    wf.report.unsupported(
                            "Node '" + name + "'",
                            b.reason
                                    + " Kept as an inert pass-through placeholder with its original name, type and parameters "
                                    + "(credentials removed).");
                }
            }
            PlaceholderScanner.scan(b.node.config(), b.node.id(), name, wf.placeholders, Map.of());
        }
    }

    private void workflowLevelNotes(JsonNode root) {
        if (root.path("active").asBoolean(false)) {
            wf.report.warn(
                    "The workflow was active in n8n. It is imported as a DRAFT and is never activated.");
        }
        JsonNode pinData = root.get("pinData");
        if (pinData != null && pinData.isObject() && pinData.size() > 0) {
            wf.report.unsupported(
                    "Pinned test data",
                    "n8n pinned sample data may contain real records, so it is not imported.");
        }
        if (AgentImportJson.obj(root, "settings") != null
                || AgentImportJson.arr(root, "tags") != null
                || root.has("staticData")) {
            wf.report.warn(
                    "Workflow-level n8n settings, tags and static data (timezone, error workflow, execution "
                            + "order) are not imported.");
        }
    }
}
