package com.agenticai.core.agents.tools.context;

import com.agenticai.core.agents.context.ToolResultRestoreIds;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code restore_tool_result}: hands back the stored output of an earlier tool call of THIS run
 * whose result was cleared from the prompt to save context (see {@code ContextCompactionEngine}).
 * Read-only.
 *
 * <p>Where the output comes from: {@code tool_call_log}, which {@code LoggingToolCallback} writes
 * for every guarded tool call - the run record. A cleared placeholder carries {@code
 * <tool>#<argsDigest>} (or the model may pass a log id); the newest matching call wins.
 *
 * <h2>Tenancy</h2>
 *
 * The agent id and session id are bound when the callback is built (the same closure posture as
 * {@code LoggingToolCallback}) and are the ONLY scope every lookup uses - identity never travels as
 * a tool argument, so the model cannot ask for another run's output by naming it. The tenant comes
 * from the tenant schema the repository query runs in ({@code TenantContext} is re-established
 * around tool execution by {@code TenantContextPropagatingToolCallback}). A row that nevertheless
 * came back for a different agent or session is refused.
 *
 * <p>Never throws: every failure is a short sentence the model can act on ("run the tool again").
 */
public class RestoreToolResultToolCallback implements ToolCallback {

    public static final String NAME = "restore_tool_result";

    private static final Logger log = LoggerFactory.getLogger(RestoreToolResultToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Same ceiling built-in tool results are capped at, and safely under the output validator's
     * 20,000.
     */
    static final int MAX_RESULT_CHARS = 16 * 1024;

    private final ToolCallLogService logs;
    private final UUID agentId;
    private final String sessionId;

    public RestoreToolResultToolCallback(ToolCallLogService logs, UUID agentId, String sessionId) {
        this.logs = logs;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Returns the original output of an earlier tool call in this conversation that was cleared from "
                                + "context to save space. Pass the stepId shown in the cleared placeholder.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "stepId": {"type": "string", "description": "The restore pointer from a cleared tool result, e.g. web_search#1a2b3c4d5e6f."}
                    },
                    "required": ["stepId"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String stepId;
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            stepId = args.path("stepId").asText("").trim();
        } catch (Exception e) {
            return "Could not restore: the tool input was not valid JSON. Call restore_tool_result with "
                    + "{\"stepId\": \"<value from the cleared result>\"}.";
        }
        Optional<ToolResultRestoreIds.Parsed> parsed = ToolResultRestoreIds.parse(stepId);
        if (parsed.isEmpty()) {
            return "Could not restore: stepId must be the pointer from a cleared tool result, in the form "
                    + "toolName#digest. If you no longer have it, run the tool again.";
        }
        try {
            Optional<ToolCallLog> row =
                    parsed.get().byLogId() ? byId(parsed.get().logId()) : byDigest(parsed.get());
            if (row.isEmpty()) {
                return "No stored result was found for that step in this conversation (it may have expired or "
                        + "the arguments differ). Run the tool again if you still need it.";
            }
            return render(row.get());
        } catch (RuntimeException e) {
            log.warn(
                    "restore_tool_result lookup failed for agent {} session {}: {}",
                    agentId,
                    sessionId,
                    e.toString());
            return "Restoring the stored result is unavailable right now. Run the tool again if you still need it.";
        }
    }

    private Optional<ToolCallLog> byId(UUID logId) {
        return logs.findByIdAndAgentIdAndSessionId(logId, agentId, sessionId)
                .filter(this::inThisRun);
    }

    private Optional<ToolCallLog> byDigest(ToolResultRestoreIds.Parsed parsed) {
        List<ToolCallLog> candidates =
                logs.findRecentByAgentIdAndSessionIdAndToolName(
                        agentId, sessionId, parsed.toolName());
        if (candidates == null) {
            return Optional.empty();
        }
        for (ToolCallLog candidate : candidates) {
            if (!inThisRun(candidate) || !parsed.toolName().equals(candidate.getToolName())) {
                continue;
            }
            boolean hasPayload =
                    candidate.getToolOutput() != null || candidate.getErrorMessage() != null;
            if (hasPayload
                    && ToolResultRestoreIds.digest(candidate.getToolInput())
                            .startsWith(parsed.argsDigest())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Defence in depth behind the bound query: a row of another agent or session is never returned.
     */
    private boolean inThisRun(ToolCallLog row) {
        return agentId != null
                && agentId.equals(row.getAgentId())
                && sessionId != null
                && sessionId.equals(row.getSessionId());
    }

    private static String render(ToolCallLog row) {
        String text = row.getToolOutput();
        if (text == null) {
            text = "The tool call failed: " + row.getErrorMessage();
        }
        if (text.length() > MAX_RESULT_CHARS) {
            int dropped = text.length() - MAX_RESULT_CHARS;
            return text.substring(0, MAX_RESULT_CHARS) + "\n...[truncated " + dropped + " chars]";
        }
        return text;
    }
}
