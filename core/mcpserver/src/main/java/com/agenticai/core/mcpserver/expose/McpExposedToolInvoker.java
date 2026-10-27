package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.mcpserver.McpServerRateLimiter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowBackgroundRunService;
import com.agenticai.core.workflow.WorkflowRunNotFoundException;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Executes a call to one of the dynamic {@code agent_*} / {@code workflow_*} / {@code get_run}
 * tools for an authenticated MCP caller. Everything an external client can trigger funnels through
 * the SAME entry points the platform's own API runs use, so the tool guard, autonomy tier,
 * guardrails and tenant kill switch apply unchanged:
 *
 * <ul>
 *   <li>agents - {@link AgentChatService#chat} (the chokepoint {@code call_agent} and the console
 *       use), under the same {@code mcp-external:<grantId>} subject so the per-user budget, {@code
 *       tool_call_log.mcp_grant_id} tagging and the grant-scoped audit trail line up;
 *   <li>workflows - {@link WorkflowBackgroundRunService#start} (the fire-and-poll path behind
 *       {@code POST /run} with {@code background: true}), then a bounded wait, so a long run
 *       returns a {@code run_id} instead of holding a request open past the edge proxy's limit.
 * </ul>
 *
 * <p><b>Confirmation fails closed.</b> A gated tool call inside an agent turn is PARKED by the
 * platform's approval gate (it is not executed; a PENDING {@code ApprovalRequest} is recorded and a
 * human decides in the Approvals inbox). MCP has no channel to answer that, so after the turn this
 * class looks for requests parked for THIS turn's session and, if there are any, returns an error
 * result naming them instead of the reply - the caller is never told the action happened, and
 * nothing is ever auto-approved. A workflow run that reaches AWAITING_APPROVAL / AWAITING_INPUT is
 * reported the same way. (The parked request itself is left for a human, exactly as for an API run:
 * approving it later does run the action.)
 *
 * <p><b>Spend.</b> There is no way to abort an LLM turn mid-flight at a dollar threshold, so the
 * existing machinery is reused rather than a second meter invented: a per-user budget pre-flight
 * ({@link PerUserBudgetService#checkBudget}, same identity as the turn itself), the agent's own run
 * cap and the per-user reservation inside {@code chat()}, plus a per-grant invocation rate limit
 * ({@link McpServerRateLimiter#allowExposedInvocation}). The "cost" returned to the caller is the
 * change in that user's metered spend across the call (agents) or the sum of recorded step costs
 * (workflows) - approximate, absent when the call ran on a BYOK key (not metered here).
 *
 * <p>Every invocation, including every refusal, is written to the audit log with the existing
 * {@link AuditLogService}, tagged {@code [channel=mcp]}. Message and input TEXT is never audited.
 */
public class McpExposedToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(McpExposedToolInvoker.class);
    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final int MAX_RUN_SCAN = 100;

    /**
     * Tunables, bound from {@code app.mcp-server.expose-agents.*} by {@link McpExposureConfig}.
     *
     * @param agentTimeoutSeconds longest an agent_* call may block the request
     * @param workflowWaitSeconds how long a workflow_* call waits before returning status RUNNING +
     *     run_id
     * @param pollIntervalMillis poll interval while waiting on a workflow run
     * @param maxInvocationsPerMinute per-grant cap on exposed-tool invocations
     * @param maxInputChars longest accepted message / workflow input
     * @param maxOutputChars longest reply / workflow output returned
     */
    public record Settings(
            long agentTimeoutSeconds,
            long workflowWaitSeconds,
            long pollIntervalMillis,
            long maxInvocationsPerMinute,
            int maxInputChars,
            int maxOutputChars) {}

    private final AgentChatService agentChatService;
    private final WorkflowService workflowService;
    private final WorkflowBackgroundRunService backgroundRuns;
    private final AuditLogService auditLogService;
    private final McpServerRateLimiter rateLimiter;
    private final ObjectProvider<PerUserBudgetService> budgetService;
    private final ObjectProvider<ApprovalRequestRepository> approvals;
    private final McpExposedToolCatalog catalog;
    private final Settings settings;

    public McpExposedToolInvoker(
            AgentChatService agentChatService,
            WorkflowService workflowService,
            WorkflowBackgroundRunService backgroundRuns,
            AuditLogService auditLogService,
            McpServerRateLimiter rateLimiter,
            ObjectProvider<PerUserBudgetService> budgetService,
            ObjectProvider<ApprovalRequestRepository> approvals,
            McpExposedToolCatalog catalog,
            Settings settings) {
        this.agentChatService = agentChatService;
        this.workflowService = workflowService;
        this.backgroundRuns = backgroundRuns;
        this.auditLogService = auditLogService;
        this.rateLimiter = rateLimiter;
        this.budgetService = budgetService;
        this.approvals = approvals;
        this.catalog = catalog;
        this.settings = settings;
    }

    /**
     * Runs {@code tool} for {@code caller}. Never throws - every failure is a {@link
     * McpToolResult#failure}.
     */
    public McpToolResult invoke(McpCallerContext caller, McpExposedTool tool, JsonNode arguments) {
        try {
            if (!isStillAuthorized(caller, tool)) {
                recordDenied(caller, tool.name());
                return McpToolResult.failure("This API key is not authorized to call that tool.");
            }
            if (!rateLimiter.allowExposedInvocation(
                    caller.grantId(), settings.maxInvocationsPerMinute())) {
                audit(caller, tool, AuditOutcome.FAILURE, "rate limited");
                return McpToolResult.failure(
                        "Too many tool invocations for this API key - try again shortly.");
            }
            return switch (tool.target()) {
                case AGENT -> invokeAgent(caller, tool, arguments);
                case WORKFLOW -> invokeWorkflow(caller, tool, arguments);
                case GET_RUN -> getRun(caller, tool, arguments);
            };
        } catch (RuntimeException e) {
            log.warn("Exposed MCP tool {} failed for grant {}", tool.name(), caller.grantId(), e);
            audit(caller, tool, AuditOutcome.FAILURE, "failed: " + e.getClass().getSimpleName());
            return McpToolResult.failure("Tool call failed: " + shorten(e.getMessage()));
        }
    }

    /**
     * Records a refused call (unknown / not exposed / not granted tool name) in the audit log. Best
     * effort.
     */
    public void recordDenied(McpCallerContext caller, String toolName) {
        auditRaw(
                caller.grantId(),
                "mcp.expose.denied",
                "McpTool",
                sanitize(toolName),
                AuditOutcome.FAILURE,
                "Tool '"
                        + sanitize(toolName)
                        + "' is not exposed to MCP grant "
                        + caller.grantId()
                        + ".");
    }

    // ---- agent_* -----------------------------------------------------------------------------

    private McpToolResult invokeAgent(
            McpCallerContext caller, McpExposedTool tool, JsonNode arguments) {
        String message = text(arguments, "message");
        if (message == null || message.isBlank()) {
            return McpToolResult.failure("message is required.");
        }
        if (message.length() > settings.maxInputChars()) {
            return McpToolResult.failure(
                    "message is too long (limit " + settings.maxInputChars() + " characters).");
        }
        String conversationId = text(arguments, "conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        } else if (!CONVERSATION_ID.matcher(conversationId).matches()) {
            return McpToolResult.failure(
                    "conversationId may only contain letters, digits and . _ : - (max 64"
                            + " characters).");
        }
        // Namespaced per grant: a client can only ever continue conversations created through its
        // own key - supplying someone else's session id reaches an empty, separate session.
        String sessionId = "mcp-" + caller.grantId() + "-" + conversationId;
        String subjectKey = "mcp-external:" + caller.grantId();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);

        String budgetMessage = budgetPreflight(tenantId, subjectKey);
        if (budgetMessage != null) {
            audit(caller, tool, AuditOutcome.FAILURE, "budget exhausted");
            return McpToolResult.failure(budgetMessage);
        }
        BigDecimal spentBefore = spentUsd(tenantId, subjectKey);
        Instant since = Instant.now().minusSeconds(1);

        String reply;
        try {
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(tool.resourceId(), sessionId, subjectKey, message)
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            turnContext.taint().mark("inbound:mcp");
            reply =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .collectList()
                            .map(chunks -> String.join("", chunks))
                            .block(Duration.ofSeconds(settings.agentTimeoutSeconds()));
        } catch (IllegalStateException timeout) {
            // Reactor's block(Duration) signals a timeout as IllegalStateException("Timeout on
            // blocking read ...");
            // any other IllegalStateException is a real failure and goes to invoke()'s generic
            // handler.
            if (timeout.getMessage() == null
                    || !timeout.getMessage().startsWith("Timeout on blocking read")) {
                throw timeout;
            }
            audit(
                    caller,
                    tool,
                    AuditOutcome.FAILURE,
                    "timed out after " + settings.agentTimeoutSeconds() + "s");
            return McpToolResult.failure(
                    "The agent did not finish within "
                            + settings.agentTimeoutSeconds()
                            + " seconds. Try a shorter request.");
        }

        List<String> parked =
                pendingApprovalIds(
                        requestedBy ->
                                requestedBy.equals(
                                        "agent:" + tool.resourceId() + ":session:" + sessionId),
                        since);
        if (!parked.isEmpty()) {
            audit(
                    caller,
                    tool,
                    AuditOutcome.FAILURE,
                    "gated tool call needs human approval " + parked);
            return McpToolResult.failure(approvalMessage(parked));
        }

        BigDecimal spentAfter = spentUsd(tenantId, subjectKey);
        String costNote =
                spentBefore != null && spentAfter != null && spentAfter.compareTo(spentBefore) >= 0
                        ? "approx cost: $" + spentAfter.subtract(spentBefore).toPlainString()
                        : "cost: not metered";
        audit(caller, tool, AuditOutcome.SUCCESS, "conversation " + conversationId);
        return McpToolResult.ok(
                McpWorkflowRunResult.truncate(
                        reply == null ? "" : reply, settings.maxOutputChars()),
                "[vegaduta] conversationId=" + conversationId + " | " + costNote);
    }

    // ---- workflow_* / get_run ----------------------------------------------------------------

    private McpToolResult invokeWorkflow(
            McpCallerContext caller, McpExposedTool tool, JsonNode arguments) {
        String input = McpWorkflowInputSchema.toRunInput(arguments);
        if (input.length() > settings.maxInputChars()) {
            return McpToolResult.failure(
                    "input is too long (limit " + settings.maxInputChars() + " characters).");
        }
        String requestedBy = "mcp-external:" + caller.grantId();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String budgetMessage = budgetPreflight(tenantId, requestedBy);
        if (budgetMessage != null) {
            audit(caller, tool, AuditOutcome.FAILURE, "budget exhausted");
            return McpToolResult.failure(budgetMessage);
        }

        WorkflowRunResponse run =
                backgroundRuns.start(tool.resourceId(), input, requestedBy, null, List.of());
        long deadline =
                System.nanoTime() + Duration.ofSeconds(settings.workflowWaitSeconds()).toNanos();
        while (run.status() == WorkflowRunStatus.RUNNING && System.nanoTime() < deadline) {
            if (!sleepQuietly(settings.pollIntervalMillis())) {
                break;
            }
            run = workflowService.getRun(tool.resourceId(), run.id());
        }
        return runResult(caller, tool, run);
    }

    private McpToolResult getRun(McpCallerContext caller, McpExposedTool tool, JsonNode arguments) {
        String runIdRaw = text(arguments, "run_id");
        UUID runId;
        try {
            runId = runIdRaw == null ? null : UUID.fromString(runIdRaw.trim());
        } catch (IllegalArgumentException e) {
            runId = null;
        }
        if (runId == null) {
            return McpToolResult.failure(
                    "run_id is required and must be the run_id a workflow_* tool returned.");
        }
        // Authorization is intrinsic: a run is only looked up under the workflows THIS caller can
        // see, and WorkflowService#getRun binds the run to that workflow id (404 on mismatch).
        int scanned = 0;
        for (McpExposedTool candidate : catalog.forCaller(caller)) {
            if (candidate.target() != McpExposedTool.Target.WORKFLOW || scanned++ >= MAX_RUN_SCAN) {
                continue;
            }
            try {
                WorkflowRunResponse run = workflowService.getRun(candidate.resourceId(), runId);
                return runResult(caller, tool, run);
            } catch (WorkflowRunNotFoundException notThisWorkflow) {
                // keep looking
            }
        }
        audit(caller, tool, AuditOutcome.FAILURE, "run not found for this key");
        return McpToolResult.failure("No run with that run_id is visible to this API key.");
    }

    /**
     * Maps a run (finished, waiting, or still running) to the tool result, failing closed on
     * anything needing a human.
     */
    private McpToolResult runResult(
            McpCallerContext caller, McpExposedTool tool, WorkflowRunResponse run) {
        String runIdText = run.id() == null ? "unknown" : run.id().toString();
        Instant since =
                run.startedAt() == null
                        ? Instant.now().minusSeconds(3600)
                        : run.startedAt().minusSeconds(1);
        String sessionSuffix = ":session:" + runIdText;
        List<String> parked =
                pendingApprovalIds(requestedBy -> requestedBy.endsWith(sessionSuffix), since);

        if (McpWorkflowRunResult.needsHuman(run.status()) || !parked.isEmpty()) {
            audit(
                    caller,
                    tool,
                    AuditOutcome.FAILURE,
                    "run " + runIdText + " needs a human (" + run.status() + ")");
            return McpToolResult.failure(
                    "Workflow run "
                            + runIdText
                            + " is waiting for a human in the platform ("
                            + run.status()
                            + (parked.isEmpty() ? "" : ", pending approvals " + parked)
                            + "). It cannot be answered over MCP and nothing was approved"
                            + " automatically. Once a person has acted, call get_run with this"
                            + " run_id.");
        }
        WorkflowRunStatus status = run.status();
        if (status == WorkflowRunStatus.FAILED || status == WorkflowRunStatus.CANCELLED) {
            audit(caller, tool, AuditOutcome.FAILURE, "run " + runIdText + " " + status);
            return new McpToolResult(
                    true,
                    List.of(McpWorkflowRunResult.toJson(run, settings.maxOutputChars(), null)));
        }
        boolean finished = status == WorkflowRunStatus.COMPLETED;
        String hint =
                finished
                        ? null
                        : "The run is still "
                                + status
                                + ". Call get_run with this run_id to check again.";
        audit(caller, tool, AuditOutcome.SUCCESS, "run " + runIdText + " " + status);
        return McpToolResult.ok(McpWorkflowRunResult.toJson(run, settings.maxOutputChars(), hint));
    }

    // ---- shared helpers ----------------------------------------------------------------------

    /**
     * Defense in depth: the catalog already filtered, but the grant sets are re-checked at the
     * point of execution.
     */
    private boolean isStillAuthorized(McpCallerContext caller, McpExposedTool tool) {
        return switch (tool.target()) {
            case AGENT -> caller.isAgentExposed(tool.resourceId());
            case WORKFLOW -> caller.isWorkflowExposed(tool.resourceId());
            case GET_RUN -> true;
        };
    }

    /**
     * Null when the call may proceed; otherwise the budget-exhausted message. Any metering failure
     * is fail-open (chat() enforces again).
     */
    private String budgetPreflight(String tenantId, String subjectKey) {
        PerUserBudgetService budgets = budgetService.getIfAvailable();
        if (budgets == null || tenantId == null) {
            return null;
        }
        try {
            budgets.checkBudget(tenantId, subjectKey, PerUserBudgetService.Modality.TEXT);
            return null;
        } catch (PerUserBudgetExhaustedException exhausted) {
            return exhausted.getMessage();
        } catch (RuntimeException meteringFailure) {
            log.debug(
                    "Per-user budget pre-flight unavailable for {} - continuing",
                    subjectKey,
                    meteringFailure);
            return null;
        }
    }

    private BigDecimal spentUsd(String tenantId, String subjectKey) {
        PerUserBudgetService budgets = budgetService.getIfAvailable();
        if (budgets == null || tenantId == null) {
            return null;
        }
        try {
            return budgets.status(tenantId, subjectKey, PerUserBudgetService.Modality.TEXT)
                    .spentUsd();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<String> pendingApprovalIds(Predicate<String> requestedByMatches, Instant since) {
        ApprovalRequestRepository repository = approvals.getIfAvailable();
        if (repository == null) {
            return List.of();
        }
        try {
            return repository.findByStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING).stream()
                    .filter(
                            request ->
                                    request.getCreatedAt() != null
                                            && !request.getCreatedAt().isBefore(since))
                    .filter(
                            request ->
                                    request.getRequestedBy() != null
                                            && requestedByMatches.test(request.getRequestedBy()))
                    .map(ApprovalRequest::getId)
                    .map(UUID::toString)
                    .toList();
        } catch (RuntimeException e) {
            // The gate already parked (not executed) the action; this lookup only improves the
            // message.
            log.warn("Could not look up pending approvals after an exposed MCP tool call", e);
            return List.of();
        }
    }

    private static String approvalMessage(List<String> approvalIds) {
        return "The agent tried an action that requires human approval (approval request(s) "
                + approvalIds
                + "). Approvals cannot be answered over MCP, so the action was NOT run and nothing"
                + " was approved automatically. A person can review it in the platform's Approvals"
                + " inbox.";
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String text(JsonNode arguments, String field) {
        if (arguments == null || !arguments.isObject()) {
            return null;
        }
        JsonNode value = arguments.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void audit(
            McpCallerContext caller, McpExposedTool tool, AuditOutcome outcome, String detail) {
        String action =
                switch (tool.target()) {
                    case AGENT -> "mcp.expose.call_agent";
                    case WORKFLOW -> "mcp.expose.run_workflow";
                    case GET_RUN -> "mcp.expose.get_run";
                };
        String entityType =
                tool.target() == McpExposedTool.Target.AGENT
                        ? "Agent"
                        : tool.target() == McpExposedTool.Target.WORKFLOW
                                ? "Workflow"
                                : "WorkflowRun";
        String entityId = tool.resourceId() == null ? null : tool.resourceId().toString();
        auditRaw(
                caller.grantId(),
                action,
                entityType,
                entityId,
                outcome,
                "Tool '" + tool.name() + "' via MCP grant " + caller.grantId() + ": " + detail);
    }

    /**
     * Best-effort, same "logging must never change the real outcome" posture as the static MCP
     * tools.
     */
    private void auditRaw(
            UUID grantId,
            String action,
            String entityType,
            String entityId,
            AuditOutcome outcome,
            String detail) {
        try {
            auditLogService.record(
                    "mcp-external:" + grantId,
                    action,
                    entityType,
                    entityId,
                    outcome,
                    "[channel=mcp] " + detail);
        } catch (RuntimeException auditFailure) {
            log.warn(
                    "Failed to record audit log entry for exposed MCP tool call (grant {})",
                    grantId,
                    auditFailure);
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_.:-]", "?");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static String shorten(String message) {
        if (message == null) {
            return "unexpected error";
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
