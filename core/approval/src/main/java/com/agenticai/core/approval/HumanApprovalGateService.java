package com.agenticai.core.approval;

import com.agenticai.core.rl.feedback.FeedbackService;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The single entry point every action that might need human sign-off goes
 * through. Nothing in this codebase should
 * call Twilio, Telegram, or any other external side-effecting API directly;
 * it calls {@link #evaluate} first, always. What happens next depends on
 * policy, not on the caller:
 *
 * <ul>
 *   <li><b>Auto-approved:</b> {@link #evaluate} runs the matching
 *       {@link ApprovalActionExecutor} itself, synchronously, before
 *       returning - by the time the caller gets back an
 *       {@link ApprovalEvaluation} with {@code approved=true}, the action has
 *       already happened. Callers must not also run the action themselves.</li>
 *   <li><b>Requires approval:</b> {@link #evaluate} creates a
 *       {@code PENDING} {@link ApprovalRequest} and returns immediately
 *       without running anything - the actual execution happens later, out
 *       of band, when {@link #approve} runs the same executor.</li>
 * </ul>
 *
 * <p>Both paths persist an {@link ApprovalRequest} row and run the action
 * through the exact same {@link #runExecutor} method - there is deliberately
 * only one code path that ever calls {@link ApprovalActionExecutor#execute},
 * so "every gated action was actually gated, whether or not a human needed
 * to look at it" is a property of this class's structure, not a convention
 * every caller has to get right on its own. An earlier version of this
 * class special-cased the auto-approved path to skip creating a request and
 * left execution to the caller - that was a real bug (an auto-approved send
 * never actually sent anything, since nothing ever called the executor for
 * it), caught before Stage 2 shipped, not after.
 *
 * <p><b>Transaction boundary, deliberately not on this class:</b> the
 * atomic "persist state + write an audit entry" work lives in
 * {@link ApprovalRequestRecorder} (its methods are {@code @Transactional}),
 * while calling {@link ApprovalActionExecutor#execute} - a potentially slow
 * outbound network call to a third-party channel API - happens here,
 * outside any database transaction. Wrapping this whole class in one
 * {@code @Transactional} method would hold a pooled DB connection open for
 * the duration of that external call, a well-known anti-pattern; found and
 * fixed during a design-principles review pass (the original version had no
 * transaction boundary anywhere in this flow at all).
 */
@Service
public class HumanApprovalGateService {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalGateService.class);

    private final ApprovalPolicyService policyService;
    private final ApprovalRequestRepository requestRepository;
    private final ApprovalActionExecutorRegistry executorRegistry;
    private final ApprovalRequestRecorder recorder;
    private final FeedbackService feedbackService;
    private final ObjectMapper objectMapper;

    // Autonomy/Guardrails Strategy Stage C retry-with-backoff config - see
    // application.yml's app.approvals.retry.* block for the tunable
    // defaults and their rationale.
    private final int maxRetryAttempts;
    private final long baseDelaySeconds;
    private final long maxDelaySeconds;

    public HumanApprovalGateService(
        ApprovalPolicyService policyService,
        ApprovalRequestRepository requestRepository,
        ApprovalActionExecutorRegistry executorRegistry,
        ApprovalRequestRecorder recorder,
        FeedbackService feedbackService,
        ObjectMapper objectMapper,
        @Value("${app.approvals.retry.max-attempts:5}") int maxRetryAttempts,
        @Value("${app.approvals.retry.base-delay-seconds:30}") long baseDelaySeconds,
        @Value("${app.approvals.retry.max-delay-seconds:3600}") long maxDelaySeconds
    ) {
        this.policyService = policyService;
        this.requestRepository = requestRepository;
        this.executorRegistry = executorRegistry;
        this.recorder = recorder;
        this.feedbackService = feedbackService;
        this.objectMapper = objectMapper;
        this.maxRetryAttempts = maxRetryAttempts;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
    }

    public ApprovalEvaluation evaluate(String actionType, String summary, String payloadJson) {
        return evaluate(actionType, summary, payloadJson, null);
    }

    public ApprovalEvaluation evaluate(String actionType, String summary, String payloadJson, String requestedBy) {
        boolean autoApproved = !policyService.requiresApproval(actionType);
        ApprovalRequest request = recorder.recordNew(actionType, summary, payloadJson, requestedBy, autoApproved);

        if (autoApproved) {
            runIfExecutorRegistered(request, requestedBy);
            return ApprovalEvaluation.autoApproved();
        }
        return ApprovalEvaluation.pending(request.getId());
    }

    public ApprovalRequest approve(UUID requestId, String decidedBy) {
        ApprovalRequest request = getPendingOrThrow(requestId);
        request = recorder.recordApproved(request, decidedBy);
        recordRlFeedback(request, true);
        runIfExecutorRegistered(request, decidedBy);
        return request;
    }

    static final int MAX_FORM_VALUE_CHARS = 2_000;

    /**
     * Approve AND hand back the answers to the fields the halted workflow node asked for
     * ({@code payload.formFields}). Only declared fields are kept, so this cannot be used to
     * smuggle arbitrary keys into a payload - and a request that declared none (every tool-call
     * approval) is approved exactly as before.
     */
    public ApprovalRequest approve(UUID requestId, String decidedBy, java.util.Map<String, String> formValues) {
        if (formValues != null && !formValues.isEmpty()) {
            ApprovalRequest request = getPendingOrThrow(requestId);
            String merged = mergeFormValues(request.getPayloadJson(), formValues);
            if (merged != null) {
                request.replacePayloadJson(merged);
                requestRepository.save(request);
            }
        }
        return approve(requestId, decidedBy);
    }

    private String mergeFormValues(String payloadJson, java.util.Map<String, String> formValues) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (!payload.isObject() || !payload.path("formFields").isArray() || !payload.path("resume").isObject()) {
                return null;
            }
            com.fasterxml.jackson.databind.node.ObjectNode values =
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("resume")).putObject("formValues");
            for (JsonNode field : payload.path("formFields")) {
                String name = field.asText("");
                String value = formValues.get(name);
                if (!name.isBlank() && value != null && !value.isBlank()) {
                    String trimmed = value.trim();
                    values.put(name, trimmed.length() > MAX_FORM_VALUE_CHARS ? trimmed.substring(0, MAX_FORM_VALUE_CHARS) : trimmed);
                }
            }
            return payload.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public ApprovalRequest reject(UUID requestId, String decidedBy, String reason) {
        ApprovalRequest request = getPendingOrThrow(requestId);
        request = recorder.recordRejected(request, decidedBy, reason);
        recordRlFeedback(request, false);
        notifyExecutorOfRejection(request, decidedBy, reason);
        return request;
    }

    /**
     * Rejection cleanup hook (see {@link ApprovalActionExecutor#onRejected}):
     * lets the executor that WOULD have run this action settle any external
     * state its pending request left suspended - a workflow run parked
     * AWAITING_APPROVAL being the motivating case. Deliberately after
     * {@code recordRejected} and strictly best-effort: the human's decision
     * is already durable, and no cleanup failure may un-decide it or turn
     * the reject call into a 5xx. Note this calls {@code onRejected}, never
     * {@code execute} - rejecting must never perform the gated action.
     */
    private void notifyExecutorOfRejection(ApprovalRequest request, String decidedBy, String reason) {
        executorRegistry.find(request.getActionType()).ifPresent(executor -> {
            try {
                executor.onRejected(request, decidedBy, reason);
            } catch (RuntimeException e) {
                log.warn("Rejection cleanup for request {} (action type '{}') failed - the rejection itself stands: {}",
                    request.getId(), request.getActionType(), e.getMessage());
            }
        });
    }

    /**
     * Approval feedback -&gt; RL reward loop hardening: previously an
     * approve/reject decision only gated execution and otherwise vanished -
     * it never reached {@code core/rl}'s learning loop the way a chat-UI
     * thumbs/rating signal does. Reuses the exact HUMAN-reward pipeline
     * {@link FeedbackService#recordApprovalDecision} already established
     * (itself reusing {@code FeedbackService#record}'s own
     * addRewardRecord -&gt; computeReward -&gt; bandit-update chain) rather than
     * inventing a second mechanism.
     *
     * <p>Scoped to workflow Approval-node decisions specifically (action
     * types shaped {@code "workflow.node.<type>"} - see {@code
     * OrchestratorService#authorizeSensitiveNode}): those are the only
     * approval requests whose {@code payloadJson} carries a workflow run id,
     * which doubles as the session id every Agent-node turn in that run was
     * recorded under (see {@code WorkflowNodeExecutor#executeAgentNode}).
     * Every other action type (channel sends, dynamic MCP tool-call gates)
     * has no such session to match against and is left untouched - calling
     * {@code recordApprovalDecision} with a blank/absent session id is
     * already a documented no-op there, but checking the action type here
     * avoids even trying for the common case (most approval requests are
     * NOT workflow nodes) and keeps the intent of this wiring legible at
     * the call site. Best-effort: this must never turn a successful
     * approve/reject decision into a failed request just because reward
     * bookkeeping hiccuped.
     */
    private void recordRlFeedback(ApprovalRequest request, boolean approved) {
        if (request.getActionType() == null || !request.getActionType().startsWith("workflow.node.")) {
            return;
        }
        try {
            String runId = extractRunId(request.getPayloadJson());
            feedbackService.recordApprovalDecision(runId, approved);
        } catch (RuntimeException e) {
            log.warn("Failed to record RL feedback for approval request {} ({}): {}",
                request.getId(), approved ? "approved" : "rejected", e.getMessage());
        }
    }

    private String extractRunId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            return payload.path("runId").isMissingNode() ? null : payload.path("runId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Autonomy/Guardrails Strategy Stage A - records a PENDING request for a
     * tool call that {@code GuardedToolCallback} already determined (via
     * {@link ApprovalPolicyService#requiresApproval}) needs human sign-off,
     * <b>without</b> going through {@link #evaluate}'s executor-lookup path
     * (nothing may execute at record time - execution happens on {@link
     * #approve}, like every other pending request).
     *
     * <p>Legacy signature, kept because rows it recorded may still be
     * PENDING in tenant schemas: {@code payloadJson} here is the raw tool
     * input with no replay envelope, so a replay of such a row has to
     * reconstruct the agent/session from {@code requestedBy}'s
     * {@code "agent:&lt;id&gt;:session:&lt;id&gt;"} label (see {@code
     * AgentToolCallApprovalExecutor}'s legacy-payload path in {@code
     * core/agents}). New recordings go through {@link
     * #recordToolCallForApproval(String, String, String, String, String,
     * UUID, String)} below, which captures everything replay needs
     * explicitly.
     */
    public ApprovalRequest recordToolCallForApproval(
        String actionType, String summary, String payloadJson, String requestedBy
    ) {
        return recorder.recordNew(actionType, summary, payloadJson, requestedBy, false);
    }

    /**
     * The replay-capable successor to the 4-arg overload above, closing the
     * "approving a gated tool call is audit-only" gap that overload's
     * javadoc used to state as an honest limitation: approval now actually
     * re-runs the original tool call, via the {@code "mcp_tool."}-family
     * {@link ApprovalActionExecutor} registered in {@code core/agents}
     * ({@code AgentToolCallApprovalExecutor}), which {@link #approve}'s
     * existing executor-lookup path finds through {@link
     * ApprovalActionExecutorRegistry#find}'s {@code supports()} fallback -
     * no new approval flow, the same {@link #runExecutor} chokepoint every
     * other gated action already goes through.
     *
     * <p>The persisted payload is a versioned envelope carrying everything
     * that executor needs to replay the call long after the recording
     * thread (and its {@code TenantContext}) is gone: the tool name, the
     * exact arguments, the agent/session that asked, and the tenant/subject
     * captured from the CURRENT thread's context - this method runs inside
     * {@code TenantContextPropagatingToolCallback}'s restored context, so
     * "current" here is genuinely the originating chat turn's tenant and
     * human subject, not whatever thread later approves the request.
     *
     * <p>Envelope construction is best-effort by design: if it fails for
     * any reason, the raw {@code argumentsJson} is recorded as the payload
     * (the legacy shape) rather than ever letting bookkeeping stop the gate
     * from recording a sensitive call - blocking-and-recording is this
     * method's contract; replayability is the bonus on top.
     */
    public ApprovalRequest recordToolCallForApproval(
        String actionType, String toolName, String summary, String argumentsJson,
        String requestedBy, UUID agentId, String sessionId
    ) {
        return recordToolCallForApproval(
            actionType, toolName, summary, argumentsJson, requestedBy, agentId, sessionId, null);
    }

    /**
     * As the 7-arg overload, plus {@code toolDigest}: a fingerprint of the tool's definition at the
     * moment approval was requested, stored in the envelope so the replay can refuse to run if the
     * tool behind the approved name has changed. {@code null} (every caller that predates this)
     * records no digest and replays as before.
     */
    public ApprovalRequest recordToolCallForApproval(
        String actionType, String toolName, String summary, String argumentsJson,
        String requestedBy, UUID agentId, String sessionId, String toolDigest
    ) {
        String payloadJson;
        try {
            payloadJson = buildToolCallReplayPayload(toolName, argumentsJson, agentId, sessionId, toolDigest);
        } catch (RuntimeException e) {
            log.warn("Failed to build replay envelope for gated tool call '{}' - recording raw arguments instead "
                + "(request will need the legacy replay path): {}", toolName, e.getMessage());
            payloadJson = argumentsJson;
        }
        return recorder.recordNew(actionType, summary, payloadJson, requestedBy, false);
    }

    /**
     * Shape: {@code {"replay": {"version": 1, "toolName", "agentId",
     * "sessionId", "tenantId", "subject"}, "arguments": {...}}}. The
     * top-level {@code "replay"} key is the discriminator {@code
     * AgentToolCallApprovalExecutor} uses to tell this envelope apart from a
     * legacy raw-arguments payload - raw tool input is model-authored and
     * could itself contain a {@code "replay"} key, which is why the executor
     * also requires {@code replay.toolName} before trusting it. Arguments
     * are embedded as a parsed node when they are valid JSON (readable on
     * the Approvals screen, semantically lossless on re-serialization);
     * an unparseable input is preserved verbatim under {@code
     * "argumentsRaw"} instead so replay hands the tool exactly what the
     * model originally produced.
     */
    private String buildToolCallReplayPayload(
        String toolName, String argumentsJson, UUID agentId, String sessionId, String toolDigest
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode replay = payload.putObject("replay");
        replay.put("version", 1);
        replay.put("toolName", toolName);
        replay.put("agentId", agentId != null ? agentId.toString() : null);
        replay.put("sessionId", sessionId);
        if (toolDigest != null && !toolDigest.isBlank()) {
            replay.put("toolDigest", toolDigest);
        }
        TenantContext.current().ifPresent(context -> {
            replay.put("tenantId", context.getTenantId());
            replay.put("subject", context.getSubject());
        });
        String arguments = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            payload.set("arguments", objectMapper.readTree(arguments));
        } catch (Exception e) {
            payload.put("argumentsRaw", arguments);
        }
        return payload.toString();
    }

    private void runIfExecutorRegistered(ApprovalRequest request, String decidedBy) {
        executorRegistry.find(request.getActionType()).ifPresentOrElse(
            executor -> runExecutor(executor, request, decidedBy),
            () -> {
                log.warn("Action type '{}' has no registered executor - request {} will not execute",
                    request.getActionType(), request.getId());
                recorder.recordNoExecutorRegistered(request, decidedBy);
            }
        );
    }

    private void runExecutor(ApprovalActionExecutor executor, ApprovalRequest request, String decidedBy) {
        try {
            executor.execute(request);
        } catch (RuntimeException e) {
            recordFailureAndMaybeScheduleRetry(request, e, decidedBy);
            throw e;
        }
    }

    /**
     * Autonomy/Guardrails Strategy Stage C - called only by {@code
     * RetryFailedActionsJob} (via {@code TenantSchemaIterator}, so {@code
     * TenantContext} is already correctly set for this request's tenant
     * before this method runs), never by a live synchronous caller. Unlike
     * {@link #runExecutor}, this deliberately does NOT rethrow - a scheduled
     * job has no HTTP caller waiting to see a 5xx, and letting one request's
     * exception propagate would abort the whole batch for every other
     * tenant/request {@code RetryFailedActionsJob} still needs to process.
     */
    public void retryExecution(ApprovalRequest request) {
        ApprovalActionExecutor executor = executorRegistry.find(request.getActionType()).orElse(null);
        if (executor == null) {
            log.warn("Action type '{}' has no registered executor - retry for request {} abandoned",
                request.getActionType(), request.getId());
            recorder.recordNoExecutorRegistered(request, ApprovalRequestRecorder.RETRY_JOB_ACTOR);
            return;
        }
        try {
            executor.execute(request);
            recorder.recordRetrySucceeded(request);
        } catch (RuntimeException e) {
            recordFailureAndMaybeScheduleRetry(request, e, ApprovalRequestRecorder.RETRY_JOB_ACTOR);
        }
    }

    /**
     * Branches a failed {@link ApprovalActionExecutor#execute} call three
     * ways: retryable and under the attempt cap schedules another try with
     * exponential backoff; retryable but exhausted dead-letters the
     * request (terminal - {@code RetryFailedActionsJob} never revisits a
     * dead-lettered row); anything else (the pre-Stage-C behavior) fails
     * permanently on the first attempt, unchanged.
     */
    private void recordFailureAndMaybeScheduleRetry(ApprovalRequest request, RuntimeException e, String decidedBy) {
        if (!(e instanceof RetryableActionFailure)) {
            recorder.recordExecutionFailed(request, decidedBy, e.getMessage());
            return;
        }
        if (request.getRetryCount() >= maxRetryAttempts) {
            recorder.recordDeadLettered(request, e.getMessage());
            return;
        }
        Instant nextRetryAt = Instant.now().plusSeconds(backoffDelaySeconds(request.getRetryCount() + 1));
        recorder.recordRetryScheduled(request, e.getMessage(), nextRetryAt);
    }

    /**
     * Exponential backoff, capped at {@link #maxDelaySeconds}: attempt 1 ->
     * {@code baseDelaySeconds}, attempt 2 -> {@code baseDelaySeconds * 2},
     * attempt 3 -> {@code * 4}, and so on. The shift is capped at 20 purely
     * to keep the intermediate {@code long} from ever overflowing before
     * the {@code Math.min} clamp runs - {@code maxDelaySeconds} is what
     * actually bounds the real delay in practice.
     */
    private long backoffDelaySeconds(int attemptNumber) {
        long delay = baseDelaySeconds * (1L << Math.min(attemptNumber - 1, 20));
        return Math.min(delay, maxDelaySeconds);
    }

    private ApprovalRequest getPendingOrThrow(UUID id) {
        ApprovalRequest request = requestRepository.findById(id)
            .orElseThrow(() -> new ApprovalRequestNotFoundException(id));
        if (request.getStatus() != ApprovalStatus.PENDING) {
            throw new ApprovalRequestAlreadyDecidedException(id, request.getStatus());
        }
        return request;
    }
}
