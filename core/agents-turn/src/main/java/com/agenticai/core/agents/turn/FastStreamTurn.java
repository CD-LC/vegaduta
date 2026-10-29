package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatAttachments.userSpec;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.containsFabricatedGenerationUrl;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import com.agenticai.core.agents.turnjournal.TurnRecorder;
import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import com.agenticai.core.chat.FinishReason;
import com.agenticai.core.chat.FinishReasonAdvisor;
import com.agenticai.core.chat.FinishReasonCapture;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.TerminalRoundStreamAdvisor;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.ToolLoopTranscript;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * One token-streaming {@link ReasoningDepth#FAST} turn, moved out of {@link
 * ChatTurnExecutor#streamFast} (extract-only split): the primary provider stream, its
 * completion-time audit and persistence, and the empty-reply fallback. The recovery ladder for a
 * failed stream (Gemini thought_signature retry, fallback provider, half-response close) lives in
 * {@link FastStreamRecovery}, which works on this object's per-turn state.
 *
 * <p>A new instance per turn; it holds that turn's values (ADR-0022 stage 3: the tenant comes from
 * the explicit {@link TurnContext}, never from the ThreadLocal) and reads its collaborators from
 * the owning {@link ChatTurnExecutor}.
 */
final class FastStreamTurn {

    final ChatTurnExecutor owner;
    final TurnContext context;
    final Agent agent;
    final UUID agentId;
    final String sessionId;
    final ChatClient chatClient;
    final String systemPrompt;
    final List<Message> history;
    final String userMessage;
    final List<ToolCallback> tools;
    final List<ChatAttachmentPayload> attachments;
    final String experimentVariants;
    final AgentStepEventEmitter stepEvents;

    /** SEC-004: the scoped memory id bound by the caller; sessionId stays raw for events/metrics. */
    final String memorySessionId;

    /** ADR-0020: NOOP unless app.turn-journal.enabled=true; never throws into the turn. */
    final TurnRecorder turn;

    final List<ToolCallback> journaledTools;
    final StringBuilder accumulated = new StringBuilder();

    /**
     * Where the provider's finish reason of the latest model call is left (see {@link
     * FinishReasonAdvisor}); read after each pass to decide whether the reply is an answer.
     */
    final FinishReasonCapture finish = new FinishReasonCapture();

    /**
     * The conversation after each tool round of the primary pass, recorded by the tool loop inside
     * Spring AI: what a continuation pass is sent so it resumes from the real transcript.
     */
    final ToolLoopTranscript transcript;

    /** How many tool calls had finished when this turn started, to tell what a pass called. */
    final int callsAtStart;

    /** Where a platform-written notice starts in {@link #accumulated}, or -1 when none was added. */
    private volatile int noticeStart = -1;

    private int lengthPasses;
    private int toolCallRetryPasses;

    /**
     * Tool-loop rounds the turn's extra passes (continuation, tool-call retry) already ran. The primary
     * pass's rounds are read straight off its {@link #transcript}; each later pass's transcript is
     * seeded with the running total ({@link ToolLoopTranscript#roundsSpentBefore}) so the loop's round
     * cap counts the turn's passes together instead of refilling on each one.
     */
    private final java.util.concurrent.atomic.AtomicInteger extraToolRounds =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Stands in for the outcome of a turn built without a context (a unit-test fixture). */
    private final TurnReplyOutcome detachedReply = new TurnReplyOutcome();

    /** Stands in for the tool activity of a turn built without a context (a unit-test fixture). */
    private final TurnToolActivity detachedActivity = new TurnToolActivity();

    /**
     * Fabricated-generation-URL guard (2026-08-10): live incident, confirmed twice despite
     * TOOL_TRUST_DIRECTIVE already forbidding it in the system prompt - a hosted model still
     * emitted a fake oaidalleapiprodscus.blob.core.windows.net URL with an invented SAS signature
     * (the target 404s/auth-fails - a real one could only ever come from an actual generation-tool
     * result, never from the model's own knowledge). Prompt instructions alone are not reliably
     * stopping this, so this is the deterministic backstop - see containsFabricatedGenerationUrl's
     * own javadoc for exactly what it catches and its known limitation (it cannot un-send tokens
     * already streamed live to the client this one time; it only stops the lie from persisting
     * into session history and feeds observability).
     */
    final Instant fabricationCheckStartedAt;

    /** What the model is sent besides the tools: system prompt, history, message, attachments. */
    record Prompt(
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<ChatAttachmentPayload> attachments) {}

    FastStreamTurn(
            ChatTurnExecutor owner,
            TurnContext context,
            Agent agent,
            UUID agentId,
            String sessionId,
            ChatClient chatClient,
            Prompt prompt,
            List<ToolCallback> tools,
            String experimentVariants,
            AgentStepEventEmitter stepEvents) {
        String userMessage = prompt.userMessage();
        List<ChatAttachmentPayload> attachments = prompt.attachments();
        this.owner = owner;
        this.context = context;
        this.transcript = owner.newTranscript(context, agentId);
        this.agent = agent;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.chatClient = chatClient;
        this.systemPrompt = prompt.systemPrompt();
        this.history = prompt.history();
        this.userMessage = userMessage;
        this.tools = tools;
        this.attachments = attachments;
        this.experimentVariants = experimentVariants;
        this.stepEvents = stepEvents;
        // Statement order is the original streamFast's: bound memory id, journal, wrapped tools,
        // then the fabrication-check start instant.
        this.memorySessionId = SessionMemoryService.boundMemorySessionId();
        // The turn's own id (TurnContext#turnId), the same one the response header names, so the client can open
        // this turn; every attempt of the turn (a hosted-chain failover) appends to the same chain.
        this.turn = TurnJournalGateway.begin(agentId, context == null ? null : context.turnId(), sessionId);
        turn.intake(
                userMessage,
                attachments == null ? 0 : attachments.size(),
                tools == null ? 0 : tools.size(),
                null,
                TurnJournals.runToolNames(context));
        TurnJournals.recordApprovalDecision(turn, context);
        this.journaledTools = turn.wrapTools(tools, context == null ? null : context.approval());
        this.fabricationCheckStartedAt = Instant.now();
        this.callsAtStart = activity().finishedCalls();
        // A new reply attempt (the hosted chain re-runs a turn on the next provider): whatever the
        // attempt before it ended with does not apply to this one.
        reply().begin();
    }

    /** What this turn's tools are doing; shared with the tool wrappers that report it. */
    TurnToolActivity activity() {
        return context == null ? detachedActivity : context.toolActivity();
    }

    /** Tool-loop rounds the turn's finished passes ran: the primary pass's plus each extra pass's. */
    int toolRoundsSpent() {
        return transcript.rounds() + extraToolRounds.get();
    }

    /** Counts a finished extra pass's tool rounds into the turn's total ({@link #toolRoundsSpent()}). */
    void spendPassRounds(ToolLoopTranscript passTranscript) {
        if (passTranscript != null) {
            extraToolRounds.addAndGet(passTranscript.rounds());
        }
    }

    /**
     * One pass's idle timeout: model silence only, never tool time (see {@link StreamIdleWatch}).
     * The serving row's own {@code responseTimeoutMs} (V674, carried on {@code
     * TurnModelTraits#streamIdleTimeout} by dispatchTurn) overrides the platform {@code
     * app.llm.http.stream-idle-timeout} for this attempt's stream only.
     */
    Flux<String> idleTimeout(Flux<String> stream) {
        return owner.idleTimeout(
                stream,
                activity(),
                context == null || context.modelTraits() == null
                        ? null
                        : context.modelTraits().streamIdleTimeout());
    }

    /** How this turn's reply ends; shared with the stages that cache and store it. */
    TurnReplyOutcome reply() {
        return context == null ? detachedReply : context.reply();
    }

    /**
     * The original {@code chat()} stream from before Stage A/B existed, with the same operators in
     * the same order (the three retry branches are {@link FastStreamRecovery}'s methods and the
     * prompt is built by {@link #content}), so {@link ReasoningDepth#FAST} (every agent's default)
     * is unaffected by that build plan.
     */
    Flux<String> stream() {
        FastStreamRecovery recovery = new FastStreamRecovery(this);
        turn.modelCall(String.valueOf(agent.getProviderName()), agent.getModel(), "primary");
        Flux<String> stream =
                // Direct feedback: "an option to see the llm errors and success logs...evaluate
                // the price cost per call or a task" - LlmMetricsAdvisor (registered on every
                // ChatClient this app builds) reads the agent/session params from the request
                // context to attribute the LlmCallLog row it writes back to this agent/
                // conversation; without them, the row still gets written (cost, tokens,
                // success/error, latency) just with a null agent/session.
                // A transient failure before the first token (429, 5xx) is retried here, on the
                // same provider, before the recovery ladder below considers another one.
                idleTimeout(
                                owner.retryBeforeFirstToken(
                                        content(chatClient, true),
                                        () -> content(chatClient, true),
                                        activity(),
                                        context.scope(),
                                        agentId,
                                        sessionId,
                                        stepEvents))
                        // Genuine mid-stream idle timeout - see ChatTurnExecutor#idleTimeout and
                        // app.llm.http.stream-idle-timeout's comment in application.yml. Resets on
                        // every emitted token AND on tool activity (a running tool emits no token);
                        // fires java.util.concurrent.TimeoutException (already a recognized
                        // category in ProviderErrorTranslator.translate, handled identically to a
                        // transport-level timeout) the first time the model is silent for longer
                        // than this duration with no tool running.
                        // Translates provider failures (billing, rate limit, invalid model, auth)
                        // into a clean UpstreamProviderException instead of letting Spring AI's
                        // generic RuntimeException fall through as an opaque 500. If the failure
                        // happens before any tokens are emitted (the common case: auth/billing/
                        // model errors reject the request immediately), GlobalExceptionHandler
                        // still gets a chance to map it to a proper status before the response is
                        // committed. If it happens mid-stream after tokens already flowed, the
                        // HTTP status is already committed and the SSE stream just ends - that's
                        // an inherent limit of HTTP streaming, not something this fixes.
                        //
                        // Guardrail exceptions (Autonomy/Guardrails Strategy Stage A) are
                        // deliberately excluded from provider-error translation: they originate
                        // from GuardedToolCallback, not from a failed upstream provider call, and
                        // ProviderErrorTranslator.translate() has no "I don't recognize this"
                        // fallback - it wraps every Throwable into an UpstreamProviderException
                        // unconditionally, which would relabel "tool step limit exceeded" as "the
                        // LLM provider call failed" and mislead anyone debugging it.
                        .onErrorMap(
                                e -> {
                                    if (FastStreamRecovery.isPolicyStop(e)) {
                                        return e;
                                    }
                                    // translate wraps every Throwable unconditionally in the real
                                    // provider; an inert (mock) translator may return null - map
                                    // that to the original error, never Mono.error(null).
                                    UpstreamProviderException translated =
                                            owner.providerErrorTranslator.translate(
                                                    e, agent.getProviderName());
                                    return translated == null ? e : translated;
                                })
                        .onErrorResume(recovery::retryWithoutToolsOnThoughtSignatureBug)
                        .onErrorResume(recovery::retryOnFallbackProvider)
                        .onErrorResume(recovery::closeHalfResponse)
                        // Finish-reason handling: the stream completed normally, but the provider may
                        // have stopped it for a reason that makes the text something other than an
                        // answer - cut off at the output limit (continue it), stopped by the safety
                        // system (say so), an unusable tool call (retry once). See settleFinish.
                        .concatWith(Flux.defer(this::settleFinish))
                        // Empty-response fix (2026-08-10): distinct from the "half response" case
                        // above - this is a stream that completes NORMALLY, no error at all, but
                        // the provider emitted zero real tokens (a genuinely blank completion - a
                        // live report showed exactly this: an empty assistant bubble, 0 tool
                        // calls, no error surfaced anywhere). Nothing upstream throws in this
                        // shape, so onErrorResume above never runs; this needs its own check on
                        // the SUCCESS path. Flux.defer + concatWith only evaluates/subscribes
                        // after the upstream Flux completes, by which point every real token has
                        // already been appended to accumulated - never fires while content is
                        // still arriving, and adds nothing when accumulated is non-empty (the
                        // overwhelmingly common case stays byte-for-byte identical to before this
                        // operator existed). The line shown is app.chat.finish.empty-reply-message,
                        // and the reply is marked as not an answer: it is neither cached nor stored
                        // as something the agent said.
                        .concatWith(
                                Flux.defer(
                                        () -> {
                                            if (!accumulated.isEmpty()) {
                                                return Flux.empty();
                                            }
                                            ChatTurnExecutor.log.warn(
                                                    "Agent {} session {} completed with a genuinely"
                                                            + " empty response - no tokens, no error",
                                                    agentId,
                                                    sessionId);
                                            reply().mark(Kind.EMPTY);
                                            noticeStart = 0;
                                            return Flux.just(finishNotice(reply().snapshot()));
                                        }));
        // Turn-loss fix (2026-08-07): the complete-path body below is the old doOnComplete body
        // unchanged; withAssistantTurnPersistence adds the once-guard around it plus the
        // error/cancel partial-persist. Everything else about this stream (operators above, error
        // propagation to the caller) is untouched.
        if (owner.continuationPasses(context) > 0 && tools != null && !tools.isEmpty()) {
            // Evaluated only after the primary pass completes normally (concatWith), by which time
            // every token of it is already in {@code accumulated}. Only a finished answer can be
            // "unfinished": a reply that was cut off, refused or replaced by a notice has already been
            // dealt with by the finish-reason handling above.
            stream =
                    stream.concatWith(
                            Flux.defer(
                                    () ->
                                            reply().isComplete()
                                                    ? new FastStreamContinuation(this).afterPrimaryPass()
                                                    : Flux.empty()));
        }
        if (turn.active()) {
            stream =
                    stream.doOnError(
                                    e -> turn.commit(null, "error:" + e.getClass().getSimpleName()))
                            .doOnCancel(() -> turn.commit(null, "cancelled"));
        }
        return owner.withAssistantTurnPersistence(
                stream,
                agentId,
                sessionId,
                memorySessionId,
                accumulated,
                this::onCompleteFullAnswer,
                () -> activity().ledger().snapshotOrNull());
    }

    /**
     * One continuation pass: the conversation so far ({@link ContinuationThread}) plus the nudge, with
     * the turn's tools on offer, recording its own tool rounds into {@code passTranscript}.
     */
    Flux<String> continuationContent(ContinuationThread.Request request, ToolLoopTranscript passTranscript) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (request.systemPrompt() != null) {
            spec = spec.system(request.systemPrompt());
        }
        return spec.messages(request.messages())
                .user(request.user())
                .toolCallbacks(ChatTurnExecutor.stableToolOrder(journaledTools))
                .toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, passTranscript))
                .advisors(this::configureAdvisors)
                .stream()
                .content();
    }

    /**
     * The prompt every FAST attempt sends: the primary call and the fallback provider's offer the
     * turn's (journaled, stably ordered) tools, the thought_signature retry passes {@code false}
     * so no {@code toolCallbacks} call is made at all.
     */
    Flux<String> content(ChatClient client, boolean offerTools) {
        ChatClient.ChatClientRequestSpec spec =
                client.prompt()
                        .system(systemPrompt)
                        .messages(history)
                        .user(userSpec(userMessage, attachments));
        if (offerTools) {
            spec =
                    spec.toolCallbacks(ChatTurnExecutor.stableToolOrder(journaledTools))
                            .toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
        }
        return spec.advisors(this::configureAdvisors).stream().content();
    }

    /**
     * The prompt of the reply written without tools after the provider refused the tool-call history
     * (see {@link ThoughtSignatureDegrade}): the same system prompt, history and attachments, with
     * {@code userText} - the user's message plus what the tools returned - as the request.
     */
    Flux<String> contentWithoutTools(String userText) {
        return chatClient
                .prompt()
                .system(systemPrompt)
                .messages(history)
                .user(userSpec(userText, attachments))
                .advisors(this::configureAdvisors)
                .stream()
                .content();
    }

    /** What every model call of this turn carries: attribution, step events, the finish reason. */
    void configureAdvisors(ChatClient.AdvisorSpec a) {
        a.param(LlmMetricsAdvisor.AGENT_ID_PARAM, agentId)
                .param(LlmMetricsAdvisor.SESSION_ID_PARAM, sessionId);
        // Forwards provider reasoning (Ollama "reasoning",
        // reasoning_content elsewhere) into step events so
        // the console can show live thinking. No-op when the
        // turn opted out of step events (shared disabled emitter).
        a.advisors(new ReasoningEmitAdvisor(stepEvents));
        // The provider's stop reason (max_tokens, safety, ...): the tool loop runs inside Spring
        // AI and .content() discards the response metadata that carries it.
        a.advisors(new FinishReasonAdvisor(finish));
        // app.chat.parity.fast-terminal-rounds-only: stream only the tool loop's terminal round - the
        // mid-loop narration of rounds that ended in tool calls never reaches the client.
        if (owner.parity != null && owner.parity.isFastTerminalRoundsOnly()) {
            a.advisors(new TerminalRoundStreamAdvisor());
        }
        // #1102 (direct feedback: opaque "value cannot be null" 400 on every
        // single chat turn) - resolveExperimentVariants() returns null
        // whenever a tenant has no active feature-flag experiment (the common
        // case), but Spring AI's DefaultChatClient$DefaultAdvisorSpec#param(k,
        // v) asserts its value argument non-null and throws exactly that
        // generic message - unconditionally passing experimentVariants through
        // crashed every turn, not just ones under an experiment.
        // LlmMetricsAdvisor#paramAsString already treats an absent key the
        // same as an explicitly-null one (chatClientRequest.context().get
        // returns null either way), so simply omitting the .param() call when
        // there's nothing to record is behaviorally identical to before, minus
        // the crash.
        if (experimentVariants != null) {
            a.param(LlmMetricsAdvisor.EXPERIMENT_VARIANTS_PARAM, experimentVariants);
        }
    }

    // ------------------------------------------------------------------ finish-reason handling

    /** What the finish reason makes this turn do, for its tenant and agent; read once per turn. */
    private TurnFinishLimits finishLimits() {
        TurnFinishLimits limits = finishLimits;
        if (limits == null) {
            limits = owner.finishLimits(context, agentId);
            finishLimits = limits;
        }
        return limits;
    }

    private volatile TurnFinishLimits finishLimits;

    /**
     * Acts on the provider's finish reason once a pass has completed normally. Cut off at the output
     * limit: another pass continues the reply, up to {@code app.chat.finish.max-continuations}, and
     * the pieces are joined. Stopped by the provider's safety system: the person is told so. An
     * unusable tool call: one more pass, tools offered. A reply that is still unfinished when the
     * passes run out says so. Nothing here fires for a reply that ended for another reason, or that a
     * failure already closed.
     */
    Flux<String> settleFinish() {
        if (!finishLimits().enabled() || reply().kind() == Kind.INTERRUPTED) {
            return Flux.empty();
        }
        return switch (finish.reason()) {
            case LENGTH -> finishPass(FinishReason.LENGTH);
            case MALFORMED_TOOL_CALL -> finishPass(FinishReason.MALFORMED_TOOL_CALL);
            case SAFETY -> endWith(Kind.REFUSED, finish.raw());
            default -> Flux.empty();
        };
    }

    private Flux<String> finishPass(FinishReason reason) {
        boolean cutOff = reason == FinishReason.LENGTH;
        int used = cutOff ? lengthPasses : toolCallRetryPasses;
        TurnFinishLimits limits = finishLimits();
        int limit = cutOff ? limits.maxContinuations() : limits.maxToolCallRetries();
        boolean mayPass = used < limit && (cutOff || mayRetryToolCall());
        if (!mayPass) {
            return endWith(cutOff ? Kind.TRUNCATED : Kind.MALFORMED_TOOL_CALL, null);
        }
        if (cutOff) {
            lengthPasses++;
        } else {
            toolCallRetryPasses++;
        }
        ChatTurnExecutor.log.info(
                "Agent {} session {}: the provider ended the reply with {} ({}); {} pass {} of {}",
                agentId,
                sessionId,
                finish.raw(),
                reason,
                cutOff ? "continuation" : "tool-call retry",
                used + 1,
                limit);
        turn.guard(cutOff ? "finish-continuation" : "finish-tool-call-retry", "pass-" + (used + 1));
        TurnLifecycleEvents.finishPass(stepEvents, cutOff, used, limit);
        // The nudge is the prompt nudge.reply_cut_off / nudge.retry_tool_call: the agent's, the tenant's or the
        // platform's text, else the shipped one; read in the turn's tenant.
        ResolvedDirectives promptsForTurn = context.scope().get(() -> owner.prompts.apply(agentId));
        String nudge = promptsForTurn.text(cutOff ? NudgeTexts.REPLY_CUT_OFF : NudgeTexts.RETRY_TOOL_CALL);
        ReplyFinish.PassPrompt prompt =
                ReplyFinish.passPrompt(history, userMessage, accumulated.toString(), nudge);
        // A continuation is prose: offering the tools again would let a cut-off answer rerun the
        // calls that produced it. A retry is about the tool call, so the tools stay on offer.
        boolean offerTools = !cutOff;
        // A retry is a fresh tool-loop invocation; it shares the turn's round budget, so it carries a
        // transcript like the primary pass's, seeded with the rounds the turn already spent.
        ToolLoopTranscript passTranscript =
                offerTools ? owner.newTranscript(context, agentId).roundsSpentBefore(toolRoundsSpent()) : null;
        // The previous pass completes on an HTTP-client thread that holds no TenantContext, and the
        // next one is subscribed from there: subscribe it in the turn's captured scope (AGENTS.md
        // rule 12) so the advisors' llm_call_log writes land in the tenant schema.
        Flux<String> pass =
                TenantScopedFlux.subscribeInScope(
                                context.scope(), () -> passContent(prompt, passTranscript))
                        .transform(this::idleTimeout)
                        .onErrorResume(
                                e -> {
                                    ChatTurnExecutor.log.warn(
                                            "Finish-reason pass failed for agent {} session {}",
                                            agentId,
                                            sessionId,
                                            e);
                                    if (FastStreamRecovery.isPolicyStop(e)) {
                                        return Flux.error(e);
                                    }
                                    reply().mark(Kind.INTERRUPTED);
                                    return Flux.just(AgentChatService.INTERRUPTED_REPLY_MARKER);
                                });
        // Count the pass's tool rounds before the finish check may start another pass that seeds its
        // transcript with the total: the deferred supplier runs after this pass terminated.
        return pass.concatWith(
                Flux.defer(
                        () -> {
                            spendPassRounds(passTranscript);
                            return settleFinish();
                        }));
    }

    /**
     * A tool call the provider could not use is retried only when no tool has run this turn: the
     * retry re-sends the conversation without the tool results of this turn (Spring AI keeps them
     * inside its loop), so after a tool ran it could repeat the call's side effect.
     */
    private boolean mayRetryToolCall() {
        if (context.approval() != null && context.approval().isParked()) {
            return false;
        }
        try {
            return context.scope()
                            .get(
                                    () ->
                                            owner.collectToolEvidenceSince(
                                                    agentId, sessionId, fabricationCheckStartedAt))
                    == null;
        } catch (RuntimeException e) {
            ChatTurnExecutor.log.warn(
                    "Agent {} session {}: could not tell whether a tool ran this turn; not retrying the tool call",
                    agentId,
                    sessionId,
                    e);
            return false;
        }
    }

    private Flux<String> passContent(ReplyFinish.PassPrompt prompt, ToolLoopTranscript passTranscript) {
        ChatClient.ChatClientRequestSpec spec =
                chatClient.prompt().system(systemPrompt).messages(prompt.messages()).user(prompt.user());
        if (passTranscript != null) {
            spec = spec.toolCallbacks(ChatTurnExecutor.stableToolOrder(journaledTools))
                    .toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, passTranscript));
        }
        return spec.advisors(this::configureAdvisors).stream().content();
    }

    /** The notice for {@code state} from the prompt registry, for this turn's agent and tenant. */
    private String finishNotice(TurnReplyOutcome.State state) {
        ResolvedDirectives promptsForTurn = context.scope().get(() -> owner.prompts.apply(agentId));
        return ReplyFinish.notice(owner.finish, state, promptsForTurn);
    }

    /** Ends the reply as {@code kind}: marks it not-an-answer and tells the person why. */
    private Flux<String> endWith(Kind kind, String detail) {
        return Flux.defer(
                () -> {
                    reply().mark(kind, detail);
                    noticeStart = accumulated.length();
                    String notice = finishNotice(reply().snapshot());
                    return Flux.just(ReplyFinish.separator(!accumulated.isEmpty()) + notice);
                });
    }

    /** The complete-path body: outbound audit, fabrication check, persistence, judge sampling. */
    private void onCompleteFullAnswer(String fullAnswer) {
        if (reply().isHeldForOutputCheck() && reply().isComplete()) {
            // app.chat.parity.fast-buffering-triggers contains OUTPUT_GUARDRAILS: nothing has been sent
            // yet (the dispatcher holds the reply), so a violating answer can genuinely be withheld, as on
            // the buffered depths - and what is stored below is what the person is shown. Pattern-based
            // guardrails only: this runs on the stream-completion thread, where the semantic toxicity
            // check (a blocking model call) must not run; the buffered depths run that one too.
            final String held = fullAnswer;
            String checked =
                    context.scope()
                            .get(
                                    () ->
                                            owner.guardrails.enforceOutboundGuardrails(
                                                    agent, agentId, sessionId, context.tenantId(), null, held));
            boolean withheld = checked != null && !checked.equals(held);
            if (withheld) {
                reply().replaceWith(checked);
                fullAnswer = checked;
            }
            turn.guard("outbound-enforced", withheld ? "withheld" : "passed");
        } else {
            // Content guardrails, OUTPUT path, FAST depth: detection + audit only - tokens are
            // already delivered, see auditStreamedOutboundViolation's javadoc for the tradeoff.
            owner.guardrails.auditStreamedOutboundViolation(
                    agent, agentId, sessionId, context.tenantId(), fullAnswer, context.tenantContext());
            turn.guard("outbound-audit", "ran");
        }
        // Fabricated-generation-URL guard: same "tokens already delivered, detect for the record"
        // tradeoff as the guardrail audit above - this cannot recall what already streamed live,
        // but it keeps the lie from being replayed as trusted context on the NEXT turn and gives
        // observability into how often it happens. Persist the corrected text instead of the raw
        // fabrication when triggered. The tool-log read runs in the turn's tenant scope: this
        // fires on the stream-completion thread.
        String toPersist = fullAnswer;
        // The prompt settings are read in the turn's tenant: this runs on the completion thread.
        ResolvedDirectives promptsForTurn = context.scope().get(() -> owner.prompts.apply(agentId));
        boolean fabricatedUrl =
                containsFabricatedGenerationUrl(fullAnswer, promptsForTurn)
                        && context.scope()
                                        .get(
                                                () ->
                                                        owner.collectToolEvidenceSince(
                                                                agentId,
                                                                sessionId,
                                                                fabricationCheckStartedAt))
                                == null;
        turn.verify("fabricated-generation-url", !fabricatedUrl);
        if (fabricatedUrl) {
            toPersist =
                    owner.issues.recordAndCorrectFabricatedGenerationUrl(
                            agentId, sessionId, fullAnswer, promptsForTurn);
        }
        TurnReplyOutcome.State outcome = reply().snapshot();
        if (outcome.kind() != Kind.COMPLETE) {
            // Not an answer: a reply that was cut off, stopped by the safety system, closed after a
            // failure or replaced by the "no response" line. It is kept in the transcript so the
            // person sees what happened, marked so the model is not shown it as something it said,
            // and it is neither announced as a completed message nor sent to the quality judge (the
            // dispatcher does not cache it either).
            int cut = noticeStart < 0 ? -1 : Math.min(noticeStart, toPersist.length());
            String body = cut < 0 ? toPersist : toPersist.substring(0, cut);
            String notice = cut < 0 ? null : toPersist.substring(cut).strip();
            turn.commit(toPersist, outcome.kind().name().toLowerCase(java.util.Locale.ROOT));
            owner.sessionMemoryService.appendTurn(
                    memorySessionId,
                    ReplyFinish.storedTurn(outcome.kind(), body, notice, toPersist)
                            .withTools(activity().ledger().snapshotOrNull()));
            return;
        }
        turn.commit(toPersist, "completed");
        owner.sessionMemoryService.appendTurn(
                memorySessionId, ChatTurn.assistant(toPersist, activity().ledger().snapshotOrNull()));
        StreamedReplyCompletion.afterPersisted(
                owner.events,
                owner.llmQualityJudgeService,
                new StreamedReplyCompletion.Reply(
                        chatClient,
                        agentId,
                        sessionId,
                        context.tenantId(),
                        userMessage,
                        toPersist,
                        fullAnswer),
                context.tenantContext());
    }
}
