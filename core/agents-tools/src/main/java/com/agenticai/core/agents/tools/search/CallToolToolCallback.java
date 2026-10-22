package com.agenticai.core.agents.tools.search;

import com.agenticai.core.agents.tools.ToolSearchToolNames;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.json.JsonMappers;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code call_tool(name, argsJson)}: runs a tool that {@code search_tools} activated this turn.
 *
 * <p><b>This is a dispatcher, not a second execution path.</b> It holds the very same
 * guard/log/step-event wrapped callbacks {@code AgentToolProvider} would have offered the model
 * directly (see {@link ToolSearchState}) and invokes the target's {@code call}. Everything that
 * wrapper chain does therefore still happens, under the target's REAL name: the per-turn step
 * ceiling, the approval policy and human-confirmation gate, the classification dispatch gate,
 * lethal-trifecta handling, output sanitisation, the tool-call log, circuit breakers and the
 * step-event trace. A human approval that parks a call parks it as the real tool with the real
 * arguments, so the replay path (which resolves tools by exact name, without deferral) works
 * unchanged. Nothing here may catch, translate or bypass what the target throws or returns.
 *
 * <p>Refused, with a one-line explanation the model can act on: a missing or unknown name, the
 * search tools themselves (no recursion), a deferred tool that has not been searched for yet (so
 * the model has never seen its schema), and arguments that are not a JSON object.
 */
public class CallToolToolCallback implements ToolCallback {

    public static final String NAME = ToolSearchToolNames.CALL_TOOL;

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Consecutive refused calls tolerated in one turn before the turn is stopped as a runaway loop.
     */
    static final int MAX_CONSECUTIVE_REFUSALS = 6;

    private final ToolSearchState state;
    private final UUID agentId;
    private final String sessionId;

    /**
     * Refusals never reach the target's own guard wrapper, so they are counted here; reset by a
     * dispatched call.
     */
    private final AtomicInteger consecutiveRefusals = new AtomicInteger();

    /** No refusal ceiling (tests and callers that have no agent context). */
    public CallToolToolCallback(ToolSearchState state) {
        this(state, null, null);
    }

    /**
     * With an agent and session, {@link #MAX_CONSECUTIVE_REFUSALS} refusals in a row stop the turn
     * like the step ceiling.
     */
    public CallToolToolCallback(ToolSearchState state, UUID agentId, String sessionId) {
        this.state = state;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Runs a tool found with search_tools. Pass its exact name and its arguments as a JSON object "
                                + "string that follows the inputSchema search_tools returned.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string", "description": "Exact tool name from search_tools."},
                        "argsJson": {"type": "string", "description": "The tool's arguments as a JSON object string, e.g. {\\"to\\": \\"a@b.com\\"}. Use {} for none."}
                    },
                    "required": ["name", "argsJson"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return dispatch(toolInput, null);
    }

    /** Forwards Spring AI's {@link ToolContext} to the target instead of dropping it. */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return dispatch(toolInput, toolContext);
    }

    private String dispatch(String toolInput, ToolContext toolContext) {
        JsonNode request;
        try {
            request = MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        } catch (Exception e) {
            return refuse(
                    "Could not run the tool: call_tool's input was not valid JSON. Use {\"name\": \"tool_name\", "
                            + "\"argsJson\": \"{}\"}.");
        }
        String name = request.path("name").asString("").trim();
        if (name.isEmpty()) {
            return refuse(
                    "Could not run the tool: no name was given. Find tools with search_tools first.");
        }
        if (SearchToolsToolCallback.NAME.equals(name) || NAME.equals(name)) {
            return refuse("Call " + name + " directly, not through call_tool.");
        }
        String argsJson;
        try {
            argsJson = argumentsOf(request);
        } catch (IllegalArgumentException e) {
            return refuse("Could not run \"" + name + "\": " + e.getMessage());
        }
        Optional<ToolCallback> target = state.callable(name);
        if (target.isEmpty()) {
            if (state.isDeferredButInactive(name)) {
                return refuse(
                        "The tool \""
                                + name
                                + "\" has not been loaded yet. Call search_tools with keywords for it "
                                + "(or {\"query\": \"select:"
                                + name
                                + "\"}) first, then call_tool again.");
            }
            return refuse(
                    "There is no tool named \""
                            + name
                            + "\" available to you. Use search_tools to find tools.");
        }
        consecutiveRefusals.set(0);
        return toolContext != null
                ? target.get().call(argsJson, toolContext)
                : target.get().call(argsJson);
    }

    /**
     * Returns {@code message} to the model, counting it; too many in a row stop the turn (see
     * {@link #MAX_CONSECUTIVE_REFUSALS}).
     */
    private String refuse(String message) {
        int refused = consecutiveRefusals.incrementAndGet();
        if (agentId != null && refused > MAX_CONSECUTIVE_REFUSALS) {
            throw new ToolStepLimitExceededException(agentId, sessionId, MAX_CONSECUTIVE_REFUSALS);
        }
        return message;
    }

    /**
     * The target's argument string: {@code argsJson} (a JSON-encoded string, the documented shape)
     * or, leniently, an object under {@code argsJson}/{@code arguments}/{@code args}. Always a JSON
     * object text; {@code {}} when none was given.
     */
    private static String argumentsOf(JsonNode request) {
        JsonNode raw = null;
        for (String field : new String[] {"argsJson", "arguments", "args"}) {
            JsonNode candidate = request.get(field);
            if (candidate != null && !candidate.isNull()) {
                raw = candidate;
                break;
            }
        }
        if (raw == null) {
            return "{}";
        }
        if (raw.isObject()) {
            return raw.toString();
        }
        if (raw.isString()) {
            String text = raw.asString().trim();
            if (text.isEmpty()) {
                return "{}";
            }
            try {
                JsonNode parsed = MAPPER.readTree(text);
                if (parsed != null && parsed.isObject()) {
                    // Re-serialised, so trailing tokens Jackson tolerated after the object are not
                    // forwarded.
                    return parsed.toString();
                }
            } catch (Exception e) {
                // fall through to the explanation below
            }
        }
        throw new IllegalArgumentException(
                "argsJson must be a JSON object string that follows the tool's inputSchema.");
    }
}
