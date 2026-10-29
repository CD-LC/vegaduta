package com.agenticai.core.mcpserver.tools;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * The one real "do something" MCP tool this server exposes - routes an external MCP client's call
 * straight into {@link AgentChatService#chat}, the EXACT SAME chokepoint a web-console or
 * omnichannel chat turn goes through. That is deliberate and load-bearing for this build's
 * governance requirement: {@code chat()} already re-runs the tenant kill-switch check, and
 * internally calls {@code AgentToolProvider#resolveToolsFor} to build that turn's tool list,
 * wrapping every one of THAT agent's own tools in {@code GuardedToolCallback}
 * (autonomy/step-ceiling enforcement) and {@code LoggingToolCallback} (tool_call_log) exactly as it
 * would for any other caller - so an externally-triggered agent turn is governed identically to an
 * internal one, with zero parallel code path. See this module's top-level report for the one honest
 * gap: there is no distinct "an external MCP grant triggered this turn" audit ROW today, only the
 * {@code subjectKey}/{@code requestedBy}-style naming convention threaded through to whatever this
 * agent's own tool calls log.
 *
 * <p><b>Per-call authorization</b> happens here, not via which tools are advertised (see {@code
 * McpServerToolConfig}'s javadoc for why): the requested {@code agent_id} MUST be in {@link
 * McpCallerContext}'s exposed-agent set for the authenticated grant, checked BEFORE {@code chat()}
 * is ever invoked - an id outside that set is rejected here, it never reaches {@code
 * AgentChatService}.
 *
 * <p><b>Known limitation, deliberately flagged:</b> {@code chat()} returns a reactive {@code
 * Flux<String>}; MCP's synchronous tool-call protocol needs a single String back, so this blocks on
 * it - the same {@code .block()} this codebase's own {@code TwilioWebhookController} uses, EXCEPT
 * that caller explicitly moved the blocking call onto a dedicated background thread specifically to
 * avoid tying up a servlet request thread (see its own comment); this tool call still runs (and
 * blocks) directly on the request thread handling the inbound MCP call. Acceptable for this
 * scaffold, but a real production concern for a high-traffic external surface - not fixed in this
 * build.
 */
public class McpCallAgentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpCallAgentToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final AgentChatService agentChatService;
    private final AuditLogService auditLogService;

    public McpCallAgentToolCallback(
            AgentChatService agentChatService, AuditLogService auditLogService) {
        this.agentChatService = agentChatService;
        this.auditLogService = auditLogService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("call_agent")
                .description(
                        "Sends a message to one of your organization's agents (see list_my_agents"
                                + " for valid agent_id values) and returns its reply. Pass the same"
                                + " session_id on a follow-up call to continue the same conversation"
                                + " with short-term memory; omit it to start a fresh conversation.")
                .inputSchema(
                        """
{
    "type": "object",
    "properties": {
        "agent_id": {"type": "string", "description": "One of the ids returned by list_my_agents."},
        "message": {"type": "string", "description": "The message to send to the agent."},
        "session_id": {"type": "string", "description": "Optional - reuse a prior call's session_id to continue that conversation."}
    },
    "required": ["agent_id", "message"]
}
""")
                .build();
    }

    @Override
    public String call(String toolInput) {
        McpCallerContext caller =
                McpCallerContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "call_agent invoked with no McpCallerContext set -"
                                                        + " McpApiKeyAuthFilter should have rejected"
                                                        + " this request before it ever reached tool"
                                                        + " dispatch."));
        try {
            JsonNode input = MAPPER.readTree(toolInput);
            String agentIdRaw = textOrNull(input, "agent_id");
            String message = textOrNull(input, "message");
            String sessionIdRaw = textOrNull(input, "session_id");

            if (!StringUtils.hasText(agentIdRaw) || !StringUtils.hasText(message)) {
                return errorJson("agent_id and message are both required.");
            }
            UUID agentId;
            try {
                agentId = UUID.fromString(agentIdRaw);
            } catch (IllegalArgumentException e) {
                return errorJson(
                        "agent_id is not a valid id - call list_my_agents to see valid ids.");
            }
            if (!caller.isAgentExposed(agentId)) {
                log.warn(
                        "MCP grant {} attempted to call agent {} which is not in its exposed-agent"
                                + " set - rejected",
                        caller.grantId(),
                        agentId);
                // Data-governance audit fix (2026-07-20) - a rejected out-of-scope attempt is
                // itself compliance-relevant ("did this grant ever try to reach past what it was
                // curated for"), so it goes to audit_log_entry too, not just the tool_call_log
                // attribution added for successful calls below (which never reach AgentChatService
                // for a rejected call, so there is no tool_call_log row to tag at all here).
                // Best-effort, same "never let logging break the real call" posture
                // LoggingToolCallback's own toolCallLogRepository.save() already takes - a failure
                // recording the rejection must never turn into some OTHER response than the
                // "not authorized" one this branch already decided on.
                recordAudit(
                        caller.grantId(),
                        agentIdRaw,
                        AuditOutcome.FAILURE,
                        "MCP grant "
                                + caller.grantId()
                                + " attempted to call agent "
                                + agentIdRaw
                                + ", which is outside its exposed-agent set.");
                return errorJson(
                        "This API key is not authorized to call that agent - call list_my_agents to"
                                + " see which agents you can use.");
            }

            // Data-governance audit fix (2026-07-20): when the caller doesn't supply its own
            // session_id, mint one that carries this grant's id rather than a bare random UUID.
            // tool_call_log has no dedicated "which MCP grant triggered this" column (see this
            // class's own javadoc for that known gap), but session_id is already persisted
            // verbatim on every ToolCallLog row for the turn - prefixing it makes an MCP-triggered
            // tool call traceable back to its grant without a schema change. The caller echoes this
            // same session_id back on follow-up calls (per this tool's own description), so
            // traceability is preserved for the whole conversation, not just the first turn.
            String sessionId =
                    StringUtils.hasText(sessionIdRaw)
                            ? sessionIdRaw
                            : "mcp-" + caller.grantId() + "-" + UUID.randomUUID();
            String subjectKey = "mcp-external:" + caller.grantId();
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(agentId, sessionId, subjectKey, message).build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            turnContext.taint().mark("inbound:mcp");
            String reply =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
                            .block();
            // Data-governance audit fix (2026-07-20) - a coarse, one-row-per-call-agent-invocation
            // audit trail entry, visible in the existing tenant-admin Audit Log screen with zero UI
            // work (AuditLogController/AuditLogPanel.js already render whatever actionType shows up
            // here - see AuditLogService#distinctActionTypes). Complements, not replaces, the
            // granular per-tool-call tool_call_log.mcp_grant_id tagging added at the same time
            // (AgentToolProvider#guardAndLog/LoggingToolCallback) - that one answers "which
            // specific
            // tool calls did this turn make," this one answers "was call_agent invoked at all,
            // when,
            // for which agent, and did it succeed" without having to reconstruct that from a list
            // of
            // tool-call rows. Best-effort (see recordAudit's own javadoc) - deliberately recorded
            // AFTER reply is already computed and BEFORE the response is built, so a logging
            // failure here can never be mistaken by the outer catch below for the actual
            // agent-call having failed (it would otherwise turn a real SUCCESS into a reported
            // FAILURE and a second, misleading audit row).
            recordAudit(
                    caller.grantId(),
                    agentId.toString(),
                    AuditOutcome.SUCCESS,
                    "MCP grant "
                            + caller.grantId()
                            + " invoked agent "
                            + agentId
                            + " (session "
                            + sessionId
                            + ").");
            return MAPPER.writeValueAsString(
                    java.util.Map.of("reply", reply == null ? "" : reply, "session_id", sessionId));
        } catch (Exception e) {
            log.warn("call_agent tool call failed for grant {}", caller.grantId(), e);
            recordAudit(
                    caller.grantId(),
                    null,
                    AuditOutcome.FAILURE,
                    "MCP grant "
                            + caller.grantId()
                            + " call_agent invocation failed: "
                            + e.getMessage());
            return errorJson("Agent call failed: " + e.getMessage());
        }
    }

    /**
     * Best-effort {@link AuditLogService#record} wrapper - same "never let logging itself break the
     * real call" posture {@link com.agenticai.core.agents.tools.LoggingToolCallback}'s own {@code
     * toolCallLogRepository.save()} already takes for {@code tool_call_log}. A failure here must
     * never propagate and change what {@link #call(String)} was already about to return.
     */
    private void recordAudit(
            UUID grantId, String agentIdOrNull, AuditOutcome outcome, String detail) {
        try {
            auditLogService.record(
                    "mcp-external:" + grantId,
                    "mcp.grant.call_agent",
                    "Agent",
                    agentIdOrNull,
                    outcome,
                    detail);
        } catch (RuntimeException auditFailure) {
            log.warn(
                    "Failed to record audit log entry for MCP grant {} call_agent invocation",
                    grantId,
                    auditFailure);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(java.util.Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
        }
    }
}
