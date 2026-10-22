package com.agenticai.core.agents.importer;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Maps an OpenAI Assistants API {@code assistant} object to an {@link AgentImportDraft}. Pure: JSON
 * in, draft (with its report) out; no Spring, no I/O.
 *
 * <p>Field set verified against the official OpenAI Python SDK model generated from the OpenAPI
 * spec: https://github.com/openai/openai-python/blob/main/src/openai/types/beta/assistant.py (id,
 * object, created_at, name, description, model, instructions, tools, tool_resources, metadata,
 * temperature, top_p, response_format) and its tool types function_tool.py / file_search_tool.py /
 * code_interpreter_tool.py (tool {@code type} is {@code code_interpreter}, {@code file_search} or
 * {@code function}; a function tool carries {@code function: {name, description, parameters,
 * strict}}).
 */
public final class OpenAiAssistantImportMapper {

    private OpenAiAssistantImportMapper() {}

    /**
     * The Assistants API allows at most 128 tools per assistant; more than that is not a real
     * assistant.
     */
    static final int MAX_TOOLS = 128;

    public static AgentImportDraft map(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "An OpenAI assistant payload must be a JSON object.");
        }
        AgentImportDraft d = new AgentImportDraft();
        AgentImportReport r = d.report;

        d.name = AgentImportJson.text(root, "name");
        d.description = AgentImportJson.text(root, "description");
        if (d.name != null) {
            r.converted("Name", "Used the assistant's name.");
        } else {
            r.warn("The assistant has no name; a default name was used.");
        }

        if (AgentImportJson.hasValue(root, "instructions")
                && AgentImportJson.text(root, "instructions") == null) {
            r.unsupported(
                    "Instructions", "The instructions field is not text, so it was not imported.");
        }
        d.instructions = AgentImportJson.text(root, "instructions");
        if (d.instructions != null) {
            r.converted(
                    "Instructions",
                    "Copied the assistant's instructions as the agent's system prompt.");
        }

        String model = AgentImportJson.text(root, "model");
        d.providerHint = "openai";
        d.modelHint = model;
        if (model == null) {
            r.needsManual("Model", "The assistant names no model.", "Pick a model for the agent.");
        }

        d.temperature = AgentImportJson.number(root, "temperature");
        d.topP = AgentImportJson.number(root, "top_p");
        if (d.temperature != null || d.topP != null) {
            r.converted(
                    "Sampling",
                    "Carried over temperature" + (d.topP != null ? " and top_p" : "") + ".");
        }

        mapTools(root, d);
        mapResponseFormat(root, r);

        List<String> metaKeys = AgentImportJson.fieldNames(root, "metadata");
        if (!metaKeys.isEmpty()) {
            r.unsupported(
                    "Metadata (" + metaKeys.size() + " keys)",
                    "Free-form key/value pairs; agents here have no equivalent field.");
        }
        return d;
    }

    private static void mapTools(JsonNode root, AgentImportDraft d) {
        AgentImportReport r = d.report;
        JsonNode toolsNode = root.get("tools");
        if (toolsNode != null && !toolsNode.isNull() && !toolsNode.isArray()) {
            r.unsupported("Tools", "The tools field is not a list, so no tools were read.");
            return;
        }
        List<JsonNode> tools = AgentImportJson.elements(root, "tools");
        if (tools.size() > MAX_TOOLS) {
            r.warn(
                    "The payload lists "
                            + tools.size()
                            + " tools; only the first "
                            + MAX_TOOLS
                            + " were read (the Assistants API itself allows at most "
                            + MAX_TOOLS
                            + ").");
        }
        int vectorStoreCount = countIds(root, "file_search", "vector_store_ids");
        int codeFileCount = countIds(root, "code_interpreter", "file_ids");
        int seen = 0;
        for (JsonNode tool : tools) {
            if (seen++ >= MAX_TOOLS) {
                break;
            }
            String type = AgentImportJson.text(tool, "type");
            if (type == null) {
                r.unsupported("A tool entry", "It has no readable type.");
                continue;
            }
            switch (type) {
                case "code_interpreter" -> {
                    d.suggestedCapabilities.add(AgentImportDraft.CAP_CODE_EXECUTION);
                    d.suggestedTools.add(AgentImportDraft.TOOL_RUN_CODE);
                    r.needsManual(
                            "code_interpreter tool",
                            "OpenAI's hosted sandbox is not available here"
                                    + (codeFileCount > 0
                                            ? "; "
                                                    + codeFileCount
                                                    + " files attached to it are not transferred."
                                            : "."),
                            AgentImportDraft.HINT_CODE_EXEC);
                }
                case "file_search" -> {
                    d.suggestedTools.add(AgentImportDraft.TOOL_KNOWLEDGE_SEARCH);
                    r.needsManual(
                            "file_search tool",
                            "OpenAI-hosted vector stores cannot be exported"
                                    + (vectorStoreCount > 0
                                            ? " ("
                                                    + vectorStoreCount
                                                    + " attached); the files inside are not transferred."
                                            : "."),
                            AgentImportDraft.HINT_KNOWLEDGE);
                }
                case "function" -> mapFunction(tool, r);
                default ->
                        r.unsupported(
                                "Tool of type '" + AgentImportText.inline(type, 40) + "'",
                                "Not a tool type this importer recognises.");
            }
        }
    }

    private static void mapFunction(JsonNode tool, AgentImportReport r) {
        JsonNode fn = AgentImportJson.obj(tool, "function");
        String name = fn == null ? null : AgentImportJson.text(fn, "name");
        String desc = fn == null ? null : AgentImportJson.text(fn, "description");
        String label =
                "Function tool '"
                        + AgentImportText.inline(name == null ? "(unnamed)" : name, 64)
                        + "'";
        String hint =
                AgentImportDraft.HINT_CUSTOM_TOOL
                        + (desc == null
                                ? ""
                                : " Original description: " + AgentImportText.inline(desc, 160));
        r.needsManual(
                label,
                "Function tools run in your own application, so they cannot be created automatically.",
                hint);
    }

    private static void mapResponseFormat(JsonNode root, AgentImportReport r) {
        JsonNode rf = root.get("response_format");
        if (rf == null || rf.isNull()) {
            return;
        }
        String type = rf.isObject() ? AgentImportJson.text(rf, "type") : AgentImportJson.textOf(rf);
        if (type == null || "auto".equals(type) || "text".equals(type)) {
            return;
        }
        r.needsManual(
                "response_format (" + AgentImportText.inline(type, 40) + ")",
                "Structured / JSON output mode is not carried over.",
                "Describe the required output format in the instructions.");
    }

    private static int countIds(JsonNode root, String resource, String idsField) {
        JsonNode res = AgentImportJson.obj(root, "tool_resources");
        JsonNode inner = res == null ? null : AgentImportJson.obj(res, resource);
        JsonNode ids = inner == null ? null : AgentImportJson.arr(inner, idsField);
        return ids == null ? 0 : ids.size();
    }
}
