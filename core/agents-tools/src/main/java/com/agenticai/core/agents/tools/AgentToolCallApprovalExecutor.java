package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.job.AgentJobLookup;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import com.agenticai.core.agents.turn.ApprovalContinuationPort;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.approval.RetryableActionFailure;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.guardrails.ToolDefinitionDigest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p><b>What the agent gets back.</b> The result is delivered as a notification-bell entry and
 * persisted onto the request's {@code executionResult} (same as {@code
 * RunCodeWorkspaceApprovalExecutor}). The turn that asked is over by approval time, so a compact
 * bracketed note is appended to the originating session's {@link SessionMemoryService} history -
 * under the SCOPED id the turn reads, captured in the replay envelope (see {@link
 * #appendSessionMemoryNote}) - and then the platform CONTINUES the task by itself: {@link
 * ApprovalContinuationPort} (implemented by the turn path's launcher) starts a turn for the same conversation that restates the user's
 * goal and hands the model the tool's full result (or the failure, or the denial), so nobody has to
 * send "carry on". A job session is not continued from here: its sweep resumes the job and builds
 * the job's turn with the same continuation builder. Also note {@code GuardedToolCallback}'s
 * soft-stop design means many tool-level failures come back as explanatory STRINGS, not exceptions
 * - those are recorded and notified as the result verbatim, which is exactly what the approver
 * needs to see either way.
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

    /**
     * Continues the conversation once the outcome is known. ObjectProvider: the launcher needs the
     * whole turn pipeline ({@code AgentChatService}), which itself reaches this class through the
     * tool provider, and a slim context has none - the note alone is then the trace, as before.
     */
    private final ObjectProvider<ApprovalContinuationPort> continuationLauncher;

    /** The wording of the denial note and the stand-in for a missing reason (model-facing text). */
    private final ObjectProvider<ApprovalContinuationProperties> continuationTexts;

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public AgentToolCallApprovalExecutor(
            ObjectProvider<AgentToolProvider> agentToolProvider,
            ApprovalRequestRepository requestRepository,
            ObjectProvider<NotificationService> notificationService,
            ObjectProvider<SessionMemoryService> sessionMemoryService,
            ObjectProvider<AgentJobLookup> agentJobLookup,
            ObjectProvider<ApprovalContinuationPort> continuationLauncher,
            ObjectProvider<ApprovalContinuationProperties> continuationTexts,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.agentToolProvider = agentToolProvider;
        this.requestRepository = requestRepository;
        this.notificationService = notificationService;
        this.sessionMemoryService = sessionMemoryService;
        this.agentJobLookup = agentJobLookup;
        this.continuationLauncher = continuationLauncher;
        this.continuationTexts = continuationTexts;
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

    /**
     * The executor reports the outcome itself (note and continuation, see {@link #continueTask}), so the gate
     * must not report it a second time through {@code ApprovalOutcomePort}.
     */
    @Override
    public boolean reportsOutcomeItself() {
        return true;
    }

    /** The wording and cut of the history notes (model-facing text; the shipped defaults when no bean). */
    private ApprovalContinuationProperties notes() {
        return continuationTexts.getIfAvailable(ApprovalContinuationProperties::new);
    }

    @Override
    public void execute(ApprovalRequest request) {
        ReplayTarget target = ReplayTarget.resolve(request, objectMapper);
        // Save/restore rather than set/clear, copying
        // TenantContextPropagatingToolCallback's idiom - never clobber a
        // context the calling thread (admin request or retry job) had set
        // for its own, still-in-progress work.
        Optional<TenantContext> previous = TenantContext.current();
        if (target.tenantId() != null) {
            TenantContext.set(TenantContext.of(target.tenantId(), target.replaySubject(), Set.of()));
        }
        try {
            refuseIfJobTerminal(target);
            String result = invokeTool(request, target);
            persistExecutionResult(request, result);
            String noteProblem =
                    appendSessionMemoryNote(
                            request,
                            target,
                            notes().renderCompletedNote(
                                    target.toolName(), truncate(result, notes().getMaxNoteResultChars())));
            // The tool ran and its result is persisted; a note that could not be written does not
            // change that outcome (rethrowing would record a failure and could retry a real side
            // effect). It is surfaced to the approver instead of being only a log line.
            notify(
                    target,
                    noteProblem == null ? NotificationSeverity.INFO : NotificationSeverity.WARNING,
                    "Approved action ran: " + target.toolName(),
                    "Your approved tool call '"
                            + target.toolName()
                            + "' has been executed. Result:\n"
                            + truncate(result, MAX_NOTIFICATION_BODY_CHARS)
                            + (noteProblem == null
                                    ? ""
                                    : "\n\nThe agent was NOT told this result ("
                                            + noteProblem
                                            + "): it may ask for the action again."));
            continueTask(request, Outcome.COMPLETED, result);
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
            String noteProblem =
                    appendSessionMemoryNote(
                            request,
                            target,
                            notes().renderFailedNote(
                                    target.toolName(), truncate(failure, notes().getMaxNoteResultChars())));
            notify(
                    target,
                    NotificationSeverity.ERROR,
                    "Approved action failed: " + target.toolName(),
                    "Your approved tool call '"
                            + target.toolName()
                            + "' was attempted but failed: "
                            + failure
                            + (noteProblem == null
                                    ? ""
                                    : "\n\nThe agent was NOT told this ("
                                            + noteProblem
                                            + "): it may ask for the action again."));
            // A failure that will be retried is not the end of the call; the retry that finally
            // succeeds (or the dead letter) is what the agent should hear about.
            if (!(e instanceof RetryableActionFailure)) {
                continueTask(request, Outcome.FAILED, failure);
            }
            throw e;
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * A human DENIED the call. Nothing runs, but the agent must not be left waiting on an answer
     * that has come: the conversation gets a note (like a completed or failed call) and then the
     * task continues with a clear "denied" result carrying the reviewer's reason, so the agent can
     * reach the goal another way or tell the user what it could not do. Best-effort by contract
     * ({@code HumanApprovalGateService} logs and swallows anything thrown here).
     */
    @Override
    public void onRejected(ApprovalRequest request, String decidedBy, String reason) {
        ReplayTarget target;
        try {
            target = ReplayTarget.resolve(request, objectMapper);
        } catch (RuntimeException unreadable) {
            // A row with no usable envelope has no conversation to tell.
            log.info("Rejected approval request {} has no replayable envelope; nobody is told", request.getId());
            return;
        }
        Optional<TenantContext> previous = TenantContext.current();
        if (target.tenantId() != null) {
            TenantContext.set(TenantContext.of(target.tenantId(), target.replaySubject(), Set.of()));
        }
        try {
            ApprovalContinuationProperties texts =
                    continuationTexts.getIfAvailable(ApprovalContinuationProperties::new);
            String shown = reason == null || reason.isBlank() ? texts.getNoReasonText() : reason.strip();
            appendSessionMemoryNote(
                    request,
                    target,
                    texts.renderDeniedNote(
                            target.toolName(), decidedBy, truncate(shown, texts.getMaxNoteResultChars())));
            continueTask(request, Outcome.DENIED, shown);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * Hands the decided call to the launcher, which continues the conversation's task (or declines:
     * a job session, a request recorded without a goal, a decision already continued). Never
     * throws - the call already ran, or was refused, and that stands whatever happens next.
     */
    private void continueTask(ApprovalRequest request, Outcome outcome, String text) {
        try {
            ApprovalContinuationPort launcher = continuationLauncher.getIfAvailable();
            if (launcher != null) {
                launcher.afterDecision(request, outcome, text);
            }
        } catch (RuntimeException e) {
            log.warn("Could not continue the task after the {} decision on approval request {}",
                    outcome, request.getId(), e);
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

    /** See {@link ApprovalOutcomeNotes#append}; returns why the note could not be written, or {@code null}. */
    private String appendSessionMemoryNote(ApprovalRequest request, ReplayTarget target, String note) {
        return ApprovalOutcomeNotes.append(sessionMemoryService, request, target, note, log);
    }

    private static String truncate(String value, int maxChars) {
        return ApprovalOutcomeNotes.truncate(value, maxChars);
    }
}
