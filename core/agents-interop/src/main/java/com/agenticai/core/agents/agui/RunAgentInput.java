package com.agenticai.core.agents.agui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * AG-UI's {@code RunAgentInput} - the request body every AG-UI client ({@code
 * @ag-ui/client}'s {@code HttpAgent}, CopilotKit, a hand-rolled fetch) POSTs
 * to start a run. Field names and shapes follow the protocol's TypeScript
 * definitions exactly, because the client serializes them and we do not get
 * to choose. Everything is {@link JsonIgnoreProperties#ignoreUnknown()
 * unknown-tolerant}: the protocol is versioned by addition, and a newer
 * client sending a field this server has not learned yet must not 400.
 *
 * <h2>What this server actually uses, week one</h2>
 * <ul>
 *   <li>{@code threadId} is the platform's {@code sessionId} - it scopes the
 *       Redis history the turn replays, so an AG-UI client that keeps its
 *       threadId gets a real multi-turn conversation.</li>
 *   <li>The LAST {@code user}-role message is the turn's {@code
 *       ChatRequest.message}. Earlier messages are NOT replayed into the model
 *       - the server already holds the thread's history under {@code threadId}
 *       and replaying the client's copy on top would double every turn. This
 *       is the same "server owns history" posture the chat endpoint has.</li>
 *   <li>{@code runId} is echoed back on {@code RUN_STARTED}/{@code
 *       RUN_FINISHED}; generated when the client omitted it.</li>
 * </ul>
 * {@code tools} (client-side tools the front end offers), {@code context},
 * {@code state}, {@code parentRunId} and {@code forwardedProps} are accepted
 * and ignored: the agent's own server-side tools run exactly as they do on
 * the chat endpoint. A client-side tool call would require the run to pause
 * for the client's {@code TOOL_CALL_RESULT} on a follow-up request, which the
 * platform's synchronous tool loop does not do today - so those tools are
 * simply never offered to the model rather than offered and left dangling.
 * See {@link AgUiRunController} for the request-level validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunAgentInput(
    String threadId,
    String runId,
    String parentRunId,
    JsonNode state,
    List<InputMessage> messages,
    List<Tool> tools,
    List<ContextItem> context,
    JsonNode forwardedProps
) {

    /**
     * One entry of {@code messages}. {@code content} is a {@link JsonNode}
     * rather than a {@code String} because the protocol allows a user message's
     * content to be either a plain string or an {@code InputContent[]} of typed
     * parts ({@code {type:"text", text}}, plus binary parts this server does not
     * consume) - see {@link #text()}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputMessage(
        String id,
        String role,
        JsonNode content,
        List<JsonNode> toolCalls,
        String toolCallId,
        String name
    ) {
        /**
         * The message's text: the string itself, or every {@code text} part of
         * a parts array joined with newlines (binary parts contribute nothing).
         * Empty, never null, for a message with no usable text.
         */
        public String text() {
            if (content == null || content.isNull()) {
                return "";
            }
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode part : content) {
                    if (part != null && part.isObject() && "text".equals(part.path("type").asText(null))) {
                        String text = part.path("text").asText("");
                        if (!text.isEmpty()) {
                            if (!joined.isEmpty()) {
                                joined.append('\n');
                            }
                            joined.append(text);
                        }
                    }
                }
                return joined.toString();
            }
            return "";
        }
    }

    /** A client-side tool definition ({@code name}, {@code description}, JSON-schema {@code parameters}). Accepted, not used - see the class javadoc. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tool(String name, String description, JsonNode parameters) {
    }

    /** A {@code context} entry ({@code description}, {@code value}). Accepted, not used - see the class javadoc. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContextItem(String description, String value) {
    }

    /**
     * The text of the last {@code user}-role message, or {@code null} when the
     * input carries none (an empty {@code messages}, or only assistant/system/
     * tool messages) - the controller turns that into a 400.
     */
    public String lastUserMessageText() {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            InputMessage candidate = messages.get(i);
            if (candidate != null && "user".equalsIgnoreCase(candidate.role())) {
                return candidate.text();
            }
        }
        return null;
    }
}
