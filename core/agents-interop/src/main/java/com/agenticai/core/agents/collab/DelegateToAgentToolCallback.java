package com.agenticai.core.agents.collab;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Multi-agent delegation - "specialists working together." One instance per collaborator agent,
 * built fresh per chat turn by {@code AgentToolProvider#resolveCollaboratorTools} (same "plain
 * in-process ToolCallback, wrapped by AgentToolProvider exactly like every other tool" pattern
 * {@code RememberFactToolCallback}/ {@code GraphRecallToolCallback} already established).
 *
 * <p>All the actual work - and the entire delegation-depth cap that keeps this from being able to
 * form a cycle - happens in {@link AgentChatService#delegateCall}. This class is deliberately a
 * thin adapter: parse the model's {@code question} argument, hand it to {@code delegateCall}, wrap
 * the answer back into a small JSON envelope so the calling model can see who actually answered.
 */
public class DelegateToAgentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DelegateToAgentToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final AgentChatService agentChatService;
    private final UUID collaboratorAgentId;
    private final String toolName;
    private final String collaboratorName;
    private final String collaboratorDescription;

    public DelegateToAgentToolCallback(
            AgentChatService agentChatService,
            UUID collaboratorAgentId,
            String toolName,
            String collaboratorName,
            String collaboratorDescription) {
        this.agentChatService = agentChatService;
        this.collaboratorAgentId = collaboratorAgentId;
        this.toolName = toolName;
        this.collaboratorName = collaboratorName;
        this.collaboratorDescription = collaboratorDescription;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(toolName)
                .description(
                        "Delegates a specific question to \""
                                + collaboratorName
                                + "\", a specialist agent"
                                + (collaboratorDescription == null
                                                || collaboratorDescription.isBlank()
                                        ? "."
                                        : ": " + collaboratorDescription + ".")
                                + " Use this when part of the user's request falls squarely in this specialist's area rather "
                                + "than your own - ask it a focused, self-contained question and use its answer. It will NOT "
                                + "see this conversation's history, only the exact question you send plus, if you provide one, "
                                + "a short context summary.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "question": {
                            "type": "string",
                            "description": "A focused, self-contained question or sub-task for the specialist agent - include any context it needs, since it has no visibility into this conversation."
                        },
                        "contextSummary": {
                            "type": "string",
                            "description": "Optional. A 1-2 sentence summary of anything established earlier in this conversation that the specialist needs to know to answer well (e.g. a constraint, a prior decision, a detail the user already gave). Do NOT paste the raw conversation - just the specific facts that matter for this question."
                        }
                    },
                    "required": ["question"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object questionValue = args.get("question");
            if (questionValue == null || questionValue.toString().isBlank()) {
                return "{\"error\": \"question is required\"}";
            }
            Object contextSummaryValue = args.get("contextSummary");
            String contextSummary =
                    (contextSummaryValue == null || contextSummaryValue.toString().isBlank())
                            ? null
                            : contextSummaryValue.toString();
            String answer =
                    agentChatService.delegateCall(
                            collaboratorAgentId, questionValue.toString(), contextSummary);
            return MAPPER.writeValueAsString(
                    Map.of("answeredBy", collaboratorName, "answer", answer));
        } catch (Exception e) {
            // Full technical detail (stack trace included) goes to the server
            // log line below; only the plain-language consumer message goes
            // into this tool result, since it flows back into the calling
            // model's context and, from there, often straight into what the
            // model tells the end user - same "don't leak the raw provider
            // detail past this layer" posture as QuickTaskService#runStream's
            // SSE error event and GlobalExceptionHandler#handleUpstreamProvider.
            log.warn(
                    "Delegation to agent {} ('{}') failed",
                    collaboratorAgentId,
                    collaboratorName,
                    e);
            String detail =
                    e instanceof UpstreamProviderException upstreamEx
                            ? upstreamEx.getConsumerMessage()
                            : "The specialist agent could not be reached right now.";
            return "{\"error\": \"Delegation to "
                    + collaboratorName.replace("\"", "'")
                    + " failed: "
                    + detail.replace("\"", "'")
                    + "\"}";
        }
    }
}
