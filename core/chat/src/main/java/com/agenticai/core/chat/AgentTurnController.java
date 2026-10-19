package com.agenticai.core.chat;

import com.agenticai.core.chat.AgentTurn.Message;
import com.agenticai.core.chat.AgentTurn.Tool;
import com.agenticai.core.chat.AgentTurn.ToolCall;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/completions/agent-turn} - one hosted-model turn for a client-side tool-calling
 * agent. See {@link AgentTurnService} for the contract and the governance it reuses; this class is
 * the thin HTTP shell plus the request bounds.
 *
 * <p><b>Security posture.</b> Deliberately the same as {@link FimCompletionController}: not listed
 * in {@code SecurityConfig}, so it falls through to {@code anyRequest().authenticated()} - a valid
 * tenant JWT, no role restriction. Spend is bounded per tenant and per user by the entitlement gate
 * (monthly pool quota + per-user budget), not by a role; every authenticated member may chat on the
 * hosted pool, and this is that same inference. NOT permitAll.
 *
 * <p>Quota and budget rejections are deliberately not caught here: they are {@code
 * TenantLimitExceededException} (402/403 via {@code GlobalExceptionHandler}, in core/app, which
 * this module does not depend on) and the content-policy block is {@code
 * LlmGatewayContentPolicyViolationException}; both keep their standard rendering.
 */
@RestController
public class AgentTurnController {

    /** Request bounds. An agent conversation is large; these bound a request, not a task. */
    static final int MAX_MESSAGES = 400;

    static final int MAX_TOTAL_CHARS = 600_000;
    static final int MAX_TOOLS = 40;
    static final int MAX_TOOL_CALLS_PER_MESSAGE = 32;
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");

    private final AgentTurnService service;

    public AgentTurnController(AgentTurnService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/api/completions/agent-turn",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentTurn.Result agentTurn(@RequestBody AgentTurn.Request request) {
        validate(request);
        try {
            return service.turn(request);
        } catch (AgentTurnService.EntitlementsUnavailableException
                | AgentTurnService.NoModelAvailableException e) {
            // Nothing about the request is wrong - the deployment/pool is incomplete.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (AgentTurnService.BusyException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        } catch (AgentTurnService.UpstreamFailedException e) {
            // The provider's own error text may carry request detail; the log has it, the client
            // does not.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    static void validate(AgentTurn.Request request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw bad("'messages' is required");
        }
        List<Message> messages = request.messages();
        if (messages.size() > MAX_MESSAGES) {
            throw bad("at most " + MAX_MESSAGES + " messages per turn");
        }
        long chars = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m == null || m.role() == null || !ROLES.contains(m.role())) {
                throw bad(
                        "message " + (i + 1) + " needs a role of system, user, assistant or tool");
            }
            chars += m.content() == null ? 0 : m.content().length();
            if (m.toolCalls() != null) {
                if (m.toolCalls().size() > MAX_TOOL_CALLS_PER_MESSAGE
                        || !"assistant".equals(m.role())) {
                    throw bad(
                            "message "
                                    + (i + 1)
                                    + ": tool calls are only valid on assistant messages (max "
                                    + MAX_TOOL_CALLS_PER_MESSAGE
                                    + ")");
                }
                for (ToolCall call : m.toolCalls()) {
                    if (call == null
                            || call.id() == null
                            || call.name() == null
                            || !TOOL_NAME.matcher(call.name()).matches()) {
                        throw bad(
                                "message "
                                        + (i + 1)
                                        + ": every tool call needs an id and a valid name");
                    }
                }
            }
            if ("tool".equals(m.role()) && (m.toolCallId() == null || m.toolCallId().isBlank())) {
                throw bad("message " + (i + 1) + ": a tool message needs 'toolCallId'");
            }
        }
        if (chars > MAX_TOTAL_CHARS) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "the conversation must be at most "
                            + MAX_TOTAL_CHARS
                            + " characters - the client should trim older tool output");
        }
        String last = messages.get(messages.size() - 1).role();
        if (!"user".equals(last) && !"tool".equals(last)) {
            throw bad("the last message must be from the user or a tool result");
        }
        if (request.tools() != null) {
            if (request.tools().size() > MAX_TOOLS) {
                throw bad("at most " + MAX_TOOLS + " tools");
            }
            for (Tool tool : request.tools()) {
                if (tool == null
                        || tool.name() == null
                        || !TOOL_NAME.matcher(tool.name()).matches()) {
                    throw bad("every tool needs a name of letters, digits, '_' or '-' (max 64)");
                }
            }
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
