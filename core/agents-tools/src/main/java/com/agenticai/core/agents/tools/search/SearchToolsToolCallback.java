package com.agenticai.core.agents.tools.search;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.ToolSearchToolNames;
import com.agenticai.core.guardrails.ToolResultMarker;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code search_tools}: finds tools that were left out of this turn's tool list (see {@code
 * AgentToolProvider}'s deferral policy) and activates them for the rest of the run.
 *
 * <p>The tool list handed to the model is fixed when a turn starts and the model providers' own
 * tool loops cannot be changed mid-turn, so "activation" is realised in two parts: this result
 * carries each match's name, one-line description and FULL input schema, and {@link
 * CallToolToolCallback} will then run an activated tool - through the very same guard chain a
 * direct call would use. The result is plain text so it costs only what it says.
 *
 * <p>Never throws: every failure is a short sentence the model can act on, the same contract as
 * every other built-in tool callback. Read-only; it reveals nothing the model would not have seen
 * had the tool been offered directly.
 */
public class SearchToolsToolCallback implements ToolCallback {

    public static final String NAME = ToolSearchToolNames.SEARCH_TOOLS;

    private static final Logger log = LoggerFactory.getLogger(SearchToolsToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** A schema longer than this is not inlined - the parameter names are listed instead. */
    static final int MAX_INLINE_SCHEMA_CHARS = 2_500;

    /**
     * Soft cap on the whole result: schemas stop being inlined once this much is written, because
     * the guard's output validator rejects anything over 20,000 characters and the model would then
     * see none of it.
     */
    static final int MAX_RESULT_CHARS = 12_000;

    /**
     * Room kept for each tool still to be listed (its name line plus a parameter-names fallback).
     */
    private static final int RESERVE_PER_TOOL_CHARS = 700;

    private static final int MAX_PARAMETER_LIST_CHARS = 400;
    private static final int MAX_SOURCES_IN_HINT = 12;

    /**
     * Below this catalog size a no-match response lists every deferred tool name - the cheapest
     * recovery map for a model whose keywords missed (task words rarely overlap tool names).
     * Larger catalogs list name groups plus a few generic tools instead.
     */
    static final int MAX_NAMES_IN_HINT = 60;

    private static final int MAX_GROUPS_IN_HINT = 20;
    private static final int MAX_GENERIC_HINTS = 12;

    /**
     * Name words that mark a broadly useful tool worth naming even when the catalog is too big to
     * list: web/fetch/search/file reach is what agents most often fail to discover.
     */
    private static final Set<String> GENERIC_NAME_WORDS =
            Set.of("web", "fetch", "search", "browse", "browser", "http", "url", "file", "read");

    private final ToolSearchState state;

    public SearchToolsToolCallback(ToolSearchState state) {
        this.state = state;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Finds more tools by keyword when none of your listed tools fit. Returns matching tool names with "
                                + "their input schemas and makes them callable via call_tool. Use select:name1,name2 to load "
                                + "tools by exact name.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "A few keywords describing what you need, e.g. 'send email' or 'create github issue'."},
                        "limit": {"type": "integer", "description": "Maximum results, 1 to 8 (default 5)."}
                    },
                    "required": ["query"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String query;
        int limit;
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            query = args.path("query").asText("").trim();
            limit = args.path("limit").asInt(ToolSearchService.DEFAULT_LIMIT);
        } catch (Exception e) {
            return platformAnswer(
                    "Could not search tools: the tool input was not valid JSON. Call search_tools with {\"query\": \"keywords\"}.");
        }
        if (query.isEmpty()) {
            return platformAnswer(
                    "Could not search tools: no query was given. Call search_tools with a few keywords, e.g. "
                            + "{\"query\": \"send email\"}.");
        }
        List<ToolSearchService.Hit> hits;
        try {
            hits = state.service().search(state.index(), query, limit);
        } catch (RuntimeException e) {
            log.warn("search_tools failed for query '{}': {}", query, e.toString());
            return platformAnswer(
                    "Tool search is unavailable right now. Continue with the tools you already have.");
        }
        List<String> names = new ArrayList<>(hits.size());
        for (ToolSearchService.Hit hit : hits) {
            names.add(hit.name());
        }
        List<String> activated = state.activate(names);
        emitStep(query, activated);
        if (activated.isEmpty()) {
            return catalogResult(noMatch(query));
        }
        return catalogResult(format(query, activated));
    }

    private String format(String query, List<String> activated) {
        StringBuilder sb = new StringBuilder();
        sb.append("Found ")
                .append(activated.size())
                .append(" tool(s) for \"")
                .append(query)
                .append(
                        "\". They are now active: run one with call_tool(name, argsJson), where argsJson is a JSON object ")
                .append("string that follows the tool's inputSchema.\n");
        int rank = 1;
        for (int i = 0; i < activated.size(); i++) {
            String name = activated.get(i);
            Optional<ToolCallback> tool = state.deferredTool(name);
            if (tool.isEmpty()) {
                continue;
            }
            int remainingAfterThis = activated.size() - i - 1;
            ToolDefinition definition = tool.get().getToolDefinition();
            sb.append('\n')
                    .append(rank++)
                    .append(". ")
                    .append(name)
                    .append(" - ")
                    .append(ToolSearchService.oneLine(definition.description()))
                    .append('\n');
            int budgetLeft =
                    MAX_RESULT_CHARS - sb.length() - remainingAfterThis * RESERVE_PER_TOOL_CHARS;
            sb.append("   inputSchema: ")
                    .append(schemaFor(name, definition.inputSchema(), budgetLeft))
                    .append('\n');
        }
        return sb.toString();
    }

    private String schemaFor(String name, String inputSchema, int budgetLeft) {
        String compact = compact(inputSchema);
        if (compact.length() <= MAX_INLINE_SCHEMA_CHARS && compact.length() <= budgetLeft) {
            return compact;
        }
        List<String> params = state.index().parameterNames(name);
        String names = params.isEmpty() ? "unknown" : String.join(", ", params);
        if (names.length() > MAX_PARAMETER_LIST_CHARS) {
            names = names.substring(0, MAX_PARAMETER_LIST_CHARS) + ", ...";
        }
        return "(too large to show; parameters: " + names + ")";
    }

    /**
     * Whitespace-free JSON when the schema parses, otherwise the raw text with runs of whitespace
     * collapsed.
     */
    static String compact(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(MAPPER.readTree(inputSchema));
        } catch (Exception e) {
            return inputSchema.replaceAll("\\s+", " ").trim();
        }
    }

    private String noMatch(String query) {
        ToolSearchService.Index index = state.index();
        StringBuilder sb = new StringBuilder();
        sb.append("No tools matched \"").append(query).append("\".");
        List<String> names = index.names();
        if (names.isEmpty()) {
            sb.append(
                    " Try different or fewer keywords; nothing is waiting behind search right now.");
            return sb.toString();
        }
        Map<String, Integer> sources = index.sources();
        if (!sources.isEmpty()) {
            sb.append(" Searchable tools by source: ");
            int shown = 0;
            for (Map.Entry<String, Integer> source : sources.entrySet()) {
                if (shown++ >= MAX_SOURCES_IN_HINT) {
                    sb.append(", ...");
                    break;
                }
                if (shown > 1) {
                    sb.append(", ");
                }
                sb.append(source.getKey()).append(" (").append(source.getValue()).append(')');
            }
            sb.append('.');
        }
        if (names.size() <= MAX_NAMES_IN_HINT) {
            sb.append(" Searchable tool names: ").append(String.join(", ", names)).append('.');
        } else {
            appendLargeCatalogHint(sb, names);
        }
        sb.append(
                " Search again with one of these names or a keyword from them, or load a tool by "
                        + "exact name with select:name1,name2.");
        return sb.toString();
    }

    /**
     * The catalog is too big to enumerate: show the distinct name prefixes ({@code mcp__<server>}
     * for MCP tools, the first word otherwise) plus a few broadly useful tool names, so a model
     * that missed lexically can still see what the catalog can do.
     */
    private static void appendLargeCatalogHint(StringBuilder sb, List<String> names) {
        List<String> groups = nameGroups(names);
        sb.append(" Tool name groups: ");
        int shown = Math.min(groups.size(), MAX_GROUPS_IN_HINT);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(groups.get(i));
        }
        if (groups.size() > shown) {
            sb.append(" (+").append(groups.size() - shown).append(" more)");
        }
        sb.append('.');
        List<String> generic = genericTools(names);
        if (!generic.isEmpty()) {
            sb.append(" Common searchable tools: ")
                    .append(String.join(", ", generic))
                    .append('.');
        }
    }

    /** Distinct leading name words ({@code mcp__<server>} for MCP tools), in first-seen order. */
    static List<String> nameGroups(List<String> names) {
        Set<String> groups = new LinkedHashSet<>();
        for (String name : names) {
            groups.add(groupOf(name));
        }
        return List.copyOf(groups);
    }

    private static String groupOf(String name) {
        if (name.startsWith("mcp__")) {
            int end = name.indexOf("__", 5);
            return end > 5 ? name.substring(0, end) : name;
        }
        int underscore = name.indexOf('_');
        return underscore > 0 ? name.substring(0, underscore) : name;
    }

    /** Up to {@link #MAX_GENERIC_HINTS} catalog names that mention a generic capability word. */
    private static List<String> genericTools(List<String> names) {
        List<String> picked = new ArrayList<>(MAX_GENERIC_HINTS);
        for (String name : names) {
            if (picked.size() >= MAX_GENERIC_HINTS) {
                break;
            }
            for (String word : name.toLowerCase(Locale.ROOT).split("_")) {
                if (GENERIC_NAME_WORDS.contains(word)) {
                    picked.add(name);
                    break;
                }
            }
        }
        return picked;
    }

    /**
     * An answer the platform authored in full - an input or availability diagnostic - is
     * registered with {@link ToolResultMarker} so the guard's untrusted-content screen passes it
     * through (these read like injection instructions to a content judge) and the journals know
     * nothing ran.
     */
    private static String platformAnswer(String message) {
        return ToolResultMarker.mark(message, ToolResultMarker.Kind.BLOCKED);
    }

    /**
     * The search RESULT embeds tool names, descriptions and schemas an MCP server can author, so it
     * is only marked the platform's answer when the catalog was screened at build time ({@link
     * ToolSearchState#catalogScreened()} - {@code SearchCatalogScreen} screens each deferred tool's
     * text once, as it enters the catalog). An unscreened catalog keeps the old contract: results
     * unmarked, screened per call like any tool output.
     */
    private String catalogResult(String text) {
        return state.catalogScreened() ? platformAnswer(text) : text;
    }

    private static void emitStep(String query, List<String> activated) {
        try {
            AgentStepEventEmitter.current()
                    .ifPresent(emitter -> emitter.toolSearch(query, activated.size(), activated));
        } catch (RuntimeException e) {
            // A trace line must never fail a search.
            log.debug("Could not emit the tool_search step event", e);
        }
    }
}
