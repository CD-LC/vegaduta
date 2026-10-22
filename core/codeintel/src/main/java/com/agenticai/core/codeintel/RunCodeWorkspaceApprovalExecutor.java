package com.agenticai.core.codeintel;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.json.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a command inside a code-intel scratch workspace, ONLY once a human has approved the request
 * (or an explicit per-tenant {@code ApprovalPolicyOverride} has opted this action type out of
 * approval - see {@code ApprovalPolicyService}) - the registered {@link ApprovalActionExecutor} for
 * {@link #ACTION_TYPE}, following exactly the same "callers never invoke this class directly"
 * contract {@code TwilioWhatsAppChannelAdapter} established: {@link RunInCodeWorkspaceToolCallback}
 * calls {@code HumanApprovalGateService.evaluate(ACTION_TYPE, ...)} and this class only runs once
 * {@code HumanApprovalGateService.approve()} (or {@code evaluate}'s own synchronous auto-approve
 * path) invokes it.
 *
 * <p><b>Two independent gates, deliberately.</b> By the time this class runs, this platform's OWN
 * human-approval gate has already said yes. code-intel's OWN internal guardrail (code-intel's
 * {@code policy.py} six-gate model) is evaluated AGAIN, independently, when {@link
 * CodeIntelClient#runInWorkspace} actually calls the service - the service may still return its own
 * 403 (denied by its policy) or even its own 428 (its own, separate, currently-disconnected
 * approval flow asking for sign-off a SECOND time - see {@code code-intel/app/main.py}'s {@code
 * ApprovalRequiredError} handler). Defense in depth here is intentional, not redundancy - see this
 * effort's design brief. This executor does NOT attempt to auto-navigate code-intel's own {@code
 * /approvals} API if that happens; {@link CodeWorkspaceResultParser} just surfaces the rejection as
 * this request's execution error rather than silently swallowing it. That residual gap (a run could
 * need BOTH this platform's approval AND a separate trip through code-intel's own disconnected
 * approvals UI) is real and not solved by this change - stated here rather than papered over.
 *
 * <p><b>Run output now reaches the approver.</b> {@link ApprovalActionExecutor#execute} still
 * returns {@code void}, but this executor writes the run's actual stdout/stderr/exit code onto
 * {@link ApprovalRequest#recordExecutionResult} and saves it explicitly via {@link
 * ApprovalRequestRepository} right after the run completes - necessary because {@code
 * HumanApprovalGateService} invokes {@code execute} OUTSIDE any database transaction and only
 * persists state itself on the exception path, never on a plain successful return (see that class's
 * javadoc). {@code GET /api/approvals/{id}} echoes this back as {@code executionResult}, and the
 * Approvals screen renders it next to the existing execution-error block. This covers BOTH a
 * genuinely successful command and a command that ran but exited non-zero - {@link
 * CodeWorkspaceResultParser#checkForFailure} deliberately treats the latter as a normal outcome,
 * not a thrown executor failure, so it would otherwise never have been recorded anywhere but the
 * application log. A human can still cross-check file effects via {@link
 * DiffCodeWorkspaceToolCallback} or code-intel's own {@code GET /audit} endpoint; this closes the
 * specific "approved a run, never saw what happened" gap, not a rebuild of code-intel's own audit
 * trail.
 */
// 2026-07-20 hotfix: this bean unconditionally depended on CodeIntelClient,
// which is itself @ConditionalOnProperty(code-intel.enabled=true) - default
// false. Without this annotation, the WHOLE application fails to start
// whenever code-intel is off (the out-of-the-box default), not just this
// feature - confirmed via a real startup stack trace naming this exact
// class. Mirrors CodeIntelClient's own conditional so this bean only
// exists when its one dependency does.
@Component
@ConditionalOnBean(CodeIntelClient.class)
public class RunCodeWorkspaceApprovalExecutor implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "codeintel.workspace.run";

    /**
     * Per-stream (stdout/stderr) cap on {@link ApprovalRequest#recordExecutionResult}'s payload, so
     * a runaway command's output can't bloat that row unboundedly.
     */
    private static final int MAX_STREAM_CHARS = 32 * 1024;

    private static final String TRUNCATED_SUFFIX = "\n...[truncated]";

    private static final Logger log =
            LoggerFactory.getLogger(RunCodeWorkspaceApprovalExecutor.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;
    private final ApprovalRequestRepository requestRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RunCodeWorkspaceApprovalExecutor(
            CodeIntelClient client,
            ApprovalRequestRepository requestRepository,
            ApplicationEventPublisher eventPublisher) {
        this.client = client;
        this.requestRepository = requestRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        RunPayload payload = parsePayload(request.getPayloadJson());
        int attempts = payload.attempts() < 1 ? 1 : payload.attempts();
        String raw =
                client.runInWorkspace(
                        payload.repoId(),
                        payload.workspaceId(),
                        payload.command(),
                        payload.timeout(),
                        attempts,
                        null);
        JsonNode result = readTree(raw);
        CodeWorkspaceResultParser.checkForFailure("run_in_code_workspace", result);
        boolean success = result.path("success").asBoolean(false);
        log.info(
                "code-intel run in workspace {} (repo {}) approved and executed - success={}",
                payload.workspaceId(),
                payload.repoId(),
                success);
        persistExecutionResult(request, result, success);
    }

    /**
     * The only place a completed run's stdout/stderr/exit code ever gets saved. {@code
     * HumanApprovalGateService.runExecutor}/{@code retryExecution} call {@link #execute} outside
     * any transaction and never persist anything themselves on a normal return - only the
     * thrown-exception path (a genuine rejection or transport failure, handled by {@link
     * #checkForFailure} upstream) gets recorded, via {@code
     * ApprovalRequestRecorder#recordExecutionFailed}. So a run that actually executed - whether the
     * command inside it succeeded or exited non-zero - needs this executor to save it explicitly,
     * via {@link ApprovalRequestRepository} directly (the package-private {@code
     * ApprovalRequestRecorder} isn't visible outside {@code core/approval}). Bounded to keep a
     * runaway command's output from bloating this row without limit - see {@link
     * #MAX_STREAM_CHARS}.
     */
    private void persistExecutionResult(ApprovalRequest request, JsonNode result, boolean success) {
        JsonNode finalAttempt = result.path("final");
        String summary =
                "success="
                        + success
                        + ", exit_code="
                        + finalAttempt.path("exit_code").asString("?")
                        + ", duration_seconds="
                        + finalAttempt.path("duration_seconds").asString("?")
                        + ", attempts_used="
                        + result.path("attempts_used").asString("?")
                        + ", timed_out="
                        + finalAttempt.path("timed_out").asBoolean(false)
                        + "\n--- stdout ---\n"
                        + truncate(finalAttempt.path("stdout").asString(""))
                        + "\n--- stderr ---\n"
                        + truncate(finalAttempt.path("stderr").asString(""));
        request.recordExecutionResult(summary);
        requestRepository.save(request);
        // Same reasoning as AgentToolCallApprovalExecutor#persistExecutionResult:
        // this write never reaches ApprovalRequestRecorder (package-private to
        // core/approval), so this is the one place it can announce that this
        // request's execution just became durable - whether or not anything in
        // this application actually cares (today, only AgentJobApprovalSweep does,
        // and only for mcp_tool.* requests; a codeintel.workspace.run request is
        // never job-parked, so in practice this publishes to no listener, which is
        // a correct and cheap no-op, not a bug).
        eventPublisher.publishEvent(
                new ApprovalRequestExecutionRecordedEvent(
                        this, request.getId(), request.getRequestedBy()));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_STREAM_CHARS) {
            return s;
        }
        return s.substring(0, MAX_STREAM_CHARS) + TRUNCATED_SUFFIX;
    }

    private RunPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new CodeWorkspaceExecutionException("Approval request has no payload to run");
        }
        try {
            return MAPPER.readValue(payloadJson, RunPayload.class);
        } catch (Exception e) {
            throw new CodeWorkspaceExecutionException(
                    "Could not parse run_in_code_workspace payload: " + e.getMessage(), e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            // CodeIntelClient always returns a valid JSON string (either the
            // service's own body or errorJson's synthesized fallback) - an
            // unparseable response means something is wrong at the
            // transport level, worth a retry.
            throw new RetryableCodeWorkspaceExecutionException(
                    "run_in_code_workspace returned a non-JSON response: " + e.getMessage());
        }
    }

    /**
     * {@code adminOnly} is read by {@code ApprovalController#enforceApproverAuthorization} straight
     * off the stored payload JSON - see {@link RunInCodeWorkspaceToolCallback}.
     */
    public record RunPayload(
            String repoId,
            String workspaceId,
            String command,
            Integer timeout,
            int attempts,
            boolean adminOnly) {}
}
