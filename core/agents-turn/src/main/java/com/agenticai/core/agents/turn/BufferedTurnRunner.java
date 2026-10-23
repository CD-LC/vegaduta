package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.AgentChatService.EMPTY_REPLY_FALLBACK;
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
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.security.logging.LogSafe;
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
     * Total-duration ceiling for ONE buffered-depth turn (CAREFUL/RIGOROUS/PLANNED) - the
     * counterpart to {@link #streamIdleTimeout}, which structurally cannot apply to these depths:
     * "idle" means "no new token", and a buffered depth emits no tokens at all until it is
     * finished, so there is nothing to re-arm a timer on. Before this existed the buffered paths
     * had NO Reactor timeout of any kind, and {@code app.llm.http.read-timeout} stops timing the
     * moment response headers arrive (see its comment in application.yml), so a provider that went
     * silent mid-answer parked the turn until the browser gave up at its own 300s absolute ceiling
     * - with no server-side error, no log, and no persisted turn. Deliberately set BELOW that
     * client ceiling so the failure surfaces here, as a translated provider error, rather than as a
     * dead socket.
     */
    private final Duration bufferedTurnTimeout;

    /**
     * Where a buffered depth's blocking work actually runs. It used to run on the SUBSCRIBING
     * thread, inline inside {@code Flux.defer}'s supplier, which is why neither {@link
     * #bufferedTurnTimeout} nor the keepalive above could exist: {@code Flux.defer} evaluates its
     * supplier BEFORE the resulting publisher's {@code onSubscribe} reaches any downstream
     * operator, so a {@code timeout} operator's timer never started, and nothing could interleave a
     * heartbeat onto a stream whose only thread was blocked inside it.
     *
     * <p>Virtual-thread-per-task, not {@code Schedulers.boundedElastic()}: this work is a long
     * blocking I/O wait (several sequential provider completions plus tool round trips), and
     * boundedElastic caps at 10x CPU threads, so buffered turns would queue behind each other under
     * load. One virtual thread per turn is exactly the concurrency profile these turns already had
     * when they blocked the request thread - which is itself virtual here (see {@code
     * spring.threads.virtual.enabled}) - so this is a thread-of-execution change, not a capacity
     * change. Same "no pooled platform threads, no shutdown needed" property as {@link
     * #preTurnExecutor}.
     */
    private final Scheduler bufferedDepthScheduler =
            TenantAwareSchedulers.fromExecutorService(
                    TenantAwareExecutors.newVirtualThreadPerTaskExecutor("buffered-turn-runner-"),
                    "buffered-depth");

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
     * Buffered-depth counterpart to {@code streamFast}'s {@code EMPTY_REPLY_FALLBACK} {@code
     * concatWith} operator and its fabricated-generation-URL correction - both were FAST-only
     * (2026-09-17 reasoning-depth audit), so on CAREFUL/RIGOROUS/PLANNED a blank completion reached
     * the client as a single empty SSE chunk (an empty assistant bubble, no error, nothing
     * persisted, nothing logged) and a fabricated generation URL was persisted verbatim into
     * session history for the next turn to treat as fact.
     *
     * @param answer the final, already-guardrailed answer text
     * @param turnStartedAt the instant this turn's tool calls began, for the "was there any
     *     corroborating tool evidence?" half of the fabrication check - same {@code
     *     collectToolEvidenceSince} source streamFast's check uses
     * @return the text to emit AND persist (they are the same string on a buffered depth, which is
     *     exactly what makes the withheld/corrected variants honest here)
     */
    /**
     * Runs one buffered depth's whole turn and wraps it back into the single-element {@code
     * Flux<String>} every caller (SSE controller included) expects - the one place {@link
     * #verifyThenRespond} and {@link #planThenRespond} now share their threading, timeout and
     * exception routing instead of each carrying their own copy.
     *
     * <p><b>Off the subscribing thread (2026-09-17).</b> {@code work} used to run inline inside
     * {@code Flux.defer}'s supplier, i.e. on whichever thread subscribed - which meant the entire
     * turn completed (or hung) before any downstream operator saw an {@code onSubscribe}. Two
     * consequences, both fixed by moving it to {@link #bufferedDepthScheduler}: a {@code timeout}
     * operator could never arm (its timer starts at {@code onSubscribe}), and nothing could
     * interleave a keepalive onto a stream whose only thread was blocked inside it. {@link
     * TenantContext} (ADR-0022: the turn's explicit {@link TurnContext#scope()}, equal to the
     * subscribing request thread's value) is re-applied around {@code work} - the schema-per-tenant
     * reads inside a turn (session memory, tool-call log, guardrail config) resolve the tenant from
     * that ThreadLocal, so this is load-bearing, not hygiene. Same save/restore shape as {@link
     * #supplyPreTurn}.
     *
     * <p>{@code timeout} failures arrive as {@link java.util.concurrent.TimeoutException}, already
     * a recognized category in {@code ProviderErrorTranslator.translate} - handled identically to a
     * transport-level timeout, exactly like {@code streamFast}'s idle timeout. Cancelling the
     * timed-out turn also interrupts its virtual thread, so a hung provider call stops burning
     * tokens rather than running on unobserved.
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
            Supplier<String> work) {
        return Flux.defer(
                        () -> {
                            // The C1 version pin is read here, on the subscribing thread, exactly
                            // as TenantScope.capture() did; the tenant is the turn's own.
                            TenantScope tenant =
                                    context.scope(
                                            com.agenticai.core.security.AgentVersionPin.current());
                            return Mono.fromCallable(() -> tenant.get(work))
                                    .subscribeOn(bufferedDepthScheduler)
                                    .flux();
                        })
                .timeout(bufferedTurnTimeout)
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
                                        LogSafe.clean(logLabel),
                                        LogSafe.clean(agentId),
                                        LogSafe.clean(sessionId),
                                        e));
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

    private String finalizeBufferedAnswer(
            UUID agentId, String sessionId, String answer, Instant turnStartedAt) {
        if (answer == null || answer.isBlank()) {
            log.warn(
                    "Agent {} session {} completed with a genuinely empty response - no text, no error",
                    LogSafe.clean(agentId),
                    LogSafe.clean(sessionId));
            return EMPTY_REPLY_FALLBACK;
        }
        if (containsFabricatedGenerationUrl(answer)
                && calls.collectToolEvidenceSince(agentId, sessionId, turnStartedAt) == null) {
            return issues.recordAndCorrectFabricatedGenerationUrl(agentId, sessionId, answer);
        }
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
        return bufferedTurn(
                context,
                agent,
                agentId,
                sessionId,
                "Chat call",
                () -> {
                    Instant turnStartedAt = Instant.now();
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
                                    tools,
                                    attachments,
                                    context,
                                    experimentVariants);
                    // L4: every later pass uses the client that wrote the kept answer (primary
                    // after escalation).
                    ChatClient turnClient = draft.client();
                    String rawAnswer = draft.answer();

                    String answer = rawAnswer == null ? "" : rawAnswer;
                    String toolEvidence =
                            calls.collectToolEvidenceSince(agentId, sessionId, turnStartedAt);
                    String recentHistory = recentHistoryBlock(history);
                    answer =
                            answerConsistencyChecker.checkAndRevise(
                                    turnClient,
                                    userMessage,
                                    answer,
                                    toolEvidence,
                                    recentHistory,
                                    agentId,
                                    sessionId);
                    if (rigorous) {
                        answer =
                                reflexionService.critiqueAndMaybeRevise(
                                        turnClient,
                                        userMessage,
                                        answer,
                                        toolEvidence,
                                        recentHistory,
                                        agentId,
                                        sessionId);
                    }

                    // Content guardrails, OUTPUT path - buffered depth, so a violating
                    // answer is genuinely withheld (replaced) before anything is shown
                    // or persisted. Fail-open on error; audits every block.
                    answer =
                            guardrails.enforceOutboundGuardrails(
                                    agent,
                                    agentId,
                                    sessionId,
                                    context.tenantId(),
                                    turnClient,
                                    answer);
                    // Empty-reply fallback + fabricated-URL correction, both FAST-only
                    // before 2026-09-17 - see finalizeBufferedAnswer's javadoc. Always
                    // returns non-blank text, so the persistence branch below is now
                    // unconditional in practice and an empty bubble is unreachable.
                    answer = finalizeBufferedAnswer(agentId, sessionId, answer, turnStartedAt);

                    if (!answer.isEmpty()) {
                        sessionMemoryService.appendTurn(
                                memorySessionId, ChatTurn.assistant(answer));
                        events.emitAgentMessageCompleted(
                                agentId, sessionId, context.tenantId(), answer, "live");
                        // Gap 2 - fire-and-forget, never affects this already-computed answer.
                        llmQualityJudgeService.sampleAsync(
                                turnClient, agentId, sessionId, userMessage, answer);
                    }
                    return answer;
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
        return bufferedTurn(
                context,
                agent,
                agentId,
                sessionId,
                "Planned chat call",
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
                    List<Message> messages =
                            plan == null ? history : prependPlanBlock(plan, history);
                    Instant turnStartedAt = Instant.now();

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
                                    tools,
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
                                            : prependPlanBlock(revisedPlan, history);
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
                                                tools,
                                                attachments,
                                                context,
                                                experimentVariants);
                                if (retryAnswer != null && !retryAnswer.isBlank()) {
                                    answer = retryAnswer;
                                }
                            } catch (RuntimeException e) {
                                log.warn(
                                        "PLANNED re-plan retry failed for agent {} session {} - keeping the "
                                                + "answer the first attempt already produced",
                                        LogSafe.clean(agentId),
                                        LogSafe.clean(sessionId),
                                        e);
                            }
                        }
                    }

                    // Content guardrails, OUTPUT path - same buffered-depth withholding
                    // as verifyThenRespond; see enforceOutboundGuardrails' javadoc.
                    answer =
                            guardrails.enforceOutboundGuardrails(
                                    agent,
                                    agentId,
                                    sessionId,
                                    context.tenantId(),
                                    turnClient,
                                    answer);
                    // Empty-reply fallback + fabricated-URL correction, same as
                    // verifyThenRespond - see finalizeBufferedAnswer's javadoc.
                    answer = finalizeBufferedAnswer(agentId, sessionId, answer, turnStartedAt);

                    if (!answer.isEmpty()) {
                        sessionMemoryService.appendTurn(
                                memorySessionId, ChatTurn.assistant(answer));
                        events.emitAgentMessageCompleted(
                                agentId, sessionId, context.tenantId(), answer, "live");
                        // Gap 2 - fire-and-forget, never affects this already-computed answer.
                        llmQualityJudgeService.sampleAsync(
                                turnClient, agentId, sessionId, userMessage, answer);
                    }
                    return answer;
                });
    }
}
