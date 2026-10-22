package com.agenticai.core.agents.importer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps an OpenAI Agents SDK style agent config (the keyword arguments of {@code Agent(...)} written
 * as a JSON object) to an {@link AgentImportDraft}. Pure.
 *
 * <p>What is and is not verified, so nobody over-trusts this mapper:
 *
 * <ul>
 *   <li>OpenAI documents that an Agent Builder workflow is exported as Agents SDK <em>code</em>
 *       (TypeScript or Python), "a scaffold ... not a finished, behavior-equivalent port":
 *       https://developers.openai.com/api/docs/guides/agent-builder/migrate-from-agent-builder .
 *       There is no official JSON export, so pasted source code is NOT parsed (the service rejects
 *       non-JSON text with an explanatory 400 instead of guessing at code).
 *   <li>The field names come from the Agents SDK {@code Agent} dataclass:
 *       https://github.com/openai/openai-agents-python/blob/main/src/agents/agent.py ({@code name},
 *       {@code handoff_description}, {@code tools}, {@code mcp_servers}, {@code instructions},
 *       {@code prompt}, {@code handoffs}, {@code model}, {@code model_settings}, {@code
 *       input_guardrails}, {@code output_guardrails}, {@code output_type}) and {@code
 *       ModelSettings}
 *       (https://github.com/openai/openai-agents-python/blob/main/src/agents/model_settings.py:
 *       {@code temperature}, {@code top_p}, ...). camelCase spellings (the TypeScript SDK style)
 *       are accepted for the same properties; that spelling mapping is by convention, not from a
 *       spec.
 * </ul>
 *
 * Everything not on that list is reported as unsupported rather than guessed.
 */
public final class OpenAiAgentsImportMapper {

    private OpenAiAgentsImportMapper() {}

    static final int MAX_TOOLS = 128;
    static final int MAX_HANDOFFS = 50;

    public static AgentImportDraft map(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("An OpenAI agent config must be a JSON object.");
        }
        AgentImportDraft d = new AgentImportDraft();
        AgentImportReport r = d.report;

        d.name = AgentImportJson.text(root, "name");
        d.description =
                AgentImportJson.firstText(root, "handoff_description", "handoffDescription");
        if (d.name != null) {
            r.converted("Name", "Used the agent's name.");
        } else {
            r.warn("The agent config has no name; a default name was used.");
        }
        if (d.description != null) {
            r.warn(
                    "handoff_description was kept for the preview only: agents here have no description field.");
        }

        mapInstructions(root, d);
        mapModel(root, d);
        mapSettings(root, d);
        mapTools(root, d);
        mapHandoffs(root, d);
        mapMcpServers(root, r);
        mapGuardrailsAndOutput(root, r);

        if (AgentImportJson.hasValue(root, "prompt")) {
            r.unsupported(
                    "prompt",
                    "A stored OpenAI prompt reference; its text lives on OpenAI and is not in this payload.");
        }
        List<String> codeOnly = new ArrayList<>();
        for (String k :
                new String[] {
                    "hooks",
                    "tool_use_behavior",
                    "toolUseBehavior",
                    "reset_tool_choice",
                    "resetToolChoice",
                    "mcp_config",
                    "mcpConfig"
                }) {
            if (AgentImportJson.hasValue(root, k)) {
                codeOnly.add(k);
            }
        }
        if (!codeOnly.isEmpty()) {
            r.unsupported(
                    "Runtime behaviour settings (" + String.join(", ", codeOnly) + ")",
                    "These configure the Agents SDK runtime and have no equivalent here.");
        }
        return d;
    }

    private static void mapInstructions(JsonNode root, AgentImportDraft d) {
        AgentImportReport r = d.report;
        d.instructions = AgentImportJson.text(root, "instructions");
        if (d.instructions != null) {
            r.converted("Instructions", "Copied as the agent's system prompt.");
        } else if (AgentImportJson.hasValue(root, "instructions")) {
            r.unsupported(
                    "Instructions",
                    "Dynamic or non-text instructions cannot be imported; only a plain string can.");
        }
    }

    private static void mapModel(JsonNode root, AgentImportDraft d) {
        AgentImportReport r = d.report;
        JsonNode m = root.get("model");
        String model = AgentImportJson.textOf(m);
        if (m != null && !m.isNull() && model == null) {
            r.unsupported(
                    "Model",
                    "The model is not a plain model-name string (a custom Model object cannot be imported).");
        }
        d.providerHint = "openai";
        if (model == null) {
            r.needsManual(
                    "Model",
                    "The config names no model (the SDK would use its own default).",
                    "Pick a model for the agent.");
            return;
        }
        // The SDK accepts "provider/model" for non-OpenAI models, optionally behind a "litellm/"
        // prefix.
        String[] parts = model.split("/");
        if (parts.length == 0) {
            d.modelHint = model;
            return;
        }
        int i = 0;
        if (parts.length > 1
                && ("litellm".equalsIgnoreCase(parts[0]) || "any-llm".equalsIgnoreCase(parts[0]))) {
            i = 1;
        }
        if (parts.length - i >= 2) {
            d.providerHint = parts[i].toLowerCase(Locale.ROOT);
            d.modelHint =
                    String.join("/", java.util.Arrays.copyOfRange(parts, i + 1, parts.length));
        } else {
            d.modelHint = parts[parts.length - 1];
        }
    }

    private static void mapSettings(JsonNode root, AgentImportDraft d) {
        JsonNode ms = AgentImportJson.obj(root, "model_settings");
        if (ms == null) {
            ms = AgentImportJson.obj(root, "modelSettings");
        }
        if (ms == null) {
            return;
        }
        final JsonNode settings = ms;
        d.temperature = AgentImportJson.number(settings, "temperature");
        d.topP = AgentImportJson.firstNumber(settings, "top_p", "topP");
        if (d.temperature != null || d.topP != null) {
            d.report.converted(
                    "Sampling",
                    "Carried over temperature" + (d.topP != null ? " and top_p" : "") + ".");
        }
        List<String> other = new ArrayList<>();
        settings.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!k.equals("temperature")
                                    && !k.equals("top_p")
                                    && !k.equals("topP")
                                    && AgentImportJson.hasValue(settings, k)
                                    && other.size() < 10) {
                                other.add(AgentImportText.inline(k, 40));
                            }
                        });
        if (!other.isEmpty()) {
            d.report.unsupported(
                    "Other model settings (" + String.join(", ", other) + ")",
                    "Only temperature and top_p are carried over.");
        }
    }

    private static void mapTools(JsonNode root, AgentImportDraft d) {
        AgentImportReport r = d.report;
        List<JsonNode> tools = AgentImportJson.elements(root, "tools");
        if (tools.size() > MAX_TOOLS) {
            r.warn(
                    "The config lists "
                            + tools.size()
                            + " tools; only the first "
                            + MAX_TOOLS
                            + " were read.");
        }
        int n = 0;
        for (JsonNode tool : tools) {
            if (n++ >= MAX_TOOLS) {
                break;
            }
            String bareName = AgentImportJson.textOf(tool);
            if (bareName != null) {
                functionNeedsManual(bareName, null, r);
                continue;
            }
            if (!tool.isObject()) {
                r.unsupported("A tool entry", "It is neither an object nor a name.");
                continue;
            }
            String type = AgentImportJson.text(tool, "type");
            String key = type == null ? "function" : normaliseToolType(type);
            switch (key) {
                case "websearch", "websearchpreview" -> {
                    d.suggestedTools.add(AgentImportDraft.TOOL_WEB_SEARCH);
                    r.converted(
                            "Web search tool",
                            "Suggested the platform's web_search tool; enable it on the agent.");
                }
                case "filesearch" -> {
                    d.suggestedTools.add(AgentImportDraft.TOOL_KNOWLEDGE_SEARCH);
                    r.needsManual(
                            "file_search tool",
                            "OpenAI-hosted vector stores cannot be exported.",
                            AgentImportDraft.HINT_KNOWLEDGE);
                }
                case "codeinterpreter" -> {
                    d.suggestedCapabilities.add(AgentImportDraft.CAP_CODE_EXECUTION);
                    d.suggestedTools.add(AgentImportDraft.TOOL_RUN_CODE);
                    r.needsManual(
                            "code_interpreter tool",
                            "OpenAI's hosted sandbox is not available here.",
                            AgentImportDraft.HINT_CODE_EXEC);
                }
                case "imagegeneration" -> {
                    d.suggestedTools.add(AgentImportDraft.TOOL_GENERATE_IMAGE);
                    r.converted(
                            "Image generation tool",
                            "Suggested the platform's generate_image tool.");
                }
                case "mcp", "hostedmcp" -> {
                    String label = AgentImportJson.firstText(tool, "server_label", "serverLabel");
                    r.needsManual(
                            "Hosted MCP server '"
                                    + AgentImportText.inline(
                                            label == null ? "(unnamed)" : label, 64)
                                    + "'",
                            "MCP connections, URLs and credentials are not imported.",
                            "Add the MCP server from the MCP registry or your Integrations, then attach it to the agent.");
                }
                case "computer",
                        "computeruse",
                        "computerusepreview",
                        "localshell",
                        "shell",
                        "applypatch" ->
                        r.unsupported(
                                "Tool of type '"
                                        + AgentImportText.inline(type == null ? key : type, 40)
                                        + "'",
                                "Computer-use and shell tools run on OpenAI-side or local runtimes and have no equivalent here.");
                case "function", "custom" ->
                        functionNeedsManual(
                                AgentImportJson.text(tool, "name"),
                                AgentImportJson.text(tool, "description"),
                                r);
                default ->
                        r.unsupported(
                                "Tool of type '"
                                        + AgentImportText.inline(type == null ? key : type, 40)
                                        + "'",
                                "Not a tool type this importer recognises.");
            }
        }
    }

    private static void functionNeedsManual(String name, String desc, AgentImportReport r) {
        r.needsManual(
                "Function tool '"
                        + AgentImportText.inline(name == null ? "(unnamed)" : name, 64)
                        + "'",
                "Function tools run in your own code, so they cannot be created automatically.",
                AgentImportDraft.HINT_CUSTOM_TOOL
                        + (desc == null
                                ? ""
                                : " Original description: " + AgentImportText.inline(desc, 160)));
    }

    /**
     * {@code WebSearchTool} / {@code web_search} / {@code web-search-preview} -> {@code
     * websearch...}.
     */
    static String normaliseToolType(String type) {
        String s = type.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (s.endsWith("tool") && s.length() > 4) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }

    private static void mapHandoffs(JsonNode root, AgentImportDraft d) {
        AgentImportReport r = d.report;
        List<JsonNode> handoffs = AgentImportJson.elements(root, "handoffs");
        if (handoffs.isEmpty()) {
            return;
        }
        d.suggestedTools.add(AgentImportDraft.TOOL_DELEGATE);
        if (handoffs.size() > MAX_HANDOFFS) {
            r.warn(
                    "The config lists "
                            + handoffs.size()
                            + " handoffs; only the first "
                            + MAX_HANDOFFS
                            + " were read.");
        }
        int n = 0;
        for (JsonNode h : handoffs) {
            if (n++ >= MAX_HANDOFFS) {
                break;
            }
            String name = AgentImportJson.textOf(h);
            boolean hasOwnInstructions = false;
            if (name == null && h.isObject()) {
                name =
                        AgentImportJson.firstText(
                                h,
                                "name",
                                "agent_name",
                                "agentName",
                                "tool_name_override",
                                "toolNameOverride");
                hasOwnInstructions = AgentImportJson.text(h, "instructions") != null;
            }
            r.needsManual(
                    "Handoff to '"
                            + AgentImportText.inline(name == null ? "(unnamed agent)" : name, 64)
                            + "'",
                    "Handoffs between agents are not converted automatically.",
                    hasOwnInstructions
                            ? "Import that agent's own config separately, then link it with agent delegation."
                            : AgentImportDraft.HINT_DELEGATION);
        }
    }

    private static void mapMcpServers(JsonNode root, AgentImportReport r) {
        List<JsonNode> servers = AgentImportJson.elements(root, "mcp_servers");
        if (servers.isEmpty()) {
            servers = AgentImportJson.elements(root, "mcpServers");
        }
        int n = 0;
        for (JsonNode s : servers) {
            if (n++ >= 20) {
                r.warn("Only the first 20 MCP servers were listed in this report.");
                break;
            }
            String name = AgentImportJson.textOf(s);
            if (name == null && s.isObject()) {
                name = AgentImportJson.firstText(s, "name", "server_label", "serverLabel");
            }
            r.needsManual(
                    "MCP server '"
                            + AgentImportText.inline(name == null ? "(unnamed)" : name, 64)
                            + "'",
                    "MCP connections and credentials are not imported.",
                    "Add the MCP server from the MCP registry or your Integrations, then attach it to the agent.");
        }
    }

    private static void mapGuardrailsAndOutput(JsonNode root, AgentImportReport r) {
        int guardrails =
                AgentImportJson.elements(root, "input_guardrails").size()
                        + AgentImportJson.elements(root, "inputGuardrails").size()
                        + AgentImportJson.elements(root, "output_guardrails").size()
                        + AgentImportJson.elements(root, "outputGuardrails").size();
        if (guardrails > 0) {
            r.needsManual(
                    "Guardrails (" + guardrails + ")",
                    "SDK guardrails are code functions, so their logic cannot be converted.",
                    "Turn on the platform's built-in content guardrails for this agent instead.");
        }
        if (AgentImportJson.hasValue(root, "output_type")
                || AgentImportJson.hasValue(root, "outputType")) {
            r.needsManual(
                    "Structured output (output_type)",
                    "Typed / structured output is not carried over.",
                    "Describe the required output format in the instructions.");
        }
    }
}
