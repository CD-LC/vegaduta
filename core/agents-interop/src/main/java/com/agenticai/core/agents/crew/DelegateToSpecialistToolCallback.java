package com.agenticai.core.agents.crew;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.prompts.tool.ToolPromptTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * "Crew"-style chat-time handoff tool - the capability-based sibling of {@code
 * DelegateToAgentToolCallback}. One instance per chat turn (or per handoff hop - see {@code
 * AgentToolProvider#resolveToolsForSpecialistHandoff}), built fresh by {@code
 * AgentToolProvider#resolveSpecialistDelegationTools}.
 *
 * <p>Where {@code DelegateToAgentToolCallback} addresses one pre-wired collaborator by id (one
 * tool per collaborator, capped at a structural depth of 1), this is a SINGLE tool per turn that
 * picks its target dynamically by role/capability tag via {@link SpecialistRegistry}, and may
 * itself be re-offered to the specialist it dispatches to - up to {@link HandoffChain#MAX_DEPTH}
 * hops total, with cycle detection - so a chain can go A -&gt; B -&gt; C in one user-facing turn.
 *
 * <p>All the actual dispatch logic - specialist lookup, the depth/cycle guard, running the
 * specialist's bounded sub-conversation, and the audit trail - lives in {@code
 * AgentChatService#delegateToSpecialist}. This class is deliberately a thin adapter, exactly like
 * {@code DelegateToAgentToolCallback}: parse the model's arguments, hand them off, wrap the
 * answer back into a small JSON envelope.
 */
public class DelegateToSpecialistToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DelegateToSpecialistToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    public static final String TOOL_NAME = "delegate_to_specialist";

    private final AgentChatService agentChatService;
    private final List<String> capabilityTags;
    private final HandoffChain chain;

    /**
     * The prompt registry as it stands for the agent whose tools are being resolved (captured at
     * construction, on the request thread): the description is the registry key {@code
     * tool.template.delegate_to_specialist} with its placeholders filled in.
     */
    private final ToolPromptTexts promptTexts = ToolPromptTexts.current();

    public DelegateToSpecialistToolCallback(AgentChatService agentChatService, List<String> capabilityTags, HandoffChain chain) {
        this.agentChatService = agentChatService;
        this.capabilityTags = capabilityTags;
        this.chain = chain;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String roster = String.join(", ", capabilityTags);
        return ToolDefinition.builder()
            .name(TOOL_NAME)
            .description(
                promptTexts.description(
                    TOOL_NAME,
                    Map.of(
                        "roster", roster,
                        "max_depth", String.valueOf(HandoffChain.MAX_DEPTH),
                        "hop", String.valueOf(chain.depth()))))
            .inputSchema(promptTexts.schema(TOOL_NAME, """
                {
                    "type": "object",
                    "properties": {
                        "capability": {
                            "type": "string",
                            "description": "The capability/role tag of the specialist to delegate to - one of the capabilities listed in this tool's description."
                        },
                        "subTask": {
                            "type": "string",
                            "description": "A focused, self-contained sub-task or question for the specialist - include any context it needs, since it has no visibility into this conversation."
                        },
                        "contextSummary": {
                            "type": "string",
                            "description": "Optional. A 1-2 sentence summary of anything established earlier in this conversation the specialist needs to know. Do NOT paste the raw conversation - just the specific facts that matter."
                        }
                    },
                    "required": ["capability", "subTask"]
                }
                """))
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object capabilityValue = args.get("capability");
            if (capabilityValue == null || capabilityValue.toString().isBlank()) {
                return "{\"error\": \"capability is required - one of: " + String.join(", ", capabilityTags) + "\"}";
            }
            Object subTaskValue = args.get("subTask");
            if (subTaskValue == null || subTaskValue.toString().isBlank()) {
                return "{\"error\": \"subTask is required\"}";
            }
            Object contextSummaryValue = args.get("contextSummary");
            String contextSummary = (contextSummaryValue == null || contextSummaryValue.toString().isBlank())
                ? null
                : contextSummaryValue.toString();
            String answer = agentChatService.delegateToSpecialist(
                chain, capabilityValue.toString(), subTaskValue.toString(), contextSummary);
            return MAPPER.writeValueAsString(Map.of("capability", capabilityValue.toString(), "answer", answer));
        } catch (Exception e) {
            // Same "don't leak raw provider detail past this layer" posture as
            // DelegateToAgentToolCallback#call - the message flows back into
            // the calling model's context and often straight into what the
            // model tells the end user.
            log.warn("Specialist handoff failed at chain depth {}", chain.depth(), e);
            String detail = e instanceof UpstreamProviderException upstreamEx
                ? upstreamEx.getConsumerMessage()
                : "The specialist agent could not be reached right now.";
            return "{\"error\": \"Delegation failed: " + detail.replace("\"", "'") + "\"}";
        }
    }
}
