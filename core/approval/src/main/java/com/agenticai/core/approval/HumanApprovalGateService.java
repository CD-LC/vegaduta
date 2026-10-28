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

    /**
     * Where the end of every approval is reported so the conversation that asked for the action can
     * carry on (see {@link ApprovalOutcomePort}). Absent in a context without the turn path: the
     * decision then only runs (or refuses) the action, as before.
     */
    private volatile ApprovalOutcomePort outcomePort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOutcomePort(ApprovalOutcomePort outcomePort) {
        this.outcomePort = outcomePort;
    }

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

    /**
     * As {@link #evaluate}, for a tool that gates ITSELF inside its {@code call()} (a code-workspace run,
     * a channel send) and so runs under the tool guard's {@link ToolCallScope}. A request that will wait
     * for a human is recorded with the same replay envelope a guarded tool call has - the tool, the
     * agent and conversation, the user's goal, the scoped history id, the tenant and subject - merged
     * into the action's own payload (its own fields stay at the top level, so the executor and the
     * {@code adminOnly} check read it unchanged). When the human has decided and the action has ended,
     * {@link ApprovalOutcomePort} puts the outcome back into the conversation and continues the task.
     *
     * <p>Outside a tool call, or when policy auto-approves (the action runs right now, inside the turn
     * that asked, which needs no continuation), this is exactly {@link #evaluate}. {@code requestedBy}
     * is never changed, so who may decide the request is unchanged too.
     *
     * @param toolName used when no scope names the tool
     */
    public ApprovalEvaluation evaluateInTurn(
        String actionType, String toolName, String summary, String payloadJson, String requestedBy
    ) {
        java.util.Optional<ToolCallScope.Current> scope = ToolCallScope.current();
        if (scope.isEmpty() || scope.get().origin() == null || !policyService.requiresApproval(actionType)) {
            return evaluate(actionType, summary, payloadJson, requestedBy);
        }
        String enveloped;
        try {
            enveloped = withReplayEnvelope(payloadJson, toolName, scope.get());
        } catch (RuntimeException e) {
            log.warn("Could not add the conversation envelope to the '{}' approval request - recording the plain "
                + "payload; the task will not be continued after the decision: {}", actionType, e.getMessage());
            return evaluate(actionType, summary, payloadJson, requestedBy);
        }
        ApprovalRequest request = recorder.recordNew(actionType, summary, enveloped, requestedBy, false);
        return ApprovalEvaluation.pending(request.getId());
    }

    private String withReplayEnvelope(String payloadJson, String toolName, ToolCallScope.Current scope) {
        try {
            JsonNode parsed = payloadJson == null || payloadJson.isBlank()
                ? objectMapper.createObjectNode() : objectMapper.readTree(payloadJson);
            if (!(parsed instanceof ObjectNode payload)) {
                throw new IllegalArgumentException("the action payload is not a JSON object");
            }
            payload.set("replay", replayEnvelope(
                scope.toolName() != null ? scope.toolName() : toolName,
                scope.agentId(), scope.sessionId(), scope.toolDigest(), scope.origin()));
            return payload.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the action payload is not valid JSON", e);
        }
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
     * Closes a request nobody decided in time (see {@link ApprovalExpiryProperties}): recorded as a
     * rejection by {@link ApprovalRequest#EXPIRY_ACTOR} with {@code reason}, after which {@link
     * #approve} refuses it as already decided - a stale request can no longer be approved into
     * running its action. The executor that would have run it gets the same cleanup call as for a
     * rejection, so anything suspended on the decision (a workflow run parked at an approval node) is
     * settled rather than left waiting. Throws {@link ApprovalRequestAlreadyDecidedException} if the
     * request was decided first.
     */
    public ApprovalRequest expire(UUID requestId, String reason) {
        ApprovalRequest request = getPendingOrThrow(requestId);
        request = recorder.recordExpired(request, reason);
        notifyExecutorOfRejection(request, ApprovalRequest.EXPIRY_ACTOR, reason);
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
            reportOutcome(executor, request, ApprovalOutcomePort.Outcome.DENIED, reason);
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
        return recordToolCallForApproval(
            actionType, toolName, summary, argumentsJson, requestedBy, agentId, sessionId, toolDigest, null);
    }

    /**
     * As the 8-arg overload, plus {@code memorySessionId}: the SEC-004 scoped id the originating
     * turn reads its conversation history under. It is stored in the envelope so that, once the
     * human decides, the executor appends its "approved action ... completed" note to THAT history
     * (the one the conversation's next turn replays) rather than to the raw client session id,
     * which nothing reads. {@code null} (edge execution, delegated calls, rows recorded before this
     * existed) stores nothing and the executor derives the id from the tenant and subject.
     */
    public ApprovalRequest recordToolCallForApproval(
        String actionType, String toolName, String summary, String argumentsJson,
        String requestedBy, UUID agentId, String sessionId, String toolDigest, String memorySessionId
    ) {
        return recordToolCallWithOrigin(
            actionType, toolName, summary, argumentsJson, requestedBy, agentId, sessionId, toolDigest,
            ToolCallOrigin.ofMemorySession(memorySessionId));
    }

    /**
     * As the 9-arg {@code recordToolCallForApproval}, with the whole {@link ToolCallOrigin}: besides the scoped history id,
     * the user message that started the parked turn, the agent id the conversation was requested
     * under and its memory subject. They are what lets the platform continue the original task
     * after the human decides (restate the goal, read the same history) rather than only replay
     * the tool. A {@code null} origin records none of them.
     */
    public ApprovalRequest recordToolCallWithOrigin(
        String actionType, String toolName, String summary, String argumentsJson,
        String requestedBy, UUID agentId, String sessionId, String toolDigest, ToolCallOrigin origin
    ) {
        String payloadJson;
        try {
            payloadJson = buildToolCallReplayPayload(
                toolName, argumentsJson, agentId, sessionId, toolDigest, origin);
        } catch (RuntimeException e) {
            log.warn("Failed to build replay envelope for gated tool call '{}' - recording raw arguments instead "
                + "(request will need the legacy replay path): {}", toolName, e.getMessage());
            payloadJson = argumentsJson;
        }
        return recorder.recordNew(actionType, summary, payloadJson, requestedBy, false);
    }

    /**
     * Shape: {@code {"replay": {"version": 1, "toolName", "agentId",
     * "sessionId", "memorySessionId", "goal", "requestedAgentId", "subjectKey", "runToolNames",
     * "tenantId", "subject"}, "arguments": {...}}}. The
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
        String toolName, String argumentsJson, UUID agentId, String sessionId, String toolDigest,
        ToolCallOrigin origin
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("replay", replayEnvelope(toolName, agentId, sessionId, toolDigest, origin));
        String arguments = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            payload.set("arguments", objectMapper.readTree(arguments));
        } catch (Exception e) {
            payload.put("argumentsRaw", arguments);
        }
        return payload.toString();
    }

    /** The {@code replay} object of the envelope: the call's identity and where it came from. */
    private ObjectNode replayEnvelope(
        String toolName, UUID agentId, String sessionId, String toolDigest, ToolCallOrigin origin
    ) {
        ObjectNode replay = objectMapper.createObjectNode();
        replay.put("version", 1);
        replay.put("toolName", toolName);
        replay.put("agentId", agentId != null ? agentId.toString() : null);
        replay.put("sessionId", sessionId);
        if (toolDigest != null && !toolDigest.isBlank()) {
            replay.put("toolDigest", toolDigest);
        }
        if (origin != null) {
            putIfPresent(replay, "memorySessionId", origin.memorySessionId());
            putIfPresent(replay, "goal", origin.goal());
            putIfPresent(replay, "requestedAgentId",
                origin.requestedAgentId() == null ? null : origin.requestedAgentId().toString());
            putIfPresent(replay, "subjectKey", origin.subjectKey());
            if (origin.runToolNames() != null) {
                // Present even when empty: "narrowed to no tools" is not "not narrowed".
                com.fasterxml.jackson.databind.node.ArrayNode names = replay.putArray("runToolNames");
                origin.runToolNames().forEach(names::add);
            }
        }
        TenantContext.current().ifPresent(context -> {
            replay.put("tenantId", context.getTenantId());
            replay.put("subject", context.getSubject());
        });
        return replay;
    }

    /**
     * The requests still waiting for a human that one conversation's tool guard recorded ({@code
     * requestedBy} is its {@code "agent:<agentId>:session:<sessionId>"} label), oldest first. Read
     * when a human decides one of them, to tell the continued task which of its other actions are
     * still outstanding. Read-only.
     */
    public java.util.List<ApprovalRequest> findPendingRequestedBy(String requestedBy) {
        return requestRepository.findByRequestedByAndStatusOrderByCreatedAtAsc(
            requestedBy, ApprovalStatus.PENDING);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
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
        // The decision is committed; from here exactly one caller may run the action. Without this a process that died
        // between the decision and the run left an approved action nobody ever ran (and nobody could find), and the
        // recovery sweep could not tell a dead run from a live one.
        if (!recorder.claimExecution(request)) {
            log.warn("Approval request {} (action type '{}') was already claimed for execution - not running it again",
                request.getId(), request.getActionType());
            return;
        }
        try {
            executor.execute(request);
        } catch (RuntimeException e) {
            recordFailureAndMaybeScheduleRetry(request, e, decidedBy);
            throw e;
        }
        recorder.recordExecutionFinished(request);
        reportOutcome(executor, request, ApprovalOutcomePort.Outcome.COMPLETED, request.getExecutionResult());
    }

    /**
     * Recovery of an approved action the process never started (it died between the decision and the run). Called only
     * by {@code ApprovalRecoveryJob}, which has set the tenant. Runs it once through the same claim as a live decision,
     * so a decision thread that is merely slow cannot run it a second time. Never rethrows: a sweep has no caller to
     * show a failure to, and one request must not stop the batch.
     */
    public void runUnstartedApproval(ApprovalRequest request) {
        ApprovalActionExecutor executor = executorRegistry.find(request.getActionType()).orElse(null);
        if (executor == null) {
            log.warn("Action type '{}' has no registered executor - approved request {} cannot be recovered",
                request.getActionType(), request.getId());
            recorder.recordNoExecutorRegistered(request, ApprovalRequestRecorder.RECOVERY_ACTOR);
            return;
        }
        try {
            runExecutor(executor, request, ApprovalRequestRecorder.RECOVERY_ACTOR);
        } catch (RuntimeException e) {
            log.warn("Recovered approval request {} (action type '{}') failed - recorded against the request: {}",
                request.getId(), request.getActionType(), e.getMessage());
        }
    }

    /**
     * Closes an approved action that was never started and is too old to run now (see {@code
     * ApprovalRecoveryProperties#getMaxRecoveryAge}). Not run; the conversation is told it was not done. Returns false
     * when it was started or finished in the meantime.
     */
    public boolean abandonStaleApproval(ApprovalRequest request, String reason) {
        if (!recorder.recordNeverRan(request, reason)) {
            return false;
        }
        reportOutcome(request, ApprovalOutcomePort.Outcome.FAILED, reason);
        return true;
    }

    /**
     * Closes an approved action that started and never reported how it ended. It is not run again (see {@link
     * ApprovalRequestRecorder#recordInterrupted}); the conversation that asked is told it failed with an unknown
     * outcome so it does not wait for it or assume it happened. Returns false when the action finished first.
     */
    public boolean abandonInterruptedApproval(ApprovalRequest request, String reason) {
        if (!recorder.recordInterrupted(request, reason)) {
            return false;
        }
        reportOutcome(request, ApprovalOutcomePort.Outcome.FAILED, reason);
        return true;
    }

    /**
     * Tells the conversation that asked for the action how its approval ended (see {@link
     * ApprovalOutcomePort}) - unless the executor does that itself. Strictly best-effort: the decision
     * and the action already took effect, so nothing thrown here may change either. A request that did
     * not come from an agent turn is ignored by the port.
     */
    private void reportOutcome(
        ApprovalActionExecutor executor, ApprovalRequest request, ApprovalOutcomePort.Outcome outcome, String text
    ) {
        ApprovalOutcomePort port = outcomePort;
        if (port == null || executor.reportsOutcomeItself()) {
            return;
        }
        try {
            port.afterDecision(request, outcome, text);
        } catch (RuntimeException e) {
            log.warn("Could not report the {} outcome of approval request {} (action type '{}') to its conversation: {}",
                outcome, request.getId(), request.getActionType(), e.getMessage());
        }
    }

    private void reportOutcome(ApprovalRequest request, ApprovalOutcomePort.Outcome outcome, String text) {
        executorRegistry.find(request.getActionType())
            .ifPresent(executor -> reportOutcome(executor, request, outcome, text));
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
            reportOutcome(executor, request, ApprovalOutcomePort.Outcome.COMPLETED, request.getExecutionResult());
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
            reportOutcome(request, ApprovalOutcomePort.Outcome.FAILED, failureText(e));
            return;
        }
        if (request.getRetryCount() >= maxRetryAttempts) {
            recorder.recordDeadLettered(request, e.getMessage());
            // The last attempt failed: the call is over, which is what the conversation should hear. A
            // failure that will be retried is not the end of the call and is not reported.
            reportOutcome(request, ApprovalOutcomePort.Outcome.FAILED, failureText(e));
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

    private static String failureText(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
