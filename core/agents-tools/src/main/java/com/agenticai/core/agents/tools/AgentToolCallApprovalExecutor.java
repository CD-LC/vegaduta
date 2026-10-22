package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.guardrails.ToolDefinitionDigest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Actually RUNS a gated agent tool call once a human approves it - the {@code
 * "mcp_tool.<name>"}-family {@link ApprovalActionExecutor} that closes the gap {@code
 * HumanApprovalGateService.recordToolCallForApproval}'s javadoc used to state honestly ("approving
 * one of these requests today is a visibility/audit action... not yet an automatically re-run the
 * original tool call action"). Found by {@code ApprovalActionExecutorRegistry#find}'s {@code
 * supports()} fallback (one bean for the whole dynamic family, since tool names are only known at
 * runtime), invoked by the exact same {@code HumanApprovalGateService#runExecutor} chokepoint every
 * other gated action goes through - approve, retry-job, audit trail all unchanged.
 *
 * <p><b>Replay goes through the ONE existing guarded chokepoint</b>, never a hand-built callback:
 * {@link AgentToolProvider#resolveToolCandidatesFor} re-resolves the agent's current tool set (same
 * merge, allow-list filter, and {@code GuardedToolCallback}/{@code LoggingToolCallback} decoration
 * a hosted turn or an edge execution gets - the same posture {@code EdgeToolExecutionController}
 * established for lookup-by-exact-name calls), and the approved tool is found by name in that set.
 * Consequences, each deliberate:
 *
 * <ul>
 *   <li>The replayed call is re-checked against the agent's CURRENT allow-list and tool
 *       availability - a tool disabled between request and approval fails the replay (recorded as
 *       an execution failure) rather than sneaking through on stale authority.
 *   <li>The call re-enters {@code GuardedToolCallback}'s approval gate, which would loop forever -
 *       {@link ApprovalReplayGrant} (single-use, same-thread, exact action type) is how the human's
 *       decision crosses into the guard. See that class's javadoc.
 *   <li>The execution lands in {@code tool_call_log} under the ORIGINAL agent/session id (recorded
 *       in the replay envelope), so the audit trail reads as the continuation of the turn that
 *       asked, not an unrelated orphan call.
 * </ul>
 *
 * <p><b>Tenant context:</b> re-asserted from the recorded envelope (same
 * save-in-a-local/restore-in-finally idiom as {@link TenantContextPropagatingToolCallback}) rather
 * than trusted from the calling thread. In practice the two agree - {@code
 * ApprovalController#approve} runs under the deciding admin's request context and {@code
 * RetryFailedActionsJob} under {@code TenantSchemaIterator}'s - but the envelope is the record the
 * human actually approved, so it wins, and a legacy row with no recorded tenant simply inherits the
 * caller's (correct) context unchanged.
 *
 * <p><b>Honest limits:</b> the result is delivered as a notification-bell entry (and persisted onto
 * the request's {@code executionResult}, same as {@code RunCodeWorkspaceApprovalExecutor}), NOT
 * injected back into the live chat stream - by approval time that stream is long gone, and the
 * model that asked never sees the answer mid-turn. What DOES happen now (Phase 2 autonomy
 * follow-up): a compact bracketed note is appended to the originating session's {@link
 * SessionMemoryService} history, so the agent's NEXT turn in that conversation knows the approved
 * action actually ran (or failed) - see {@link #appendSessionMemoryNote} for the exact
 * representation and its guards. Also note {@code GuardedToolCallback}'s soft-stop design means
 * many tool-level failures come back as explanatory STRINGS, not exceptions - those are recorded
 * and notified as the result verbatim, which is exactly what the approver needs to see either way.
 */
@Component
public class AgentToolCallApprovalExecutor implements ApprovalActionExecutor {

    /**
     * Must stay in sync with {@code GuardedToolCallback.ACTION_TYPE_PREFIX} (same duplication, same
     * reason as {@code ApprovalController#TOOL_CALL_ACTION_TYPE_PREFIX}: the dependency runs
     * guardrails -&gt; approval and agents -&gt; guardrails, but that constant is private and
     * exporting it for one string would couple modules over nothing).
     */
    public static final String ACTION_TYPE_PREFIX = "mcp_tool.";

    /** Same cap and rationale as {@code RunCodeWorkspaceApprovalExecutor#MAX_STREAM_CHARS}. */
    private static final int MAX_RESULT_CHARS = 32 * 1024;

    private static final int MAX_NOTIFICATION_BODY_CHARS = 2_000;

    /**
     * Much tighter than {@link #MAX_RESULT_CHARS} on purpose: a session-memory turn is re-sent to
     * the LLM on EVERY subsequent turn of that conversation, so a 32KB tool result pasted in
     * verbatim would tax every later prompt. The full result still lives on the request's {@code
     * executionResult}; the memory note only needs enough for the model to know what happened.
     */
    private static final int MAX_MEMORY_NOTE_RESULT_CHARS = 500;

    private static final String TRUNCATED_SUFFIX = "\n...[truncated]";

    /**
     * Kept as a constant so {@link #appendSessionMemoryNote} can recognize (and skip) synthetic
     * ids.
     */
    private static final String FALLBACK_SESSION_PREFIX = "approval-replay:";

    private static final Logger log = LoggerFactory.getLogger(AgentToolCallApprovalExecutor.class);

    // ObjectProvider is load-bearing, not the usual slim-test-context
    // convenience: a direct AgentToolProvider field here is a genuine bean
    // cycle (this bean -> AgentToolProvider -> HumanApprovalGateService ->
    // ApprovalActionExecutorRegistry -> List<ApprovalActionExecutor> ->
    // this bean). Same deferred-lookup idiom AgentToolProvider itself uses
    // for AgentChatService, for the same reason.
    private final ObjectProvider<AgentToolProvider> agentToolProvider;
    private final ApprovalRequestRepository requestRepository;
    // ObjectProvider: core/audit is always on the classpath in the real app,
    // but this keeps slim test contexts constructible without a
    // NotificationService bean - same posture as every other cross-module
    // optional in this package. Notification is best-effort anyway.
    private final ObjectProvider<NotificationService> notificationService;
    // ObjectProvider for the same slim-test-context reason as
    // NotificationService above (SessionMemoryService drags in a live
    // StringRedisTemplate) - and the append is best-effort anyway.
    private final ObjectProvider<SessionMemoryService> sessionMemoryService;

    /**
     * The terminal-job refusal's read (see {@link #refuseIfJobTerminal}). ObjectProvider for the
     * slim-test-context reason the other cross-package collaborators above give - and because the
     * check is a best-effort backstop, a context without the jobs module simply skips it.
     */
    private final ObjectProvider<AgentJobLookup> agentJobLookup;

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public AgentToolCallApprovalExecutor(
            ObjectProvider<AgentToolProvider> agentToolProvider,
            ApprovalRequestRepository requestRepository,
            ObjectProvider<NotificationService> notificationService,
            ObjectProvider<SessionMemoryService> sessionMemoryService,
            ObjectProvider<AgentJobLookup> agentJobLookup,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.agentToolProvider = agentToolProvider;
        this.requestRepository = requestRepository;
        this.notificationService = notificationService;
        this.sessionMemoryService = sessionMemoryService;
        this.agentJobLookup = agentJobLookup;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The registry's uniqueness key, NOT a real action type - no request is ever recorded as bare
     * {@code "mcp_tool."}; {@link #supports} (which requires a non-empty tool name after the
     * prefix) is what actually routes requests here, via the registry's exact-match-first fallback.
     */
    @Override
    public String actionType() {
        return ACTION_TYPE_PREFIX;
    }

    @Override
    public boolean supports(String actionType) {
        return actionType != null
                && actionType.startsWith(ACTION_TYPE_PREFIX)
                && actionType.length() > ACTION_TYPE_PREFIX.length();
    }

    @Override
    public void execute(ApprovalRequest request) {
        ReplayTarget target = resolveTarget(request);
        // Save/restore rather than set/clear, copying
        // TenantContextPropagatingToolCallback's idiom - never clobber a
        // context the calling thread (admin request or retry job) had set
        // for its own, still-in-progress work.
        Optional<TenantContext> previous = TenantContext.current();
        if (target.tenantId() != null) {
            TenantContext.set(TenantContext.of(target.tenantId(), replaySubject(target), Set.of()));
        }
        try {
            refuseIfJobTerminal(target);
            String result = invokeTool(request, target);
            persistExecutionResult(request, result);
            appendSessionMemoryNote(
                    target,
                    "[approved action '"
                            + target.toolName()
                            + "' completed: "
                            + truncate(result, MAX_MEMORY_NOTE_RESULT_CHARS)
                            + "]");
            notify(
                    target,
                    NotificationSeverity.INFO,
                    "Approved action ran: " + target.toolName(),
                    "Your approved tool call '"
                            + target.toolName()
                            + "' has been executed. Result:\n"
                            + truncate(result, MAX_NOTIFICATION_BODY_CHARS));
        } catch (RuntimeException e) {
            // Best-effort notify, then rethrow unchanged so
            // HumanApprovalGateService records the failure against the
            // request exactly as it does for every other executor. Nothing
            // thrown here is retryable - see AgentToolReplayException's
            // javadoc for why auto-retrying an arbitrary side-effectful
            // tool is the wrong default.
            String failure =
                    truncate(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                            MAX_NOTIFICATION_BODY_CHARS);
            // The failure note matters as much as the success one: without it
            // the agent's next turn would assume its pending action simply
            // vanished and might silently re-attempt it.
            appendSessionMemoryNote(
                    target,
                    "[approved action '"
                            + target.toolName()
                            + "' failed: "
                            + truncate(failure, MAX_MEMORY_NOTE_RESULT_CHARS)
                            + "]");
            notify(
                    target,
                    NotificationSeverity.ERROR,
                    "Approved action failed: " + target.toolName(),
                    "Your approved tool call '"
                            + target.toolName()
                            + "' was attempted but failed: "
                            + failure);
            throw e;
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * Refuses to replay a tool call whose originating agent JOB is already terminal (FAILED /
     * CANCELLED / SUCCEEDED).
     *
     * <p>The approval sweep rejects a terminalised job's PENDING requests itself, but two paths
     * still leave an approvable request behind: a synchronous {@code AgentJobService#cancel} of a
     * parked job (the sweep never sees it), and any sweep tick whose best-effort rejection failed.
     * Approving such an orphan used to fire a real, non-idempotent side effect - a channel send, a
     * document render, any {@code mcp_tool.*} - on behalf of a job nobody will ever resume. The
     * session id in the replay envelope ({@code job:<uuid>}) is what makes the question answerable
     * here.
     *
     * <p>Runs INSIDE the envelope's re-asserted {@link TenantContext} so the job read routes to the
     * right schema. Fail-open on a lookup blip, on a non-job session (console/omnichannel gates
     * have no job to check), and in contexts without the repository: this is a backstop on top of
     * the sweep's rejection, and a transient read failure must not turn a legitimately approved
     * replay into an execution failure. The refusal itself throws {@link AgentToolReplayException},
     * which is deliberately non-retryable.
     */
    private void refuseIfJobTerminal(ReplayTarget target) {
        Optional<UUID> jobId = AgentJobSession.jobIdOf(target.sessionId());
        if (jobId.isEmpty()) {
            return;
        }
        AgentJobLookup jobs = agentJobLookup.getIfAvailable();
        if (jobs == null) {
            return;
        }
        AgentJobLookup.JobFacts job;
        try {
            job = jobs.findById(jobId.get()).orElse(null);
        } catch (RuntimeException lookupFailed) {
            log.warn(
                    "Could not check whether agent job {} is terminal before replaying an approved tool call - "
                            + "proceeding with the replay",
                    jobId.get(),
                    lookupFailed);
            return;
        }
        if (job != null && job.terminal()) {
            throw new AgentToolReplayException(
                    "This approval belongs to agent job "
                            + job.id()
                            + ", which is already "
                            + job.status()
                            + (job.errorCode() != null ? " (" + job.errorCode() + ")" : "")
                            + " - the job ended before this decision, so the gated action will not be executed. "
                            + "Re-run the job if the work is still wanted.");
        }
    }

    private String invokeTool(ApprovalRequest request, ReplayTarget target) {
        AgentToolProvider provider = agentToolProvider.getObject();
        // resolveToolCandidatesFor, not resolveToolsFor - lookup by exact
        // name that never reaches an LLM provider, so the per-turn relevance
        // cap could only hide the one tool this replay needs (same reasoning,
        // same call, as EdgeToolExecutionController#executeAuthenticated).
        // subjectKey = sessionId is the established no-independent-subject
        // idiom from resolveToolsForDelegatedCall.
        List<ToolCallback> tools =
                provider.resolveToolCandidatesFor(
                        target.agentId(), target.sessionId(), target.sessionId());
        ToolCallback callback =
                tools.stream()
                        .filter(tool -> target.toolName().equals(tool.getToolDefinition().name()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AgentToolReplayException(
                                                "Tool '"
                                                        + target.toolName()
                                                        + "' is no longer resolvable for agent "
                                                        + target.agentId()
                                                        + " - it may have been disabled, removed from the agent's allow-list, or its MCP server "
                                                        + "disconnected since this request was recorded. Ask the user to retry the action in chat."));
        refuseIfToolChanged(target, callback);
        ApprovalReplayGrant.grant(request.getActionType());
        try {
            return callback.call(target.argumentsJson());
        } finally {
            // consume() clears on the matching gate check, but a callback
            // that fails BEFORE its gate runs (circuit breaker, transport)
            // must not leave a live grant on this pooled thread.
            ApprovalReplayGrant.clear();
        }
    }

    /**
     * The approval named a tool; the fingerprint recorded with it says which tool that was. If the
     * tool now resolved under that name has a different definition (its MCP server was swapped or
     * updated since the request), the human never approved <i>this</i> tool, so the replay is
     * refused and a fresh approval is needed. A request recorded before fingerprints existed has no
     * digest and replays as before.
     */
    private void refuseIfToolChanged(ReplayTarget target, ToolCallback callback) {
        if (target.toolDigest() == null) {
            return;
        }
        String current = ToolDefinitionDigest.of(callback.getToolDefinition());
        if (!target.toolDigest().equals(current)) {
            throw new AgentToolReplayException(
                    "Tool '"
                            + target.toolName()
                            + "' has changed since this request was approved (its description or input schema is "
                            + "different), so it was not run. Ask the user to retry the action in chat and approve it again.");
        }
    }

    /**
     * Same explicit-save necessity as {@code
     * RunCodeWorkspaceApprovalExecutor#persistExecutionResult}: the gate service invokes {@link
     * #execute} outside any transaction and persists nothing on a normal return, so the result
     * would otherwise exist only in the application log.
     *
     * <p>Also the ONE place this executor's normal (first-attempt, not retried) success actually
     * becomes durable - {@code ApprovalRequestRecorder} in {@code core/approval} never sees this
     * write at all, since it is package-private to that module and this class lives in {@code
     * core/agents} (same reason {@code RunCodeWorkspaceApprovalExecutor} saves directly rather than
     * going through the recorder). So the {@link ApprovalRequestExecutionRecordedEvent} this method
     * publishes is published from HERE, not from the recorder, for this specific outcome - {@code
     * AgentJobApprovalSweep}'s listener does not care which class raised it, only that the
     * request's execution outcome just became durable.
     */
    private void persistExecutionResult(ApprovalRequest request, String result) {
        request.recordExecutionResult(truncate(result, MAX_RESULT_CHARS));
        requestRepository.save(request);
        eventPublisher.publishEvent(
                new ApprovalRequestExecutionRecordedEvent(
                        this, request.getId(), request.getRequestedBy()));
    }

    /**
     * Best-effort by {@code NotificationService#create}'s own documented convention - a
     * notification-write failure must never turn a completed (or already-failed) replay into a
     * different outcome. Targeted at the originating human's Keycloak subject when the envelope
     * recorded one; a legacy/subject-less row broadcasts to tenant-admins instead - the same people
     * who approved it, so never silent. Category SYSTEM (the least-wrong existing bucket: this is
     * "your approved action completed", not a tool failure or workflow run); {@code link} is null -
     * no tenant-facing approval-detail page exists to deep-link into, and this codebase never
     * fabricates a dead route (see {@code AiDiagnosticAnalysisService}'s identical call-site note).
     */
    private void notify(
            ReplayTarget target, NotificationSeverity severity, String title, String body) {
        NotificationService service = notificationService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.create(
                    target.subject(),
                    NotificationCategory.SYSTEM,
                    title,
                    body,
                    severity,
                    null,
                    null);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to create notification for replayed tool call '{}': {}",
                    target.toolName(),
                    e.getMessage());
        }
    }

    /**
     * Everything replay needs, resolved from either payload shape. Nullable: {@code tenantId},
     * {@code subject}.
     */
    record ReplayTarget(
            UUID agentId,
            String sessionId,
            String toolName,
            String argumentsJson,
            String tenantId,
            String subject,
            String toolDigest) {}

    /**
     * Two payload shapes exist in the wild and both must replay:
     *
     * <ol>
     *   <li><b>Envelope</b> (7-arg {@code recordToolCallForApproval}, the shape everything records
     *       now): {@code {"replay": {...}, "arguments": ...}}. Discriminated by {@code
     *       replay.toolName} being present, not by the {@code "replay"} key alone - the legacy
     *       payload is model-authored tool input and could contain a {@code "replay"} key of its
     *       own.
     *   <li><b>Legacy</b> (raw tool arguments, rows recorded before the envelope existed): tool
     *       name recovered from the action type, agent/session from {@code requestedBy}'s {@code
     *       "agent:<id>:session:<id>"} label, tenant inherited from the calling thread (the
     *       approve/retry paths both set it correctly - see class javadoc).
     * </ol>
     */
    private ReplayTarget resolveTarget(ApprovalRequest request) {
        String payloadJson = request.getPayloadJson();
        JsonNode payload = null;
        if (payloadJson != null && !payloadJson.isBlank()) {
            try {
                payload = objectMapper.readTree(payloadJson);
            } catch (Exception e) {
                throw new AgentToolReplayException(
                        "Approval request "
                                + request.getId()
                                + " has an unparseable payload - cannot replay: "
                                + e.getMessage(),
                        e);
            }
        }
        if (payload != null && payload.path("replay").hasNonNull("toolName")) {
            return fromEnvelope(request, payload);
        }
        return fromLegacyPayload(request, payloadJson);
    }

    private ReplayTarget fromEnvelope(ApprovalRequest request, JsonNode payload) {
        JsonNode replay = payload.path("replay");
        UUID agentId = parseUuid(replay.path("agentId").asString(null));
        if (agentId == null) {
            throw new AgentToolReplayException(
                    "Approval request "
                            + request.getId()
                            + " has a replay envelope with no valid agentId - cannot replay.");
        }
        String arguments;
        if (payload.has("arguments")) {
            arguments = payload.get("arguments").toString();
        } else {
            arguments = payload.path("argumentsRaw").asString("{}");
        }
        String sessionId = replay.path("sessionId").asString(null);
        return new ReplayTarget(
                agentId,
                sessionId != null && !sessionId.isBlank() ? sessionId : fallbackSessionId(request),
                replay.path("toolName").asString(),
                arguments,
                replay.path("tenantId").asString(null),
                replay.path("subject").asString(null),
                replay.path("toolDigest").asString(null));
    }

    private ReplayTarget fromLegacyPayload(ApprovalRequest request, String payloadJson) {
        String actionType = request.getActionType();
        String toolName = actionType.substring(ACTION_TYPE_PREFIX.length());
        UUID agentId = null;
        String sessionId = null;
        String requestedBy = request.getRequestedBy();
        // GuardedToolCallback's label: "agent:<uuid>:session:<sessionId>".
        if (requestedBy != null && requestedBy.startsWith("agent:")) {
            int sessionMarker = requestedBy.indexOf(":session:");
            if (sessionMarker > "agent:".length()) {
                agentId = parseUuid(requestedBy.substring("agent:".length(), sessionMarker));
                sessionId = requestedBy.substring(sessionMarker + ":session:".length());
            }
        }
        if (agentId == null) {
            throw new AgentToolReplayException(
                    "Approval request "
                            + request.getId()
                            + " predates the replay envelope and its requestedBy label ('"
                            + requestedBy
                            + "') does not identify the requesting agent - cannot replay. "
                            + "Ask the user to retry the action in chat.");
        }
        return new ReplayTarget(
                agentId,
                sessionId != null && !sessionId.isBlank() ? sessionId : fallbackSessionId(request),
                toolName,
                payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson,
                null,
                null,
                null);
    }

    private static String fallbackSessionId(ApprovalRequest request) {
        return FALLBACK_SESSION_PREFIX + request.getId();
    }

    /**
     * Closes the "the model that asked never learns the answer" gap this class's javadoc used to
     * document as future work: a compact bracketed note (e.g. {@code [approved action
     * 'send_message' completed: ...]}) is appended to the originating conversation's Redis-backed
     * history, so the agent's NEXT turn in that session sees the outcome in-context and doesn't
     * re-request an action a human already ran.
     *
     * <p><b>Representation choice, documented honestly:</b> the note is an {@link
     * ChatTurn#assistant ASSISTANT}-role turn, because {@code MessageRole} has only USER/ASSISTANT
     * and {@code AgentChatService#toSpringAiMessage} maps every non-USER turn to an {@code
     * AssistantMessage} anyway - adding a SYSTEM role today would be inert without also changing
     * that mapping (deliberately out of this change's scope). Assistant-voiced is also the
     * least-wrong semantic: the action was performed on the agent's behalf, and the bracketed shape
     * keeps it visually distinct from genuine model prose.
     *
     * <p>Guards: skipped when the recorded sessionId is missing or is the synthetic {@value
     * #FALLBACK_SESSION_PREFIX}-prefixed placeholder (no real conversation exists to inform), and
     * any failure here is logged and swallowed - a Redis hiccup must never change the outcome of an
     * approval that already executed (or already failed for its own reason).
     */
    private void appendSessionMemoryNote(ReplayTarget target, String note) {
        String sessionId = target.sessionId();
        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.startsWith(FALLBACK_SESSION_PREFIX)) {
            return;
        }
        SessionMemoryService memory = sessionMemoryService.getIfAvailable();
        if (memory == null) {
            return;
        }
        try {
            memory.appendTurn(sessionId, ChatTurn.assistant(note));
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to append approval-outcome note to session '{}' memory (approval outcome unaffected): {}",
                    LogSafe.clean(sessionId),
                    LogSafe.clean(e.getMessage()));
        }
    }

    /**
     * The subject stamped on the replay's {@link TenantContext} - the originating human when
     * recorded, a system label otherwise, so audit writes made during the replay never attribute to
     * the deciding admin something an agent's session actually asked for.
     */
    private static String replaySubject(ReplayTarget target) {
        return target.subject() != null && !target.subject().isBlank()
                ? target.subject()
                : "system:approval-replay";
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + TRUNCATED_SUFFIX;
    }
}
