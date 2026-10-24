package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatPromptDirectives.containsFabricatedGenerationUrl;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.prependPlanBlock;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.recentHistoryBlock;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turn.quality.AnswerConsistencyChecker;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.ReflexionService;
import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import com.agenticai.core.agents.turnjournal.TurnRecorder;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/**
 * The buffered reasoning depths (CAREFUL / RIGOROUS verify-then-respond and PLANNED
 * plan-then-respond), extracted verbatim from {@link AgentChatService} (extract-only split). The
 * model calls themselves (provider fallback, thought_signature retry, L4 escalation) are in {@link
 * BufferedTurnCalls}; this class owns the turn shapes, the off-thread execution, the timeout and
 * the keepalive.
 *
 * <p>ADR-0022 stage 3: each turn carries its {@link TurnContext}; the buffered work re-applies
 * {@link TurnContext#scope()} on the worker thread instead of capturing the ThreadLocal.
 */
final class BufferedTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final BufferedTurnCalls calls;
    private final ChatGuardrailGate guardrails;
    private final ChatIssueRecorder issues;
    private final AgentMessageEvents events;
    private final ProviderErrorTranslator providerErrorTranslator;
    private final SessionMemoryService sessionMemoryService;
    private final LlmQualityJudgeService llmQualityJudgeService;
    private final AnswerConsistencyChecker answerConsistencyChecker;
    private final ReflexionService reflexionService;
    private final TaskPlanningService taskPlanningService;

    /** See {@code AgentChatService#bufferedKeepAliveInterval}. */
    private final Duration bufferedKeepAliveInterval;

    /**
     * Total-duration ceiling for ONE buffered-depth turn. The stream idle timeout cannot apply here (a buffered
     * depth emits no token until it is finished), and the HTTP read timeout stops timing once headers arrive, so
     * without this a provider that went silent mid-answer parked the turn until the browser's own 300s ceiling,
     * with no server-side error. Set BELOW that ceiling so the failure surfaces here as a translated provider error.
     */
    private final Duration bufferedTurnTimeout;

    /**
     * Where a buffered depth's blocking work runs: off the subscribing thread (inline inside {@code Flux.defer}'s
     * supplier no {@code timeout} timer or keepalive could ever start, because the supplier runs before {@code
     * onSubscribe} reaches downstream). One virtual thread per turn, not {@code boundedElastic}, which caps at 10x
     * CPU threads and would queue long blocking turns behind each other; the concurrency profile is the one these
     * turns had when they blocked the (virtual) request thread.
     */
    private final Scheduler bufferedDepthScheduler =
            TenantAwareSchedulers.fromExecutorService(
                    TenantAwareExecutors.newVirtualThreadPerTaskExecutor("buffered-turn-runner-"), "buffered-depth");

    BufferedTurnRunner(
            BufferedTurnCalls calls,
            ChatGuardrailGate guardrails,
            ChatIssueRecorder issues,
            AgentMessageEvents events,
            ProviderErrorTranslator providerErrorTranslator,
            SessionMemoryService sessionMemoryService,
            LlmQualityJudgeService llmQualityJudgeService,
            AnswerConsistencyChecker answerConsistencyChecker,
            ReflexionService reflexionService,
            TaskPlanningService taskPlanningService,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval) {
        this.calls = calls;
        this.guardrails = guardrails;
        this.issues = issues;
        this.events = events;
        this.providerErrorTranslator = providerErrorTranslator;
        this.sessionMemoryService = sessionMemoryService;
        this.llmQualityJudgeService = llmQualityJudgeService;
        this.answerConsistencyChecker = answerConsistencyChecker;
        this.reflexionService = reflexionService;
        this.taskPlanningService = taskPlanningService;
        this.bufferedTurnTimeout = bufferedTurnTimeout;
        this.bufferedKeepAliveInterval = bufferedKeepAliveInterval;
    }

    /**
     * The prompt registry's view for an agent (its, its tenant's and the platform's overrides); the
     * shipped text until {@link ChatServiceParts} wires the settings bean.
     */
    private volatile java.util.function.Function<UUID, ResolvedDirectives> prompts =
            ResolvedDirectives.DEFAULTS_FOR_AGENT;

    void usePrompts(java.util.function.Function<UUID, ResolvedDirectives> prompts) {
        this.prompts = prompts == null ? ResolvedDirectives.DEFAULTS_FOR_AGENT : prompts;
    }

    /** L4 (default null = feature unwired). See {@link VerifierEscalationRouter}. */
    void useEscalationRouter(VerifierEscalationRouter router) {
        calls.useEscalationRouter(router);
    }

    /**
     * First-draft answer plus the client that actually produced it (the primary one after an
     * escalation).
     */
    record Draft(String answer, ChatClient client, Instant keptAttemptStartedAt) {
        Draft(String answer, ChatClient client) {
            this(answer, client, null);
        }

        /**
         * Start of the attempt that wrote the kept answer: after an escalation, the strong re-run,
         * else {@code turnStart}.
         */
        Instant since(Instant turnStart) {
            return keptAttemptStartedAt != null ? keptAttemptStartedAt : turnStart;
        }
    }

    /**
     * The schema this TURN is bound to, or null. {@code Agent#getResultSchemaJson} is the agent-JOB
     * schema; it only governs a turn whose goal carries the schema instruction (the job path
     * appends the schema verbatim), never an ordinary chat reply.
     */
    static String turnOutputSchema(Agent agent, String userMessage) {
        String schema = agent == null ? null : agent.getResultSchemaJson();
        if (schema == null
                || schema.isBlank()
                || userMessage == null
                || !userMessage.contains(schema)) {
            return null;
        }
        return schema;
    }

    /**
     * Runs one buffered depth's whole turn and wraps it back into the single-element {@code
     * Flux<String>} every caller (SSE controller included) expects - the one place {@link
     * #verifyThenRespond} and {@link #planThenRespond} now share their threading, timeout and
     * exception routing instead of each carrying their own copy.
     *
     * <p>{@code work} runs on {@link #bufferedDepthScheduler}, off the subscribing thread, so the {@code timeout}
     * below can arm and a keepalive can interleave. The turn's explicit {@link TurnContext#scope()} is re-applied
     * around it: the schema-per-tenant reads inside a turn resolve the tenant from the ThreadLocal, so this is
     * load-bearing. A timeout arrives as {@link java.util.concurrent.TimeoutException} (translated like a transport
     * timeout) and cancelling the timed-out turn interrupts its virtual thread, so a hung provider call stops.
     *
     * @param logLabel the turn shape, for the error log ("Chat call" / "Planned chat call")
     * @param work the whole buffered turn; never returns null (see {@link
     *     #finalizeBufferedAnswer}), which matters because {@code Mono.fromCallable} treats a null
     *     return as an EMPTY Mono - i.e. zero chunks, the exact failure this audit was about
     */
    private Flux<String> bufferedTurn(
            TurnContext context,
            Agent agent,
            UUID agentId,
            String sessionId,
            String logLabel,
            TurnRecorder journal,
            Supplier<String> work) {
        Flux<String> turn =
                Flux.defer(
                        () -> {
                            // The C1 version pin is read here, on the subscribing thread, exactly
                            // as TenantScope.capture() did; the tenant is the turn's own.
                            TenantScope tenant =
                                    context.scope(
                                            com.agenticai.core.security.AgentVersionPin.current());
                            return Mono.fromCallable(() -> tenant.get(work))
                                    .subscribeOn(bufferedDepthScheduler)
                                    .flux()
                                    // The limit is worked out per subscription: an enclosing deadline
                                    // (an agent job's own timeout) can raise it.
                                    .timeout(bufferedLimit(context, Instant.now()));
                        })
                // Same exception routing as streamFast - see that method's comment.
                .onErrorMap(
                        e ->
                                e instanceof ToolStepLimitExceededException
                                                || e instanceof GuardrailViolationException
                                                || e
                                                        instanceof
                                                        LlmGatewayContentPolicyViolationException
                                        ? e
                                        : providerErrorTranslator.translate(
                                                e, agent.getProviderName()))
                .doOnError(
                        e ->
                                log.error(
                                        "{} failed for agent {} session {}",
                                        logLabel,
                                        agentId,
                                        sessionId,
                                        e));
        // ADR-0020: a turn that ends without an answer still closes its journal record (a settled turn already
        // committed; a second commit is a no-op). No operators at all while the journal is off.
        return journal.active()
                ? turn.doOnError(e -> journal.commit(null, "error:" + e.getClass().getSimpleName()))
                        .doOnCancel(() -> journal.commit(null, "cancelled"))
                : turn;
    }

    /**
     * How long one buffered turn may run: the turn's {@code turnTimeoutSeconds} setting (platform {@code
     * app.llm.http.buffered-turn-timeout} unless a tenant or agent set another), and the enclosing deadline
     * of an agent job when there is one: sooner ends the turn at the deadline, later extends the platform
     * default (a job has no browser that gives up at 300 s) but never an explicit limit. See {@link
     * TurnTimeLimit}.
     */
    Duration bufferedLimit(TurnContext context, Instant now) {
        return TurnTimeLimit.buffered(context, now, bufferedTurnTimeout);
    }

    /** The configured {@code buffered-turn-timeout}, for the notice a timed-out turn is reported with. */
    Duration bufferedTurnTimeout() {
        return bufferedTurnTimeout;
    }

    /**
     * Interleaves an empty SSE chunk every {@link #bufferedKeepAliveInterval} until {@code answer}
     * terminates - see that field's javadoc for why a buffered depth needs one and why an EMPTY
     * chunk is the right shape.
     *
     * <p>Applied in {@link #chat} OUTSIDE {@code attemptHostedChain} on purpose: that wrapper gates
     * provider failover on "has this link emitted anything yet", and a keepalive emitted from
     * inside it would read as real output and disarm failover for the rest of the chain. Out here
     * it cannot.
     */
    Flux<String> withBufferedKeepAlive(Flux<String> answer) {
        return Flux.defer(
                () -> {
                    Sinks.Empty<Void> finished = Sinks.empty();
                    Flux<String> keepAlive =
                            Flux.interval(bufferedKeepAliveInterval, bufferedKeepAliveInterval)
                                    .map(tick -> "")
                                    .takeUntilOther(finished.asMono());
                    // doFinally, not doOnComplete: a turn that errors or is cancelled must stop the
                    // beats too, or the merged stream would never terminate.
                    return Flux.merge(
                            answer.doFinally(signal -> finished.tryEmitEmpty()), keepAlive);
                });
    }

    /**
     * The end of a buffered turn: decides what the person is shown and what is stored. A finished
     * answer is stored, announced and sampled by the quality judge, after the fabricated-generation-URL
     * correction (see below). Anything else - a reply cut off at the output limit, stopped by the
     * provider's safety system, an unusable tool call, or nothing at all - is shown with the notice
     * from {@code app.chat.finish.*}, kept in the transcript as a cut-off or failed reply, and is
     * neither announced nor judged (the dispatcher does not cache it either). The outcome is on the
     * turn's {@link TurnReplyOutcome}, set by the model calls; an empty answer is marked here.
     *
     * <p>Buffered-depth counterpart to {@code streamFast}'s empty-reply and fabricated-generation-URL
     * handling - both were FAST-only (2026-09-17 reasoning-depth audit), so on
     * CAREFUL/RIGOROUS/PLANNED a blank completion reached the client as a single empty SSE chunk and a
     * fabricated generation URL was persisted verbatim into session history for the next turn to
     * treat as fact.
     *
     * @param answer the final, already-guardrailed answer text
     * @param turnStartedAt the instant this turn's tool calls began, for the "was there any
     *     corroborating tool evidence?" half of the fabrication check - same {@code
     *     collectToolEvidenceSince} source streamFast's check uses
     * @return the text to emit (the model's text, then any notice)
     */
    private String settleBufferedReply(
            TurnContext context,
            UUID agentId,
            String sessionId,
            String memorySessionId,
            ChatClient turnClient,
            String userMessage,
            String answer,
            Instant turnStartedAt,
            TurnRecorder journal) {
        TurnReplyOutcome reply = context.reply();
        if (reply.isComplete() && (answer == null || answer.isBlank())) {
            log.warn(
                    "Agent {} session {} completed with a genuinely empty response - no text, no error",
                    agentId,
                    sessionId);
            reply.mark(TurnReplyOutcome.Kind.EMPTY);
        }
        if (!reply.isComplete()) {
            TurnReplyOutcome.State state = reply.snapshot();
            String body = answer == null ? "" : answer;
            String notice = calls.finishNotice(agentId, state);
            String emitted = notice == null ? body : ReplyFinish.join(body, notice);
            journal.commit(emitted, state.kind().name().toLowerCase(java.util.Locale.ROOT));
            sessionMemoryService.appendTurn(
                    memorySessionId,
                    ReplyFinish.storedTurn(state.kind(), body, notice, emitted)
                            .withTools(context.toolActivity().ledger().snapshotOrNull()));
            return emitted;
        }
        boolean fabricatedUrl =
                containsFabricatedGenerationUrl(answer, prompts.apply(agentId))
                && calls.collectToolEvidenceSince(agentId, sessionId, turnStartedAt) == null;
        journal.verify("fabricated-generation-url", !fabricatedUrl);
        if (fabricatedUrl) {
            answer = issues.recordAndCorrectFabricatedGenerationUrl(agentId, sessionId, answer);
        }
        journal.commit(answer, "completed");
        sessionMemoryService.appendTurn(
                memorySessionId, ChatTurn.assistant(answer, context.toolActivity().ledger().snapshotOrNull()));
        events.emitAgentMessageCompleted(agentId, sessionId, context.tenantId(), answer, "live");
        // Gap 2 - fire-and-forget, never affects this already-computed answer.
        llmQualityJudgeService.sampleAsync(turnClient, agentId, sessionId, userMessage, answer);
        return answer;
    }

    /**
     * {@link ReasoningDepth#CAREFUL}/{@link ReasoningDepth#RIGOROUS} path - runs the tool-enabled
     * turn ONCE, synchronously ({@code .call()} not {@code .stream()}, so tools still execute
     * exactly once, correctly - see {@link AnswerConsistencyChecker}'s javadoc for why resampling
     * the whole turn instead would be unsafe), then applies Stage A's self-consistency
     * check-and-revise (both tiers) and, for RIGOROUS only, Stage B's Reflexion critique - both
     * fail open to the prior answer on any error. The result is wrapped back into a single-element
     * {@code Flux<String>} so this method's signature and every caller (SSE controller included)
     * are unaffected; the client simply receives the whole answer as one chunk instead of a token
     * stream for these two tiers - an explicit, documented UX trade for a verified answer, not an
     * oversight.
     *
     * <p>Verification-audit fix (2026-08-09, items 5+16): both checkers used to see only the
     * answer's TEXT - re-judging it for plausibility against itself, never against what this turn's
     * tools actually returned, and never against anything said earlier in the same session. {@code
     * turnStartedAt} plus {@link #collectToolEvidenceSince} reads back the tool results {@code
     * toolCallLogService} already recorded for this exact turn (same source {@link
     * #firstToolFailureSince} and {@code chatWithParams}'s {@code ToolCallSummary} already use - no
     * new capture mechanism), and {@link #recentHistoryBlock} hands the checkers a bounded slice of
     * {@code history} - both threaded into the checker prompts so a plausible- sounding but wrong
     * or self-contradicting answer has real evidence to be caught against.
     */
    Flux<String> verifyThenRespond(
            TurnContext context,
            Agent agent,
            UUID agentId,
            String sessionId,
            String memorySessionId,
            ChatClient chatClient,
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            String experimentVariants,
            boolean rigorous) {
        // ADR-0020: every depth is journaled; the tools are wrapped so a refused or parked call is recorded as such.
        TurnRecorder journal = TurnJournals.begin(context, agentId, sessionId, userMessage, attachments, tools);
        List<ToolCallback> journaledTools = journal.wrapTools(tools, context.approval());
        return bufferedTurn(
                context,
                agent,
                agentId,
                sessionId,
                "Chat call",
                journal,
                () -> {
                    Instant turnStartedAt = Instant.now();
                    journal.modelCall(String.valueOf(agent.getProviderName()), agent.getModel(), "primary");
                    // #848 (LLM gateway seam) - CAREFUL/RIGOROUS never had provider
                    // fallback before this (only streamFast/FAST did, via #829) -
                    // callBufferedTurn closes that gap using the same "flat one-shot
                    // retry, original error wins if fallback also fails" discipline
                    // streamFast already established, and (2026-09-17) also carries
                    // streamFast's Gemini thought_signature retry - see its javadoc.
                    Draft draft =
                            calls.callFirstDraft(
                                    agent,
                                    agentId,
                                    sessionId,
                                    chatClient,
                                    systemPrompt,
                                    history,
                                    userMessage,
                                    journaledTools,
                                    attachments,
                                    context,
                                    experimentVariants);
                    // L4: every later pass uses the client that wrote the kept answer (primary
                    // after escalation).
                    ChatClient turnClient = draft.client();
                    String rawAnswer = draft.answer();

                    String answer = rawAnswer == null ? "" : rawAnswer;
                    ChatParityProperties parity = calls.parity();
                    String toolEvidence =
                            VerifierGuard.boundEvidence(
                                    calls.collectToolEvidenceSince(agentId, sessionId, turnStartedAt), parity);
                    String recentHistory = recentHistoryBlock(history, parity.getVerifierHistoryWindow());
                    // Only an answer is verified: a reply that was cut off or stopped by the safety
                    // system is not a draft for the checkers to "improve" into something that reads
                    // as finished. A verifier rewrites WITHOUT tools (re-running them could repeat a
                    // side effect), so the rewrite is accepted only if it is not a fraction of the
                    // answer it replaces (app.chat.parity.verifier-*).
                    if (context.reply().isComplete()) {
                        String checked =
                                answerConsistencyChecker.checkAndRevise(
                                        turnClient,
                                        userMessage,
                                        answer,
                                        toolEvidence,
                                        recentHistory,
                                        agentId,
                                        sessionId);
                        String beforeCheck = answer;
                        answer = VerifierGuard.acceptRevision(answer, checked, parity);
                        journal.verify("self-consistency", answer.equals(beforeCheck));
                        if (rigorous) {
                            String critiqued =
                                    reflexionService.critiqueAndMaybeRevise(
                                            turnClient,
                                            userMessage,
                                            answer,
                                            toolEvidence,
                                            recentHistory,
                                            agentId,
                                            sessionId);
                            String beforeCritique = answer;
                            answer = VerifierGuard.acceptRevision(answer, critiqued, parity);
                            journal.verify("reflexion", answer.equals(beforeCritique));
                        }
                    }

                    // Content guardrails, OUTPUT path - buffered depth, so a violating
                    // answer is genuinely withheld (replaced) before anything is shown
                    // or persisted. Fail-open on error; audits every block.
                    String beforeGuardrails = answer;
                    answer =
                            guardrails.enforceOutboundGuardrails(
                                    agent,
                                    agentId,
                                    sessionId,
                                    context.tenantId(),
                                    turnClient,
                                    answer);
                    journal.guard(
                            "outbound-enforced",
                            java.util.Objects.equals(answer, beforeGuardrails) ? "passed" : "withheld");
                    // Empty-reply line, notices for a reply that is not an answer, and the
                    // fabricated-URL correction - see settleBufferedReply. Always returns text to
                    // show, so an empty bubble is unreachable.
                    return settleBufferedReply(
                            context,
                            agentId,
                            sessionId,
                            memorySessionId,
                            turnClient,
                            userMessage,
                            answer,
                            turnStartedAt,
                            journal);
                });
    }

    /**
     * {@link ReasoningDepth#PLANNED} path (Agent Planning &amp; Multi-Agent Collaboration Build
     * Plan, Stage 1) - same "runs the tool-enabled turn synchronously via {@code .call()}, wrapped
     * back into a single-element Flux" shape {@link #verifyThenRespond} already established, for
     * the same reason: a plan has to exist BEFORE the turn starts, and a possible re-plan-and-retry
     * has to happen AFTER it finishes, neither of which is compatible with live token streaming.
     *
     * <p>Flow: one upfront {@link TaskPlanningService#planIfNeeded} call produces an ordered step
     * list (or {@code null} if the request didn't need one); if present, it's injected as its own
     * {@code SystemMessage} ahead of the conversation, the same "small, per-turn system block"
     * shape {@link #prependMemoryBlock} already uses for recalled memories. The turn then runs
     * once, normally. If a plan was made AND a tool call during this turn recorded an error (see
     * {@link #firstToolFailureSince}), exactly one bounded re-plan-and-retry fires - never a second
     * one, matching {@link ReflexionService}'s single-retry discipline - with the revised remaining
     * steps replacing the original plan block for the retry only.
     */
    Flux<String> planThenRespond(
            TurnContext context,
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<ToolCallback> tools,
            List<ChatAttachmentPayload> attachments,
            String experimentVariants,
            AgentStepEventEmitter stepEvents) {
        final String memorySessionId = SessionMemoryService.boundMemorySessionId();
        TurnRecorder journal = TurnJournals.begin(context, agentId, sessionId, userMessage, attachments, tools);
        List<ToolCallback> journaledTools = journal.wrapTools(tools, context.approval());
        return bufferedTurn(
                context,
                agent,
                agentId,
                sessionId,
                "Planned chat call",
                journal,
                () -> { // SEC-004: scoped id bound by the caller, read once on entry
                    String plan =
                            taskPlanningService.planIfNeeded(
                                    chatClient,
                                    userMessage,
                                    agentId,
                                    sessionId,
                                    tools.stream()
                                            .map(t -> t.getToolDefinition().name())
                                            .distinct()
                                            .toList());
                    // Step-event vocabulary (2026-09-09): the plan is the one piece of
                    // PLANNED-depth reasoning a user can meaningfully watch, and this
                    // is the only place it exists as text - it is injected as a system
                    // block, never streamed. No-op on the shared disabled emitter.
                    if (plan != null && stepEvents != null) {
                        stepEvents.plan(plan);
                    }
                    journal.guard("plan", plan == null ? "none" : "made");
                    List<Message> messages =
                            plan == null
                                    ? history
                                    : prependPlanBlock(plan, history, prompts.apply(agentId));
                    Instant turnStartedAt = Instant.now();
                    journal.modelCall(String.valueOf(agent.getProviderName()), agent.getModel(), "primary");

                    // #848 (LLM gateway seam) - same provider-fallback coverage
                    // extended to CAREFUL/RIGOROUS above; see verifyThenRespond's
                    // comment and callBufferedTurn's javadoc (which also brings
                    // streamFast's Gemini thought_signature retry to this path).
                    Draft draft =
                            calls.callFirstDraft(
                                    agent,
                                    agentId,
                                    sessionId,
                                    chatClient,
                                    systemPrompt,
                                    messages,
                                    userMessage,
                                    journaledTools,
                                    attachments,
                                    context,
                                    experimentVariants);
                    ChatClient turnClient = draft.client();
                    String rawAnswer = draft.answer();
                    String answer = rawAnswer == null ? "" : rawAnswer;

                    if (plan != null) {
                        String failure =
                                calls.firstToolFailureSince(
                                        agentId, sessionId, draft.since(turnStartedAt));
                        if (failure != null) {
                            String revisedPlan =
                                    taskPlanningService.replan(
                                            turnClient,
                                            userMessage,
                                            plan,
                                            failure,
                                            agentId,
                                            sessionId);
                            // Same hook as the initial plan above - a second `plan`
                            // event on the same turn IS the re-plan, in order.
                            if (revisedPlan != null && stepEvents != null) {
                                stepEvents.plan(revisedPlan);
                            }
                            List<Message> retryMessages =
                                    revisedPlan == null
                                            ? history
                                            : prependPlanBlock(
                                                    revisedPlan, history, prompts.apply(agentId));
                            // The re-plan retry is an IMPROVEMENT on an answer this turn already
                            // has, so it must never be able to destroy it (2026-09-17 audit):
                            // before
                            // this, any failure here - including the ToolStepLimitExceededException
                            // that only PLANNED can reach, because AgentToolProvider creates ONE
                            // stepCounter per turn and this is the turn's SECOND tool-enabled
                            // completion against it - propagated and failed the whole turn,
                            // emitting
                            // nothing at all, even though `answer` was sitting right here. Same
                            // fail-open posture as every other optional pass on this path
                            // (planIfNeeded, replan, the consistency/Reflexion checkers).
                            TurnReplyOutcome.State firstAnswersOutcome = context.reply().snapshot();
                            try {
                                String retryAnswer =
                                        calls.callBufferedTurn(
                                                agent,
                                                agentId,
                                                sessionId,
                                                turnClient,
                                                systemPrompt,
                                                retryMessages,
                                                userMessage,
                                                journaledTools,
                                                attachments,
                                                context,
                                                experimentVariants);
                                if (retryAnswer != null && !retryAnswer.isBlank()) {
                                    answer = retryAnswer;
                                } else {
                                    context.reply().restore(firstAnswersOutcome);
                                }
                            } catch (RuntimeException e) {
                                log.warn(
                                        "PLANNED re-plan retry failed for agent {} session {} - keeping the "
                                                + "answer the first attempt already produced",
                                        agentId,
                                        sessionId,
                                        e);
                                context.reply().restore(firstAnswersOutcome);
                            }
                        }
                    }

                    // Content guardrails, OUTPUT path - same buffered-depth withholding
                    // as verifyThenRespond; see enforceOutboundGuardrails' javadoc.
                    String beforeGuardrails = answer;
                    answer =
                            guardrails.enforceOutboundGuardrails(
                                    agent,
                                    agentId,
                                    sessionId,
                                    context.tenantId(),
                                    turnClient,
                                    answer);
                    journal.guard(
                            "outbound-enforced",
                            java.util.Objects.equals(answer, beforeGuardrails) ? "passed" : "withheld");
                    // Empty-reply line, notices, fabricated-URL correction - same as
                    // verifyThenRespond; see settleBufferedReply.
                    return settleBufferedReply(
                            context,
                            agentId,
                            sessionId,
                            memorySessionId,
                            turnClient,
                            userMessage,
                            answer,
                            turnStartedAt,
                            journal);
                });
    }

}
