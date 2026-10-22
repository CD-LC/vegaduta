package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.TemplateRequirement;
import com.agenticai.core.templates.dto.TemplateRequirement.Type;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Works out what an importer has to activate for a template (MCP servers, a Google connection, a
 * knowledge base, a sandbox, a model key...) and the machine tags that mirror it. A pure function
 * of the sanitised snapshot, derived at read time so every template reports it with no migration.
 * Split out of {@link TemplateReadinessService}, which exposes it as {@code requirements} and also
 * uses it in the submit gate.
 */
final class TemplateRequirements {

    private TemplateRequirements() {}

    /** What an importer must activate. Never throws; unknown shapes yield an empty list. */
    static List<TemplateRequirement> requirements(TemplateKind kind, JsonNode config) {
        if (config == null || config.isMissingNode() || config.isNull()) {
            return List.of();
        }
        Map<String, TemplateRequirement> out = new LinkedHashMap<>();
        Set<String> tools = toolNames(kind, config);

        for (String tool : tools) {
            if (tool.startsWith("mcp__")) {
                String server = mcpServer(tool);
                out.putIfAbsent(
                        "mcp:" + server,
                        new TemplateRequirement(
                                Type.MCP_SERVER,
                                server,
                                "MCP server: " + server,
                                "Calls tools on the \""
                                        + server
                                        + "\" MCP server - enable it (and add its credentials)"
                                        + " first.",
                                true,
                                "/tools/servers"));
            } else if (tool.startsWith("google_")) {
                out.putIfAbsent(
                        "google",
                        new TemplateRequirement(
                                Type.GOOGLE_CONNECTION,
                                "google",
                                "Google account connection",
                                "Uses Google Workspace tools ("
                                        + tool
                                        + "...) - connect a Google account so they can run.",
                                true,
                                "/tools"));
            } else if (tool.equals("search_knowledge_base")
                    || tool.equals("list_knowledge_collections")) {
                out.putIfAbsent("kb", knowledgeRequirement());
            } else if (tool.startsWith("code_") || tool.equals("search_code")) {
                out.putIfAbsent(
                        "codeintel",
                        new TemplateRequirement(
                                Type.CODE_INTEL,
                                null,
                                "Code intelligence index",
                                "Searches an indexed repository - register and index a repo first.",
                                true,
                                "/code-intel"));
            } else if (tool.equals("run_code")) {
                out.putIfAbsent("sandbox", sandboxRequirement());
            } else if (tool.startsWith("remember_") || tool.startsWith("recall_")) {
                out.putIfAbsent(
                        "memory",
                        new TemplateRequirement(
                                Type.MEMORY,
                                null,
                                "Agent memory",
                                "Stores and recalls facts between conversations (built in - nothing"
                                        + " to install).",
                                false,
                                null));
            } else if (tool.equals("request_human")) {
                out.putIfAbsent(
                        "human",
                        new TemplateRequirement(
                                Type.HUMAN_APPROVAL,
                                null,
                                "Human approval step",
                                "Pauses and asks a person before acting (built in - approvals"
                                        + " arrive in your inbox).",
                                false,
                                null));
            } else {
                out.putIfAbsent(
                        "tool:" + tool,
                        new TemplateRequirement(
                                Type.BUILT_IN_TOOL,
                                tool,
                                "Tool: " + tool,
                                "Built-in platform tool the design relies on.",
                                false,
                                null));
            }
        }

        if (kind == TemplateKind.AGENT) {
            if (config.path("knowledgeBaseEnabled").asBoolean(false)) {
                out.putIfAbsent("kb", knowledgeRequirement());
            }
            if (config.path("e2bEnabled").asBoolean(false)
                    || config.path("codeWorkspaceEnabled").asBoolean(false)) {
                out.putIfAbsent("sandbox", sandboxRequirement());
            }
            String provider = config.path("providerName").asString("");
            if (!provider.isBlank() && !"PLATFORM_HOSTED".equalsIgnoreCase(provider)) {
                out.putIfAbsent(
                        "model",
                        new TemplateRequirement(
                                Type.MODEL_KEY,
                                provider,
                                "Model provider key: " + provider,
                                "Designed for "
                                        + provider
                                        + " - add your own key, or the agent falls back to your"
                                        + " default model.",
                                false,
                                "/providers"));
            }
            if (!config.path("voiceModel").asString("").isBlank()) {
                out.putIfAbsent(
                        "voice",
                        new TemplateRequirement(
                                Type.VOICE_MODEL,
                                config.path("voiceModel").asString(),
                                "Voice model",
                                "Uses a voice model for spoken replies.",
                                false,
                                "/providers"));
            }
            if (!config.path("videoModel").asString("").isBlank()) {
                out.putIfAbsent(
                        "video",
                        new TemplateRequirement(
                                Type.VIDEO_MODEL,
                                config.path("videoModel").asString(),
                                "Video model",
                                "Uses a video model for generated clips.",
                                false,
                                "/providers"));
            }
        }
        return List.copyOf(out.values());
    }

    private static TemplateRequirement knowledgeRequirement() {
        return new TemplateRequirement(
                Type.KNOWLEDGE_BASE,
                null,
                "Knowledge base",
                "Answers from your documents - upload files to a knowledge collection and attach it"
                        + " after import.",
                true,
                "/knowledge");
    }

    private static TemplateRequirement sandboxRequirement() {
        return new TemplateRequirement(
                Type.CODE_SANDBOX,
                null,
                "Code sandbox",
                "Runs code in an isolated sandbox - available on plans that include it.",
                true,
                null);
    }

    static String mcpServer(String toolName) {
        String rest = toolName.substring("mcp__".length());
        int sep = rest.indexOf("__");
        return sep < 0 ? rest : rest.substring(0, sep);
    }

    /**
     * Machine tags (tool:/mcp:/setup:) that mirror the requirements, in the platform's existing
     * convention.
     */
    static List<String> derivedTags(List<TemplateRequirement> requirements) {
        Set<String> tags = new LinkedHashSet<>();
        for (TemplateRequirement r : requirements) {
            switch (r.type()) {
                case MCP_SERVER -> tags.add("mcp:" + r.id());
                case GOOGLE_CONNECTION -> tags.add("setup:google");
                case KNOWLEDGE_BASE -> tags.add("setup:knowledge");
                case CODE_INTEL -> tags.add("tool:codeintel");
                case CODE_SANDBOX -> tags.add("sandbox");
                case MODEL_KEY -> tags.add("setup:model-key");
                default -> {}
            }
        }
        return tags.stream().filter(t -> t.length() <= 40).toList();
    }

    static Set<String> toolNames(TemplateKind kind, JsonNode config) {
        Set<String> out = new LinkedHashSet<>();
        if (config == null) {
            return out;
        }
        if (kind == TemplateKind.AGENT) {
            for (JsonNode n : config.path("allowedToolNames")) {
                if (n.isString() && !n.asString().isBlank()) {
                    out.add(n.asString());
                }
            }
        } else {
            for (JsonNode n : config.path("graph").path("nodes")) {
                if ("tool".equals(n.path("type").asString())) {
                    String name = n.path("data").path("config").path("toolName").asString("");
                    if (!name.isBlank()) {
                        out.add(name);
                    }
                }
            }
        }
        return out;
    }
}
