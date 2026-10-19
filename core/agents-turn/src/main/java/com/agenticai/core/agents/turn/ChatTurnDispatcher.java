package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.context.CompactionRun;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.hosted.HostedPoolExhaustedException;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * Runs the model call and assembles the reply stream of one turn - the reasoning-depth dispatch,
 * the hosted-chain failover (and its budget-reservation release), the buffered keepalive, the
 * lifecycle hooks, the step-event bookends, the RL episode close, TTFT, failed-reply persistence,
 * the cache writes and the server-side cancel - moved verbatim out of {@link
 * AgentChatService#chat}. The operator order on the returned Flux is the original's.
 *
 * <p>ADR-0022 stage 2/3: the tenant, and the hosted turn's reservation guards, come from the
 * explicit {@link TurnContext} ({@link ChatClientSelection.Clients#context()}). The operators that
 * run on a completion thread re-apply {@link TurnContext#scope()} instead of a ThreadLocal value
 * captured on the request thread.
 */
final class ChatTurnDispatcher {

    private final AgentChatService svc;

    ChatTurnDispatcher(AgentChatService svc) {
        this.svc = svc;
    }

    Flux<String> stream(
            long ttftStartNanos,
            ChatTurnPreflight.Turn turn,
            RlTurnPlan rl,
            ChatClientSelection.Clients clients,
            ChatPromptAssembler.AssembledPrompt prompt,
            UUID requestedAgentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            AgentStepEventEmitter stepEvents) {
        ChatTurnExecutor turns = svc.turns;
        BufferedTurnRunner bufferedTurns = svc.bufferedTurns;
        FreeTierChatClientService freeTierChatClientService = svc.freeTierChatClientService;
        ChatIssueRecorder issues = svc.issues;
        Duration bufferedKeepAliveInterval = svc.bufferedKeepAliveInterval;
        RlEpisodeRecorder rlEpisodes = svc.rlEpisodes;
        ChatTtftRecorder ttft = svc.ttft;
        ChatTurnCancelService chatTurnCancelService = svc.chatTurnCancelService;
        LlmResponseCacheService responseCacheService = svc.responseCacheService;
        TurnContext context = clients.context();
        String currentTenantId = context.tenantId();
        UUID agentId = turn.agentId();
        String memorySessionId = turn.memorySessionId();
        Agent agent = turn.agent();
        com.agenticai.core.agents.hooks.HookRun hookRun = turn.hookRun();
        boolean cacheEligible = turn.cacheEligible();
        boolean semanticEligible = turn.semanticEligible();
        SemanticResponseCacheService semanticCache = turn.semanticCache();
        double effTemperature = rl.effTemperature;
        double effTopP = rl.effTopP;
        ReasoningDepth effReasoningDepth = rl.effReasoningDepth;
        ChatClient chatClient = clients.chatClient();
        List<HostedChainLink> hostedChain = clients.hostedChain();
        TenantPlan hostedPlan = clients.hostedPlan();
        String systemPrompt = prompt.systemPrompt();
        List<Message> messages = prompt.messages();
        List<ToolCallback> tools = prompt.tools();
        List<ChatAttachmentPayload> attachments = prompt.attachments();
        boolean anyMcpToolsOffered = prompt.anyMcpToolsOffered();
        CompactionRun compactionRun = prompt.compactionRun();
        boolean effectiveForceFastDepth = prompt.effectiveForceFastDepth();
        String experimentVariants = prompt.experimentVariants();
        UUID rlEpisodeId = prompt.rlEpisodeId();

        // FAST (the default, unchanged
        // since the beginning) keeps live token streaming exactly as before - see
        // streamFast's javadoc. CAREFUL/RIGOROUS trade live streaming for a
        // verified-before-shown answer - see verifyThenRespond's javadoc for why
        // that trade is unavoidable (self-consistency/Reflexion both need the
        // COMPLETE answer before anything is shown to the user, which live
        // token-by-token streaming structurally can't allow).
        //
        // PLANNED
        // gets its own branch for the same structural reason CAREFUL/RIGOROUS
        // do - see planThenRespond's javadoc.
        // One turn against a given client, dispatched by reasoning depth -
        // factored into a function so the platform-hosted pool can re-run the
        // whole turn against the next provider in its fallback chain (see
        // attemptHostedChain) without duplicating this branch.
        // Fabricated-generation-URL guard, live-stream half (2026-08-10):
        // AgentChatService.FABRICATED_GENERATION_URL_MARKERS already corrects
        // PERSISTED history after the fact, but by design cannot un-send
        // tokens already streamed live - confirmed FOUR times tonight (twice
        // via generate_image/generate_document, once via the playwright-
        // vision BROWSING tool claiming to have screenshotted a site, and a
        // fourth time on a multi-turn follow-up: the actual "go to this URL
        // and summarize it" request was stated in an EARLIER turn, and the
        // triggering message here was just "try again" - no generation/
        // extraction keyword in sight). That last case is what killed the
        // original design: gating on isGenerationRequest(userMessage) only
        // ever looks at THIS turn's text, so any short follow-up in a
        // multi-turn browsing conversation slips past it even though the
        // conversation is squarely in the risk zone this guard exists for.
        // Message-based intent detection cannot see conversation history
        // cheaply here, so it's been dropped entirely rather than patched
        // with a fifth keyword list: buffer whenever the turn offers a
        // generation tool OR any MCP-sourced tool at all (browsing
        // included), full stop. This trades live streaming away more
        // broadly - any turn on an agent with these tools loses it, not
        // just ones that look like a request - but every narrower signal
        // tried so far has had a real gap, and the cost of a false fire
        // (one reply buffered instead of streamed) is far lower than the
        // cost of a false miss (a fabricated link still reaching the user).
        boolean bufferForGenerationSafety =
                ChatPromptDirectives.offersGenerationTool(tools) || anyMcpToolsOffered;
        Instant fastDispatchStartedAt = Instant.now();
        // RL REASONING_DEPTH (2026-08-15 wiring): effReasoningDepth is the
        // bandit-selected depth when one was chosen (never PLANNED - see
        // reasoningDepthFromArmConfig's javadoc), else agent.getReasoningDepth()
        // unchanged - safe to read uniformly at all three branches below.
        // The FAST shape, with the tool set as a parameter: the normal FAST branch offers the
        // turn's tools; the depth-timeout fallback below offers none.
        java.util.function.BiFunction<
                        ChatClient, List<org.springframework.ai.tool.ToolCallback>, Flux<String>>
                fastTurn =
                        (client, turnTools) -> {
                            Flux<String> fast;
                            String previousMemoryBinding =
                                    SessionMemoryService.bindMemorySessionId(memorySessionId);
                            try {
                                fast =
                                        turns.streamFast(
                                                context,
                                                agent,
                                                agentId,
                                                sessionId,
                                                client,
                                                systemPrompt,
                                                messages,
                                                userMessage,
                                                turnTools,
                                                attachments,
                                                experimentVariants,
                                                stepEvents);
                            } finally {
                                SessionMemoryService.restoreMemorySessionId(previousMemoryBinding);
                            }
                            return bufferForGenerationSafety
                                    ? turns.bufferAndGuardGenerationClaims(
                                            context, fast, agentId, sessionId, fastDispatchStartedAt)
                                    : fast;
                        };
        // Self-correction (2026-10-18): a CAREFUL/RIGOROUS/PLANNED turn that hits
        // app.llm.http.buffered-turn-timeout used to surface as "every model failed" - the
        // whole turn was discarded after four minutes of work. Seen on a laptop running a 4B
        // model at RIGOROUS depth: five sequential model calls plus verifier calls per tool
        // never fit. Now the turn is answered at FAST depth instead, streamed, with tools
        // disabled (the buffered attempt may already have run some of them; re-running a
        // write is worse than answering without it), and the trace says so. The hosted chain
        // wrapper sees a successful turn, so no provider is marked dead for being slow.
        java.util.function.BiFunction<ChatClient, String, Flux<String>> depthTimeoutFallback =
                (client, depthName) ->
                        Flux.defer(
                                () -> {
                                    DegradedRetrySignal.flagDepthTimeout(
                                            turns.meterRegistry(), agent, depthName, stepEvents);
                                    return fastTurn.apply(client, List.of());
                                });
        Function<ChatClient, Flux<String>> dispatchTurn =
                turnClient -> {
                    ChatClient client =
                            compactionRun == null ? turnClient : compactionRun.attach(turnClient);
                    if (effectiveForceFastDepth || effReasoningDepth == ReasoningDepth.FAST) {
                        return fastTurn.apply(client, tools);
                    } else if (effReasoningDepth == ReasoningDepth.PLANNED) {
                        String previousMemoryBinding =
                                SessionMemoryService.bindMemorySessionId(memorySessionId);
                        Flux<String> planned;
                        try {
                            planned =
                                    bufferedTurns.planThenRespond(
                                            context,
                                            agent,
                                            agentId,
                                            sessionId,
                                            client,
                                            systemPrompt,
                                            messages,
                                            userMessage,
                                            tools,
                                            attachments,
                                            experimentVariants,
                                            stepEvents);
                        } finally {
                            SessionMemoryService.restoreMemorySessionId(previousMemoryBinding);
                        }
                        return planned.onErrorResume(
                                e ->
                                        DegradedRetrySignal.isTimeout(e)
                                                ? depthTimeoutFallback.apply(client, "PLANNED")
                                                : Flux.error(e));
                    } else {
                        String depthName =
                                effReasoningDepth == ReasoningDepth.RIGOROUS ? "RIGOROUS" : "CAREFUL";
                        return bufferedTurns
                                .verifyThenRespond(
                                        context,
                                        agent,
                                        agentId,
                                        sessionId,
                                        memorySessionId,
                                        client,
                                        systemPrompt,
                                        messages,
                                        userMessage,
                                        tools,
                                        attachments,
                                        experimentVariants,
                                        effReasoningDepth == ReasoningDepth.RIGOROUS)
                                .onErrorResume(
                                        e ->
                                                DegradedRetrySignal.isTimeout(e)
                                                        ? depthTimeoutFallback.apply(client, depthName)
                                                        : Flux.error(e));
                    }
                };
        // EVERY hosted turn goes through the chain wrapper now, including a
        // one-row pool. The old `size() > 1` guard is exactly why the live
        // Gemini-404 outage surfaced as a hard error: the production pool has a
        // single enabled row, so the wrapper never ran, nothing classified the
        // failure, nothing learned the row was dead, and the user got the raw
        // "choose a different model" message for a model they don't choose.
        // With one row the wrapper is still worth running - it records health,
        // and it turns the dead end into an honest pool-level message (see
        // HostedPoolExhaustedException). BYOK turns are untouched.
        //
        // Issue 4 fix (2026-08-07): the common production shape IS a one-row
        // pool, where the terminal rung above is also the ONLY rung - the
        // async self-heal (maybeSelfHealInvalidModelRow) fixes the row for the
        // NEXT turn but leaves THIS one to throw HostedPoolExhaustedException
        // regardless. exhaustedChainSyncHeal gives attemptHostedChain a
        // same-turn fast path: heal + rebuild synchronously, retry once, only
        // fall through to the pool-exhausted error if that retry also fails.
        BiFunction<HostedChainLink, Throwable, HostedChainLink> exhaustedChainSyncHeal =
                hostedChain != null
                        ? (failedLink, failure) ->
                                svc.syncHealChainExhaustionAndRebuild(
                                        failedLink,
                                        failure,
                                        agent.getMaxTokens(),
                                        effTemperature,
                                        effTopP,
                                        currentTenantId,
                                        subjectKey,
                                        PerUserBudgetService.Modality.TEXT)
                        : null;
        // The issue write below runs inside a reactor operator, on a thread that carries no
        // TenantContext; platform_issue is a tenant-schema table, so without the turn's scope it
        // resolved to a schema that does not exist and the pool-exhausted issue was never recorded.
        Flux<String> result =
                svc.buildingTurnStream(
                        agentId,
                        memorySessionId, // the SEC-004 memory id: where the failed reply is stored
                        () ->
                                hostedChain != null
                                        ? HostedChainFailover.attemptHostedChain(
                                                        hostedChain,
                                                        0,
                                                        null,
                                                        dispatchTurn,
                                                        agentId,
                                                        sessionId,
                                                        freeTierChatClientService.providerHealth(),
                                                        svc::maybeSelfHealInvalidModelRow,
                                                        exhaustedChainSyncHeal)
                                                .doOnError(
                                                        e -> {
                                                            context.scope()
                                                                    .run(
                                                                            () ->
                                                                                    issues
                                                                                            .recordHostedPoolExhaustedIssue(
                                                                                                    agentId,
                                                                                                    e));
                                                            // The whole chain failed and the tenant
                                                            // got zero
                                                            // response, but buildChainForFreeTenant
                                                            // already reserved
                                                            // a token/dollar-budget amount for the
                                                            // turn (see
                                                            // FreeTierChatClientService#buildChainForFreeTenant) that
                                                            // bought zero value - every link in the
                                                            // chain failed
                                                            // before any usage was ever recorded.
                                                            // Release it - a
                                                            // systemic pool outage must not also
                                                            // burn the tenant's
                                                            // quota on pure failures. Read the
                                                            // reservation back off
                                                            // the chain HostedChainLink already
                                                            // carries (same value
                                                            // on every link - see that record's
                                                            // javadoc; the guards are the turn
                                                            // context's budget); never
                                                            // double-fires against the advisors'
                                                            // own reconciliation,
                                                            // because it consumes the SAME
                                                            // turn-scoped guards the
                                                            // advisors use (2026-08-12 fix - a
                                                            // mid-stream idle
                                                            // timeout CANCELS a link, whose
                                                            // advisors release the
                                                            // reservations, before surfacing as a
                                                            // retryable 408 that
                                                            // can still exhaust the chain and land
                                                            // here; see
                                                            // TurnReservationGuards).
                                                            if (e
                                                                            instanceof
                                                                            HostedPoolExhaustedException
                                                                    && currentTenantId != null) {
                                                                HostedChainLink firstLink =
                                                                        hostedChain.get(0);
                                                                freeTierChatClientService
                                                                        .releaseReservations(
                                                                                currentTenantId,
                                                                                hostedPlan,
                                                                                subjectKey,
                                                                                PerUserBudgetService
                                                                                        .Modality
                                                                                        .TEXT,
                                                                                firstLink
                                                                                        .reservedTokens(),
                                                                                firstLink
                                                                                        .reservedMicroCents(),
                                                                                context
                                                                                        .budget());
                                                            }
                                                        })
                                        : dispatchTurn.apply(chatClient));

        // SSE keepalive, buffered depths only (2026-09-17 reasoning-depth audit). A
        // CAREFUL/RIGOROUS/PLANNED turn writes NOTHING to the wire until it is completely
        // finished, which every intermediary in front of this service reads as a dead origin
        // - Cloudflare's ~100s origin-response limit (524) sits below the browser's own
        // connect ceiling, and no amount of client-side tuning can move it. FAST is left
        // strictly untouched: its tokens are their own keepalive, and a voice turn (which is
        // always forced to FAST) must never see a synthetic chunk it would try to speak.
        // See withBufferedKeepAlive and bufferedKeepAliveInterval for the empty-chunk shape
        // and the one status-mapping tradeoff it carries.
        boolean bufferedDepth =
                !effectiveForceFastDepth && effReasoningDepth != ReasoningDepth.FAST;
        if (bufferedDepth
                && !bufferedKeepAliveInterval.isZero()
                && !bufferedKeepAliveInterval.isNegative()) {
            result = bufferedTurns.withBufferedKeepAlive(result);
        }

        // Agent lifecycle hooks: AFTER_MODEL / ON_ERROR / ON_RUN_END on the assembled reply stream
        // (hookRun is null
        // when the flag is off or no model/run-level hook is configured, leaving `result`
        // untouched). Applied BEFORE
        // the run-level step events below so the hooks' own step events land ahead of run_finished,
        // not after the
        // emitter has completed.
        if (hookRun != null) {
            result = hookRun.wrap(result);
        }

        // Step-event vocabulary (2026-09-09, AG-UI alignment) - run-level
        // bookends and the guardrail-exception branch, on the side channel
        // only. Gated exactly like the tool-resolution branch above: a turn
        // without step events is handed `result` untouched, with no operator
        // added, so the omnichannel/voice/workflow callers see a byte-identical
        // Flux. Placed HERE, on the fully-assembled turn (after hosted-chain
        // fallback), so run_started fires once even when attemptHostedChain
        // re-runs dispatchTurn against the next provider, and run_finished
        // reflects the whole turn's wall clock. See
        // AgentStepEventEmitter#runStarted for the one consequence of the
        // early commit this introduces on step-event turns.
        //
        // The guardrail hook is on doOnError deliberately rather than inside
        // GuardedToolCallback: a GuardrailViolationException is the ONE tool
        // failure the wrap chain lets propagate as a stream error (see
        // streamFast's onErrorMap), and this is the single point that sees it
        // for every reasoning depth. It fires BEFORE the controller's
        // onErrorResume turns the error into a STREAM_ERROR_MARKER chunk, so a
        // client sees the typed event first, then the human message.
        if (stepEvents != null && stepEvents.isEnabled()) {
            Instant runStartedAt = Instant.now();
            result =
                    result.doOnSubscribe(
                                    subscription -> {
                                        stepEvents.runStarted(sessionId, agentId);
                                        // The model call starts now; a local model may spend
                                        // tens of seconds loading weights and evaluating the
                                        // prompt before its first token.
                                        stepEvents.phase("model");
                                    })
                            .doOnComplete(
                                    () ->
                                            stepEvents.runFinished(
                                                    Duration.between(runStartedAt, Instant.now())
                                                            .toMillis()))
                            .doOnError(
                                    e -> {
                                        GuardrailViolationException violation =
                                                findGuardrailViolation(e);
                                        if (violation != null) {
                                            stepEvents.guardrail(
                                                    violation.getToolName(),
                                                    guardrailReason(violation));
                                        }
                                    });
        }

        // Close the RL episode once the turn's stream settles (complete / error
        // / client-cancel). doFinally fires on the completion thread, which for
        // the FAST streaming path is not this request thread, so the turn's
        // tenant (the explicit context) is re-applied inside - identical
        // ThreadLocal handoff to LlmMetricsAdvisor. Best-effort: never affects the turn.
        if (rlEpisodeId != null) {
            // Accumulate the streamed answer so the closed episode carries the
            // full assistant completion as a trajectory step - the (prompt,
            // completion) text the preference-export / offline-DPO pipeline
            // needs. Captured here in the OUTER stream, so it works for both the
            // token-streaming FAST path and the buffered CAREFUL/RIGOROUS/PLANNED
            // paths without threading an episode id through any depth method.
            StringBuilder answer = new StringBuilder();
            result =
                    result.doOnNext(answer::append)
                            .doFinally(
                                    signal ->
                                            rlEpisodes.completeRlEpisode(
                                                    rlEpisodeId,
                                                    answer.toString(),
                                                    context.tenantContext()));
        }

        // Cache writes: best-effort, doOnComplete only (a partial/failed answer never poisons
        // a cache). Semantic writes only for tool-less turns and re-apply the captured
        // TenantContext (completion thread, schema-per-tenant), like the RL episode close.
        // Server-side turn cancel - the OUTERMOST operator on either return
        // path, deliberately. takeUntilOther shows downstream a normal
        // onComplete (the client's stream just ends) while CANCELLING
        // everything upstream - so withAssistantTurnPersistence's doOnCancel
        // still persists the partial reply with INTERRUPTED_REPLY_MARKER, and
        // the cache doOnComplete below never sees a complete signal for a
        // truncated answer (a partial reply must never poison either cache).
        result =
                ttft.record(
                        result,
                        bufferedDepth ? ChatTtftRecorder.Mode.BUFFERED : ChatTtftRecorder.Mode.LLM,
                        ttftStartNanos);
        // memorySessionId, not the raw client id: the failed reply must land in the same list the
        // user turn went to (and the transcript endpoint reads), not in an unscoped one.
        result = svc.withFailedReplyPersistence(result, agentId, memorySessionId);
        // Skill-learning loop (app.skills.learning.enabled, off by default): reports how the turn
        // ended. Returns the same Flux, untouched, while the loop is off.
        result =
                com.agenticai.core.agents.skills.SkillLearningGateway.observe(
                        result,
                        agentId,
                        sessionId,
                        userMessage,
                        fastDispatchStartedAt,
                        context.tenantContext());
        // Lessons loop (app.learning.lessons.enabled, off by default): same contract, same no-op.
        result =
                com.agenticai.core.agents.skills.LessonLearningGateway.observe(
                        result,
                        agentId,
                        sessionId,
                        userMessage,
                        fastDispatchStartedAt,
                        context.tenantContext());
        // Preferences-from-behavior loop (app.learning.preferences.enabled, off by default): hands over the
        // person's message only; the reply stream is not touched and nothing blocks.
        com.agenticai.core.agents.skills.PreferenceSignalGateway.observe(
                agentId, sessionId, userMessage, context.tenantContext());
        boolean semanticPutEligible = semanticEligible && tools.isEmpty();
        Flux<Long> cancelled =
                chatTurnCancelService.watchForCancellation(requestedAgentId, memorySessionId);
        if (!cacheEligible && !semanticPutEligible) {
            return result.takeUntilOther(cancelled);
        }
        StringBuilder fullReply = new StringBuilder();
        return result.doOnNext(fullReply::append)
                .doOnComplete(
                        () -> {
                            String full = fullReply.toString();
                            if (cacheEligible) {
                                responseCacheService.put(
                                        currentTenantId, agentId, subjectKey, userMessage, full);
                            }
                            if (semanticPutEligible) {
                                context.scope()
                                        .run(
                                                () ->
                                                        semanticCache.put(
                                                                agentId,
                                                                subjectKey,
                                                                userMessage,
                                                                full));
                            }
                        })
                .takeUntilOther(cancelled);
    }

    /**
     * Finds a {@link GuardrailViolationException} anywhere in {@code error}'s cause chain, for the
     * step-event {@code guardrail} hook in {@link AgentChatService#chat}. The buffered depths run
     * their {@code .call()} inside {@code Flux.defer} and provider-fallback wrappers, so the
     * exception can arrive wrapped (a {@code CompletionException}, an {@code
     * IllegalStateException}) rather than raw - same reason {@code
     * AgentChatStreamResponder#midStreamFailureMessage} walks the chain, and the same bounded walk: a cause
     * chain can be cyclic and this runs on a live stream's error path.
     */
    private static GuardrailViolationException findGuardrailViolation(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof GuardrailViolationException violation) {
                return violation;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return null;
    }

    /**
     * The human reason behind a {@link GuardrailViolationException}, without the "Guardrail blocked
     * output from tool 'x': " preamble its constructor prepends - the {@code guardrail} step event
     * already carries the tool name as its own field, so repeating it inside the reason is noise
     * for a trace UI. Falls back to the full message if the prefix ever changes.
     */
    private static String guardrailReason(GuardrailViolationException violation) {
        String message = violation.getMessage();
        if (message == null) {
            return "";
        }
        String prefix = "Guardrail blocked output from tool '" + violation.getToolName() + "': ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }
}
