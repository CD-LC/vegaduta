package com.agenticai.core.agents.collab;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.prompts.tool.ToolPromptTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Multi-agent delegation - "specialists working
 * together." One instance per collaborator agent, built fresh per chat turn by {@code
 * AgentToolProvider#resolveCollaboratorTools} (same "plain in-process ToolCallback, wrapped by
 * AgentToolProvider exactly like every other tool" pattern {@code RememberFactToolCallback}/
 * {@code GraphRecallToolCallback} already established).
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

    /** Registry id of the description template ({@code tool.template.delegate_to_agent}) shared by every collaborator tool. */
    static final String TEMPLATE_ID = "delegate_to_agent";

    /**
     * The prompt registry as it stands for the agent whose tools are being resolved (captured at
     * construction, on the request thread). Outside a tool resolution it serves the shipped text.
     */
    private final ToolPromptTexts promptTexts = ToolPromptTexts.current();

    public DelegateToAgentToolCallback(
        AgentChatService agentChatService,
        UUID collaboratorAgentId,
        String toolName,
        String collaboratorName,
        String collaboratorDescription
    ) {
        this.agentChatService = agentChatService;
        this.collaboratorAgentId = collaboratorAgentId;
        this.toolName = toolName;
        this.collaboratorName = collaboratorName;
        this.collaboratorDescription = collaboratorDescription;
    }

    private static final String INPUT_SCHEMA =
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
                """;

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(toolName)
            .description(
                promptTexts.description(
                    TEMPLATE_ID,
                    Map.of(
                        "name", String.valueOf(collaboratorName),
                        "specialty",
                            collaboratorDescription == null || collaboratorDescription.isBlank()
                                ? "."
                                : ": " + collaboratorDescription + ".")))
            .inputSchema(promptTexts.schema(TEMPLATE_ID, INPUT_SCHEMA))
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
            String contextSummary = (contextSummaryValue == null || contextSummaryValue.toString().isBlank())
                ? null
                : contextSummaryValue.toString();
            String answer = agentChatService.delegateCall(collaboratorAgentId, questionValue.toString(), contextSummary);
            return MAPPER.writeValueAsString(Map.of("answeredBy", collaboratorName, "answer", answer));
        } catch (Exception e) {
            // Full technical detail (stack trace included) goes to the server
            // log line below; only the plain-language consumer message goes
            // into this tool result, since it flows back into the calling
            // model's context and, from there, often straight into what the
            // model tells the end user - same "don't leak the raw provider
            // detail past this layer" posture as QuickTaskService#runStream's
            // SSE error event and GlobalExceptionHandler#handleUpstreamProvider.
            log.warn("Delegation to agent {} ('{}') failed", collaboratorAgentId, collaboratorName, e);
            String detail = e instanceof UpstreamProviderException upstreamEx
                ? upstreamEx.getConsumerMessage()
                : "The specialist agent could not be reached right now.";
            return "{\"error\": \"Delegation to " + collaboratorName.replace("\"", "'") + " failed: " + detail.replace("\"", "'") + "\"}";
        }
    }
}
