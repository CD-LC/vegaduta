package com.agenticai.core.agents.tools.search;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.ToolSearchToolNames;
import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
            query = args.path("query").asString("").trim();
            limit = args.path("limit").asInt(ToolSearchService.DEFAULT_LIMIT);
        } catch (Exception e) {
            return "Could not search tools: the tool input was not valid JSON. Call search_tools with {\"query\": \"keywords\"}.";
        }
        if (query.isEmpty()) {
            return "Could not search tools: no query was given. Call search_tools with a few keywords, e.g. "
                    + "{\"query\": \"send email\"}.";
        }
        List<ToolSearchService.Hit> hits;
        try {
            hits = state.service().search(state.index(), query, limit);
        } catch (RuntimeException e) {
            log.warn("search_tools failed for query '{}': {}", query, e.toString());
            return "Tool search is unavailable right now. Continue with the tools you already have.";
        }
        List<String> names = new ArrayList<>(hits.size());
        for (ToolSearchService.Hit hit : hits) {
            names.add(hit.name());
        }
        List<String> activated = state.activate(names);
        emitStep(query, activated);
        if (activated.isEmpty()) {
            return noMatch(query);
        }
        return format(query, activated);
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
        StringBuilder sb = new StringBuilder();
        sb.append("No tools matched \"")
                .append(query)
                .append("\". Try different or fewer keywords.");
        Map<String, Integer> sources = state.index().sources();
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
        return sb.toString();
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
