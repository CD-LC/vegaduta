package com.agenticai.core.agents.importer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a Flowise chatflow / agentflow export ({@code {nodes: [...], edges: [...]}}) to an {@link
 * AgentImportDraft}. Pure.
 *
 * <p>Format verified against Flowise's own marketplace templates and component sources
 * (https://github.com/FlowiseAI/Flowise/tree/main/packages/server/marketplaces and
 * .../packages/components/nodes): top level {@code nodes}/{@code edges}; a node is {@code {id,
 * position, type: "customNode"|"agentFlow"|"stickyNote", data: {id, label, name, type, category,
 * inputs, credential?, ...}}}; {@code data.inputs} holds the configured values and links to other
 * nodes as {@code "{{<nodeId>.data.instance}}"}; classic agents keep the prompt in {@code
 * inputs.systemMessage}, chains in {@code responsePrompt}/{@code systemMessagePrompt}, Sequential
 * Agents in {@code systemMessagePrompt}, and Agentflow v2 agents in {@code agentMessages[{role,
 * content}]} (HTML) with the model in {@code agentModel} + {@code agentModelConfig.modelName}.
 *
 * <p>Credentials: a node may carry {@code data.credential} (a credential id) or API keys inside
 * inputs. Those are never read - {@link AgentImportJson#countCredentialFields} only counts them so
 * the report can say they were not imported.
 */
public final class FlowiseImportMapper {

    private FlowiseImportMapper() {}

    /** Hard cap on nodes; a real flow is tens of nodes, so this only bounds abuse. */
    public static final int MAX_NODES = 500;

    /** Hard cap on edges. */
    public static final int MAX_EDGES = 5000;

    private static final Pattern REF =
            Pattern.compile("^\\{\\{\\s*([^.{}\\s]{1,100})\\.data\\.instance\\s*\\}\\}$");

    private record FNode(
            String id,
            String name,
            String label,
            String category,
            String type,
            JsonNode data,
            JsonNode inputs) {
        boolean is(String n) {
            return name != null && name.equalsIgnoreCase(n);
        }
    }

    /**
     * Text of the {@code flowData} field of a stored chatflow record, or null when this isn't such
     * a wrapper.
     */
    public static String flowDataText(JsonNode root) {
        return AgentImportJson.text(root, "flowData");
    }

    /**
     * @param flow the {@code {nodes, edges}} object
     * @param nameHint optional flow name (from a wrapper record), may be null
     * @param descriptionHint optional flow description, may be null
     * @throws IllegalArgumentException when the payload isn't a flow or exceeds the node/edge caps
     */
    public static AgentImportDraft map(JsonNode flow, String nameHint, String descriptionHint) {
        if (flow == null || !flow.isObject() || AgentImportJson.arr(flow, "nodes") == null) {
            throw new IllegalArgumentException(
                    "This doesn't look like a Flowise export: expected an object with a \"nodes\" list.");
        }
        JsonNode nodesNode = flow.get("nodes");
        if (nodesNode.size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "This flow has "
                            + nodesNode.size()
                            + " nodes; the import limit is "
                            + MAX_NODES
                            + ".");
        }
        JsonNode edgesNode = AgentImportJson.arr(flow, "edges");
        if (edgesNode != null && edgesNode.size() > MAX_EDGES) {
            throw new IllegalArgumentException(
                    "This flow has "
                            + edgesNode.size()
                            + " edges; the import limit is "
                            + MAX_EDGES
                            + ".");
        }

        AgentImportDraft d = new AgentImportDraft();
        AgentImportReport r = d.report;

        List<FNode> nodes = new ArrayList<>();
        Map<String, FNode> byId = new HashMap<>();
        int malformed = 0;
        for (JsonNode n : nodesNode) {
            FNode f = toNode(n);
            if (f == null) {
                malformed++;
                continue;
            }
            nodes.add(f);
            if (f.id() != null) {
                byId.putIfAbsent(f.id(), f);
            }
        }
        if (malformed > 0) {
            r.warn(
                    malformed
                            + " entries in \"nodes\" were not readable node objects and were skipped.");
        }

        Map<String, List<String>> adjacency = adjacency(edgesNode);

        // ---- classify ------------------------------------------------------------------------
        List<FNode> chatModels = new ArrayList<>();
        List<FNode> classicAgents = new ArrayList<>();
        List<FNode> chains = new ArrayList<>();
        List<FNode> flowAgents = new ArrayList<>(); // agentAgentflow / llmAgentflow
        List<FNode> seqAgents = new ArrayList<>(); // seqAgent / seqLLMNode
        List<FNode> tools = new ArrayList<>();
        List<FNode> memory = new ArrayList<>();
        List<FNode> retrieval = new ArrayList<>();
        List<FNode> support = new ArrayList<>(); // embeddings, text splitters
        List<FNode> moderation = new ArrayList<>();
        Map<String, Integer> control = new LinkedHashMap<>();

        for (FNode f : nodes) {
            if (f.is("stickyNote")
                    || f.is("stickyNoteAgentflow")
                    || "stickyNote".equals(f.type())) {
                continue;
            }
            String cat = f.category() == null ? "" : f.category();
            String name = f.name() == null ? "" : f.name();
            if (AgentImportProviders.flowiseProviderHint(name) != null
                    || "Chat Models".equals(cat)) {
                chatModels.add(f);
            } else if (name.equals("agentAgentflow") || name.equals("llmAgentflow")) {
                flowAgents.add(f);
            } else if (name.equals("seqAgent") || name.equals("seqLLMNode")) {
                seqAgents.add(f);
            } else if (name.equals("retrieverAgentflow")) {
                retrieval.add(f);
            } else if ("Agents".equals(cat)) {
                classicAgents.add(f);
            } else if ("Chains".equals(cat)) {
                chains.add(f);
            } else if (cat.startsWith("Tools")) {
                tools.add(f);
            } else if ("Memory".equals(cat)) {
                memory.add(f);
            } else if (cat.equals("Vector Stores")
                    || cat.equals("Retrievers")
                    || cat.equals("Document Loaders")
                    || cat.equals("Record Manager")) {
                retrieval.add(f);
            } else if (cat.equals("Embeddings") || cat.equals("Text Splitters")) {
                support.add(f);
            } else if (cat.equals("Moderation")) {
                moderation.add(f);
            } else if (cat.equals("Prompts")) {
                // read on demand through references; nothing to report on its own
                continue;
            } else if (!name.equals("startAgentflow")
                    && !name.equals("seqStart")
                    && !name.equals("seqEnd")
                    && !name.equals("seqState")) {
                String shown = AgentImportText.inline(name.isEmpty() ? f.label() : name, 60);
                if (!shown.isEmpty()) {
                    control.merge(shown, 1, Integer::sum);
                }
            }
        }

        // ---- primary agent -------------------------------------------------------------------
        FNode primary = null;
        String kind = null;
        if (!classicAgents.isEmpty()) {
            primary = classicAgents.get(0);
            kind = "classic";
        } else if (!flowAgents.isEmpty()) {
            primary = firstInFlowOrder(flowAgents, nodes, adjacency, "startAgentflow");
            kind = "agentflow";
        } else if (!seqAgents.isEmpty()) {
            primary = firstOfName(seqAgents, "seqAgent");
            if (primary == null) {
                primary = seqAgents.get(0);
            }
            kind = "seq";
        } else if (!chains.isEmpty()) {
            primary = chains.get(0);
            kind = "chain";
        }

        d.name = nameHint;
        d.description =
                descriptionHint != null
                        ? descriptionHint
                        : AgentImportJson.text(flow, "description");
        if (d.description != null) {
            r.warn(
                    "The flow description was kept for the preview only: agents here have no description field.");
        }

        if (primary == null) {
            r.needsManual(
                    "Agent",
                    "No agent, chain or LLM node was found in this flow.",
                    "Write the instructions and pick a model for the agent yourself.");
        } else {
            readPrimary(primary, kind, byId, chatModels, nodes, d);
            int extra = 0;
            List<FNode> pool =
                    switch (kind) {
                        case "agentflow" -> flowAgents;
                        case "seq" -> seqAgents;
                        case "classic" -> classicAgents;
                        default -> chains;
                    };
            for (FNode other : pool) {
                if (other == primary) {
                    continue;
                }
                if (extra++ >= 20) {
                    r.warn("Only the first 20 additional agents were listed in this report.");
                    break;
                }
                String label =
                        other.is("seqAgent")
                                ? AgentImportJson.text(other.inputs(), "agentName")
                                : other.is("seqLLMNode")
                                        ? AgentImportJson.text(other.inputs(), "llmNodeName")
                                        : other.label();
                r.needsManual(
                        "Additional agent '"
                                + AgentImportText.inline(label == null ? other.name() : label, 64)
                                + "'",
                        "A flow with several agents maps to several platform agents; only the first was imported.",
                        AgentImportDraft.HINT_DELEGATION);
                d.suggestedTools.add(AgentImportDraft.TOOL_DELEGATE);
            }
        }

        // ---- the rest of the flow --------------------------------------------------------------
        mapTools(tools, flowAgents, d);
        mapMemory(memory, r);
        mapRetrieval(retrieval, support, flowAgents, d);
        for (FNode m : moderation) {
            r.needsManual(
                    "Moderation '"
                            + AgentImportText.inline(m.label() == null ? m.name() : m.label(), 64)
                            + "'",
                    "Flowise moderation nodes are not converted.",
                    "Turn on the platform's content guardrails for this agent instead.");
        }
        for (Map.Entry<String, Integer> e : control.entrySet()) {
            r.unsupported(
                    "Flow node '"
                            + e.getKey()
                            + "'"
                            + (e.getValue() > 1 ? " (x" + e.getValue() + ")" : ""),
                    "Orchestration and utility nodes (branching, loops, shared state, custom functions) are not converted; "
                            + "consider rebuilding that logic as a platform workflow.");
        }
        if (chatModels.size() > 1) {
            r.warn(
                    "The flow uses "
                            + chatModels.size()
                            + " chat models; only the one the main agent uses was imported.");
        }

        int creds = AgentImportJson.countCredentialFields(flow);
        if (creds > 0) {
            r.needsManual(
                    "Credentials (" + creds + " fields)",
                    "The export contained credential references or API keys. They were not imported - they are never "
                            + "read, stored or shown.",
                    "Add your own provider key on the Providers page and connect tool integrations again.");
        }
        return d;
    }

    // ---- primary agent extraction --------------------------------------------------------------

    private static void readPrimary(
            FNode p,
            String kind,
            Map<String, FNode> byId,
            List<FNode> chatModels,
            List<FNode> allNodes,
            AgentImportDraft d) {
        AgentImportReport r = d.report;
        JsonNode in = p.inputs();
        String instructions = null;
        JsonNode modelConfig = null;
        String modelNodeName = null;

        switch (kind) {
            case "agentflow" -> {
                boolean isAgent = p.is("agentAgentflow");
                String messagesField = isAgent ? "agentMessages" : "llmMessages";
                instructions = systemFromMessages(in, messagesField, r);
                modelNodeName = AgentImportJson.text(in, isAgent ? "agentModel" : "llmModel");
                modelConfig =
                        AgentImportJson.obj(in, isAgent ? "agentModelConfig" : "llmModelConfig");
                if (d.name == null) {
                    d.name = p.label();
                }
                if (AgentImportJson.text(in, isAgent ? "agentUserMessage" : "llmUserMessage")
                        != null) {
                    r.warn(
                            "The step's user message template was not imported (it is a runtime input in Flowise).");
                }
            }
            case "seq" -> {
                instructions = AgentImportJson.text(in, "systemMessagePrompt");
                if (d.name == null) {
                    d.name =
                            p.is("seqAgent")
                                    ? AgentImportJson.text(in, "agentName")
                                    : AgentImportJson.text(in, "llmNodeName");
                }
                if (AgentImportJson.text(in, "humanMessagePrompt") != null) {
                    r.warn("The step's human message template was not imported.");
                }
            }
            case "classic" ->
                    instructions =
                            firstNonNull(
                                    AgentImportJson.text(in, "systemMessage"),
                                    fromReferencedPrompt(in, byId));
            default -> {
                instructions =
                        firstNonNull(
                                AgentImportJson.text(in, "systemMessagePrompt"),
                                AgentImportJson.text(in, "systemMessage"),
                                AgentImportJson.text(in, "responsePrompt"),
                                fromReferencedPrompt(in, byId));
                if (AgentImportJson.text(in, "rephrasePrompt") != null) {
                    r.warn("The chain's follow-up-question rephrase prompt was not imported.");
                }
            }
        }
        d.instructions = instructions;
        if (instructions != null) {
            r.converted(
                    "Instructions",
                    "Copied the " + describeKind(kind) + "'s prompt as the system prompt.");
        } else {
            r.needsManual(
                    "Instructions",
                    "No prompt text was found on the main agent node.",
                    "Write the agent's instructions.");
        }

        // model + sampling
        if (modelConfig != null) {
            d.providerHint = AgentImportProviders.flowiseProviderHint(modelNodeName);
            d.modelHint =
                    firstNonNull(
                            AgentImportJson.text(modelConfig, "customModelName"),
                            AgentImportJson.text(modelConfig, "modelName"),
                            AgentImportJson.text(modelConfig, "model"));
            d.temperature = AgentImportJson.number(modelConfig, "temperature");
            d.topP = AgentImportJson.number(modelConfig, "topP");
            if (d.providerHint == null && modelNodeName != null) {
                r.warn(
                        "The chat model type '"
                                + AgentImportText.inline(modelNodeName, 40)
                                + "' is not recognised.");
            }
            return;
        }
        FNode model = resolveChatModel(in, byId, chatModels, allNodes, kind);
        if (model == null) {
            r.needsManual(
                    "Model",
                    "No chat model node was found for the main agent.",
                    "Pick a model for the agent.");
            return;
        }
        d.providerHint = AgentImportProviders.flowiseProviderHint(model.name());
        d.modelHint =
                firstNonNull(
                        AgentImportJson.text(model.inputs(), "customModelName"),
                        AgentImportJson.text(model.inputs(), "modelName"),
                        AgentImportJson.text(model.inputs(), "model"));
        d.temperature = AgentImportJson.number(model.inputs(), "temperature");
        d.topP = AgentImportJson.number(model.inputs(), "topP");
        if (d.providerHint == null) {
            r.warn(
                    "The chat model type '"
                            + AgentImportText.inline(model.name(), 40)
                            + "' is not recognised.");
        }
    }

    private static String describeKind(String kind) {
        return switch (kind) {
            case "agentflow" -> "Agentflow step";
            case "seq" -> "Sequential Agents step";
            case "classic" -> "agent";
            default -> "chain";
        };
    }

    private static FNode resolveChatModel(
            JsonNode inputs,
            Map<String, FNode> byId,
            List<FNode> chatModels,
            List<FNode> allNodes,
            String kind) {
        FNode viaRef = referenced(inputs.get("model"), byId);
        if (viaRef != null && isChatModel(viaRef)) {
            return viaRef;
        }
        if ("seq".equals(kind)) {
            for (FNode n : allNodes) {
                if (n.is("seqStart")) {
                    FNode m = referenced(n.inputs().get("model"), byId);
                    if (m != null && isChatModel(m)) {
                        return m;
                    }
                }
            }
        }
        return chatModels.isEmpty() ? null : chatModels.get(0);
    }

    private static boolean isChatModel(FNode n) {
        return "Chat Models".equals(n.category())
                || AgentImportProviders.flowiseProviderHint(n.name()) != null;
    }

    private static String systemFromMessages(JsonNode inputs, String field, AgentImportReport r) {
        StringBuilder sb = new StringBuilder();
        int other = 0;
        for (JsonNode m : AgentImportJson.elements(inputs, field)) {
            String role = AgentImportJson.text(m, "role");
            String content = AgentImportJson.text(m, "content");
            if (content == null) {
                continue;
            }
            if (role != null
                    && (role.equalsIgnoreCase("system") || role.equalsIgnoreCase("developer"))) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(AgentImportText.htmlToText(content));
            } else {
                other++;
            }
        }
        if (other > 0) {
            r.warn(other + " preset non-system messages on the step were not imported.");
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Follows a {@code chatPromptTemplate}/{@code prompt} reference to a Prompts node and reads its
     * text.
     */
    private static String fromReferencedPrompt(JsonNode inputs, Map<String, FNode> byId) {
        if (inputs == null) {
            return null;
        }
        for (String key : new String[] {"chatPromptTemplate", "prompt"}) {
            FNode p = referenced(inputs.get(key), byId);
            if (p != null) {
                String t =
                        firstNonNull(
                                AgentImportJson.text(p.inputs(), "systemMessagePrompt"),
                                AgentImportJson.text(p.inputs(), "template"));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    // ---- tools / memory / retrieval -----------------------------------------------------------

    private static void mapTools(List<FNode> tools, List<FNode> flowAgents, AgentImportDraft d) {
        AgentImportReport r = d.report;
        for (FNode t : tools) {
            mapOneTool(t.name(), t.label(), d);
        }
        // Agentflow v2 keeps tools inline: agentTools: [{agentSelectedTool: "<tool name>", ...}]
        for (FNode a : flowAgents) {
            for (JsonNode sel : AgentImportJson.elements(a.inputs(), "agentTools")) {
                String toolName = AgentImportJson.text(sel, "agentSelectedTool");
                if (toolName != null) {
                    mapOneTool(toolName, toolName, d);
                }
            }
        }
        if (tools.size() > 50) {
            r.warn("The flow has " + tools.size() + " tool nodes; every one was reported.");
        }
    }

    private static final Set<String> SEARCH_TOOLS =
            Set.of(
                    "serpapi",
                    "googlecustomsearch",
                    "bravesearchapi",
                    "tavilyapi",
                    "exasearch",
                    "searxng",
                    "serper",
                    "searchapi");

    private static void mapOneTool(String name, String label, AgentImportDraft d) {
        AgentImportReport r = d.report;
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String shown =
                AgentImportText.inline(
                        label != null ? label : name == null ? "(unnamed)" : name, 64);
        if (SEARCH_TOOLS.contains(key)) {
            d.suggestedTools.add(AgentImportDraft.TOOL_WEB_SEARCH);
            r.converted(
                    "Tool '" + shown + "'",
                    "Suggested the platform's web_search tool. The search API key was not imported.");
        } else if (key.equals("webbrowser") || key.equals("webscrapertool")) {
            d.suggestedTools.add(AgentImportDraft.TOOL_FETCH_PAGE);
            r.converted("Tool '" + shown + "'", "Suggested the platform's fetch_web_page tool.");
        } else if (key.startsWith("requests")) {
            d.suggestedTools.add(AgentImportDraft.TOOL_HTTP_REQUEST);
            r.needsManual(
                    "Tool '" + shown + "'",
                    "Generic HTTP requests map to the http_request tool, which needs its own allow-list and review.",
                    "Enable the http_request tool deliberately and restrict what it may call.");
        } else if (key.startsWith("codeinterpreter") || key.contains("e2b")) {
            d.suggestedCapabilities.add(AgentImportDraft.CAP_CODE_EXECUTION);
            d.suggestedTools.add(AgentImportDraft.TOOL_RUN_CODE);
            r.needsManual(
                    "Tool '" + shown + "'",
                    "Code interpreter credentials and templates are not imported.",
                    AgentImportDraft.HINT_CODE_EXEC);
        } else if (key.equals("calculator")) {
            r.unsupported(
                    "Tool '" + shown + "'",
                    "No built-in equivalent; models handle simple arithmetic and Code execution covers the rest.");
        } else if (key.equals("mcp") || key.contains("mcp")) {
            r.needsManual(
                    "Tool '" + shown + "'",
                    "MCP server configuration and credentials are not imported.",
                    "Add the MCP server from the MCP registry or your Integrations, then attach it to the agent.");
        } else if (key.equals("agentastool")
                || key.equals("chatflowtool")
                || key.equals("chaintool")) {
            d.suggestedTools.add(AgentImportDraft.TOOL_DELEGATE);
            r.needsManual(
                    "Tool '" + shown + "'",
                    "Calls another flow or agent from this one.",
                    AgentImportDraft.HINT_DELEGATION);
        } else {
            r.needsManual(
                    "Tool '" + shown + "'",
                    "No automatic equivalent exists for this Flowise tool.",
                    AgentImportDraft.HINT_CUSTOM_TOOL);
        }
    }

    private static void mapMemory(List<FNode> memory, AgentImportReport r) {
        for (FNode m : memory) {
            String key = m.name() == null ? "" : m.name().toLowerCase(Locale.ROOT);
            String shown = AgentImportText.inline(m.label() != null ? m.label() : m.name(), 64);
            if (key.equals("buffermemory")
                    || key.equals("bufferwindowmemory")
                    || key.startsWith("conversationsummary")) {
                r.converted(
                        "Memory '" + shown + "'",
                        "Conversation memory is built into every agent; nothing to configure.");
            } else {
                r.unsupported(
                        "Memory '" + shown + "'",
                        "External or custom memory stores are not imported; the platform's own conversation memory is used.");
            }
        }
    }

    private static void mapRetrieval(
            List<FNode> retrieval,
            List<FNode> support,
            List<FNode> flowAgents,
            AgentImportDraft d) {
        AgentImportReport r = d.report;
        List<String> labels = new ArrayList<>();
        for (FNode f : retrieval) {
            if (labels.size() < 8) {
                labels.add(AgentImportText.inline(f.label() != null ? f.label() : f.name(), 40));
            }
        }
        boolean agentStores = false;
        for (FNode a : flowAgents) {
            if (AgentImportJson.hasValue(a.inputs(), "agentKnowledgeDocumentStores")) {
                agentStores = true;
            }
        }
        if (!retrieval.isEmpty() || agentStores) {
            d.suggestedTools.add(AgentImportDraft.TOOL_KNOWLEDGE_SEARCH);
            r.needsManual(
                    "Knowledge / retrieval"
                            + (labels.isEmpty() ? "" : ": " + String.join(", ", labels)),
                    "Vector stores and document stores live in your Flowise instance; their contents are not part of the export.",
                    AgentImportDraft.HINT_KNOWLEDGE);
        }
        if (!support.isEmpty()) {
            r.unsupported(
                    "Embedding / text-splitter nodes (" + support.size() + ")",
                    "The platform's knowledge base chooses its own embedding and chunking settings.");
        }
    }

    // ---- graph helpers --------------------------------------------------------------------------

    private static FNode toNode(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        JsonNode data = AgentImportJson.obj(n, "data");
        if (data == null) {
            return null;
        }
        String id = firstNonNull(AgentImportJson.text(n, "id"), AgentImportJson.text(data, "id"));
        JsonNode inputs = AgentImportJson.obj(data, "inputs");
        return new FNode(
                id == null ? null : AgentImportText.truncate(id, 200),
                AgentImportJson.text(data, "name"),
                AgentImportJson.text(data, "label"),
                AgentImportJson.text(data, "category"),
                AgentImportJson.text(n, "type"),
                data,
                inputs == null
                        ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                        : inputs);
    }

    private static Map<String, List<String>> adjacency(JsonNode edgesNode) {
        Map<String, List<String>> adj = new HashMap<>();
        if (edgesNode == null) {
            return adj;
        }
        for (JsonNode e : edgesNode) {
            String s = AgentImportJson.text(e, "source");
            String t = AgentImportJson.text(e, "target");
            if (s != null && t != null) {
                adj.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
            }
        }
        return adj;
    }

    /**
     * The candidate reached first by walking edges from the flow's start node (array order if
     * none/unreached).
     */
    private static FNode firstInFlowOrder(
            List<FNode> candidates,
            List<FNode> all,
            Map<String, List<String>> adj,
            String startName) {
        Set<String> candidateIds = new HashSet<>();
        for (FNode c : candidates) {
            if (c.id() != null) {
                candidateIds.add(c.id());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (FNode n : all) {
            if (n.is(startName) && n.id() != null) {
                queue.add(n.id());
            }
        }
        Map<String, FNode> byId = new HashMap<>();
        for (FNode c : candidates) {
            if (c.id() != null) {
                byId.putIfAbsent(c.id(), c);
            }
        }
        FNode firstAgent = null;
        FNode firstAny = null;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            FNode hit = byId.get(id);
            if (hit != null) {
                if (hit.is("agentAgentflow") && firstAgent == null) {
                    firstAgent = hit;
                }
                if (firstAny == null) {
                    firstAny = hit;
                }
            }
            for (String next : adj.getOrDefault(id, List.of())) {
                queue.add(next);
            }
        }
        if (firstAgent != null) {
            return firstAgent;
        }
        if (firstAny != null) {
            return firstAny;
        }
        return candidates.get(0);
    }

    private static FNode firstOfName(List<FNode> list, String name) {
        for (FNode f : list) {
            if (f.is(name)) {
                return f;
            }
        }
        return null;
    }

    /**
     * The node a {@code "{{id.data.instance}}"} input points at (first element if the input is a
     * list).
     */
    private static FNode referenced(JsonNode input, Map<String, FNode> byId) {
        if (input == null) {
            return null;
        }
        JsonNode v = input.isArray() ? (input.size() > 0 ? input.get(0) : null) : input;
        String s = AgentImportJson.textOf(v);
        if (s == null || s.length() > 300) {
            return null;
        }
        Matcher m = REF.matcher(s);
        return m.matches() ? byId.get(m.group(1)) : null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
