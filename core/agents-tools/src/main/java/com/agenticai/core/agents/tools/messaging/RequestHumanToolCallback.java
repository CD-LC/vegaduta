package com.agenticai.core.agents.tools.messaging;

import com.agenticai.core.json.JsonMappers;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code request_human} - lets an agent escalate the CURRENT conversation to a human operator when
 * it cannot (or should not) keep handling it itself: the user explicitly asks for a person, the
 * agent is repeatedly failing at the same task, or the request is outside what the agent should
 * decide on its own (e.g. a refund, a complaint, something sensitive). Delegates to {@link
 * HumanHandoffPort#requestHuman}, the designed seam {@code
 * HandoffEscalationService.requestHumanForAgentSession}'s own javadoc names (see {@link
 * HumanHandoffPort}'s javadoc for the full cross-module story).
 *
 * <p>Deliberately narrow, same shape as {@link ForwardAttachmentToolCallback}: this tool does not
 * itself decide anything about routing, pairing, or channel type - {@link HumanHandoffPort}'s
 * implementation owns every one of those rules (e.g. a live VOICE call or an unpaired sender cannot
 * be escalated this way) and reports back a plain, always-present {@code escalated}/{@code detail}
 * pair the model can act on regardless of why an attempt did or didn't succeed - there is no
 * separate error path to handle beyond the port genuinely throwing, which {@link
 * HumanHandoffPort}'s own contract says it must not do for an ordinary escalation failure.
 *
 * <p>{@code sessionId} is captured at construction time (this class is built fresh per chat turn by
 * {@code AgentToolProvider}, exactly like {@code RememberFactToolCallback}'s {@code agentId}/{@code
 * subjectKey}) - the model never supplies or can override which conversation gets escalated, only
 * why.
 */
public class RequestHumanToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RequestHumanToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final HumanHandoffPort handoffPort;
    private final String sessionId;

    public RequestHumanToolCallback(HumanHandoffPort handoffPort, String sessionId) {
        this.handoffPort = handoffPort;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("request_human")
                .description(
                        "Escalates THIS conversation to a human team member. Use this when the user explicitly asks to "
                                + "speak to a person, when you are unable to help after genuinely trying, or when the request "
                                + "is sensitive enough (a complaint, a refund, something you should not decide alone) that a "
                                + "human should take over. reason is shown to the operator picking this up, so make it a "
                                + "short, specific summary of why a human is needed - not a generic phrase. After calling "
                                + "this tool, tell the user (in their language, briefly) that a team member has been notified, "
                                + "using the tool result's own wording as your guide - do not promise a response time this "
                                + "tool does not state.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "reason": {
                            "type": "string",
                            "description": "Short, specific reason a human is needed, e.g. \\"Customer is requesting a refund for order #4521\\""
                        }
                    },
                    "required": ["reason"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object reasonValue = args.get("reason");
            if (reasonValue == null || reasonValue.toString().isBlank()) {
                return errorJson("reason is required");
            }
            HumanHandoffPort.HandoffOutcome outcome =
                    handoffPort.requestHuman(sessionId, reasonValue.toString());
            return MAPPER.writeValueAsString(
                    Map.of(
                            "escalated",
                            outcome.escalated(),
                            "detail",
                            outcome.detail() == null ? "" : outcome.detail()));
        } catch (Exception e) {
            // Defense-in-depth only - HumanHandoffPort's own contract says it must not throw for
            // an ordinary escalation failure (see its javadoc); this catches a genuinely
            // unexpected failure (e.g. malformed toolInput) the same way every other builtin
            // tool's call() does, rather than letting it kill the whole model turn.
            log.warn("request_human tool call failed", e);
            return errorJson("Could not request a human right now: " + e.getMessage());
        }
    }

    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }
}
