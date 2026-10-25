package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.AgentChatService.INTERRUPTED_REPLY_MARKER;
import static com.agenticai.core.agents.turn.ChatClientResolver.providerOptions;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.isKnownThoughtSignatureBug;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * The FAST stream's recovery ladder, moved out of {@link ChatTurnExecutor#streamFast}
 * (extract-only split). {@link FastStreamTurn#stream} chains the three branches as {@code
 * onErrorResume} operators in this order: the same-provider tools-disabled retry for the known
 * Gemini thought_signature refusal (a visible reply written without tools), the one-shot fallback-provider retry, and the graceful close of a
 * stream that fails after text already reached the client. The first two only ever fire before
 * any token has streamed.
 */
final class FastStreamRecovery {

    private final FastStreamTurn t;

    FastStreamRecovery(FastStreamTurn turn) {
        this.t = turn;
    }

    /**
     * Gemini refuses the request that follows a tool call ("Function call is missing a
     * thought_signature") when the model's own function call is sent back without the signature it put
     * on it. Spring AI stores those signatures with the reply and puts them back; {@code
     * GeminiMixedContentToolCallChatModel} keeps the function-call parts' signatures lined up with their
     * calls, which is what makes the turn work. This branch is for the provider refusing anyway.
     *
     * <p>It used to re-run the turn with every tool removed and say nothing. Now, only before any token
     * has streamed (the same gate the fallback-provider branch uses, for the same reason), and unless
     * {@code app.chat.resilience.thought-signature-degrade} is off: the reply opens with a notice that
     * it was written without tools and which steps had already run, the model is given what those tools
     * returned (it used to be shown nothing), and the reply is marked as not an answer so it is not
     * cached or judged. The tools are not run again: this pass offers none.
     */
    Flux<String> retryWithoutToolsOnThoughtSignatureBug(Throwable e) {
        Agent agent = t.agent;
        if (!(e instanceof UpstreamProviderException upstreamE)
                || !t.accumulated.isEmpty()
                || t.tools.isEmpty()
                || !t.owner.resilience.isThoughtSignatureDegrade()
                || !isKnownThoughtSignatureBug(upstreamE)) {
            return Flux.error(e);
        }
        ChatTurnExecutor.log.warn(
                "Provider {} refused the tool-call history of agent {} session {} (missing thought_signature) -"
                        + " answering once on the same provider/model WITHOUT tools, and saying so in the reply",
                agent.getProviderName(),
                t.agentId,
                t.sessionId);
        // Counted, and announced on the step-event channel as well as in the reply itself.
        t.owner.flagToolsDisabledRetry(agent, t.stepEvents, t.context, t.agentId);
        t.turn.modelCall(
                String.valueOf(agent.getProviderName()), agent.getModel(), "retry-tools-disabled");
        ChatResilienceProperties resilience = t.owner.resilience;
        String notice = ThoughtSignatureDegrade.notice(resilience, t.activity().snapshot());
        String userText = ThoughtSignatureDegrade.userText(resilience, t.userMessage, toolEvidence());
        t.reply().mark(TurnReplyOutcome.Kind.INTERRUPTED);
        // Deliberately NO toolCallbacks - see the comment above.
        return Flux.just(notice + "\n\n")
                .concatWith(
                        t.contentWithoutTools(userText)
                                // Same genuine mid-stream idle timeout as the initial attempt - this is a
                                // replacement Flux, not a continuation, so it needs its own operator instance.
                                .transform(t::idleTimeout)
                                .onErrorMap(
                                        retryError ->
                                                t.owner.providerErrorTranslator.translate(
                                                        retryError, agent.getProviderName())));
    }

    /** What this turn's tools have returned so far, or {@code null} when none ran or it cannot be read. */
    private String toolEvidence() {
        try {
            return t.context
                    .scope()
                    .get(
                            () ->
                                    t.owner.collectToolEvidenceSince(
                                            t.agentId, t.sessionId, t.fabricationCheckStartedAt));
        } catch (RuntimeException readFailure) {
            ChatTurnExecutor.log.warn(
                    "Agent {} session {}: could not read the tool results for the reply written without tools",
                    t.agentId,
                    t.sessionId,
                    readFailure);
            return null;
        }
    }

    /**
     * #815 Gap 4 - graceful degradation. Only ever fires when NO tokens have streamed yet
     * (accumulated.isEmpty()): once even one token has reached the client, the HTTP/SSE response
     * is already committed to this provider's output - switching providers mid-stream would
     * interleave two different models' text, not fix anything. That means this only helps the
     * common failure shape (auth/billing/rate-limit/model-not-found errors, which reject before
     * any output), not a provider that dies mid-answer - an honest limitation, not an oversight
     * (the "inherent limit of HTTP streaming" note in {@link FastStreamTurn#stream}, same root
     * cause).
     */
    Flux<String> retryOnFallbackProvider(Throwable e) {
        Agent agent = t.agent;
        if (!(e instanceof UpstreamProviderException)
                || !t.accumulated.isEmpty()
                || agent.getFallbackProviderName() == null) {
            return Flux.error(e);
        }
        // Same retryability decision the hosted pool applies (see HostedFailoverPolicy's
        // javadoc): a content-policy refusal or an over-length prompt is a property of the USER'S
        // INPUT - the fallback provider would refuse it identically, so replaying it only adds
        // latency and spend before the same actionable message (already classified by
        // ProviderErrorTranslator) reaches the user. Provider-shaped failures - auth, billing,
        // rate limit, and crucially the invalid-model 400/404 - still fall back.
        if (!HostedFailoverPolicy.isRetryable(e)) {
            ChatTurnExecutor.log.warn(
                    "Provider {} failed for agent {} session {} with a"
                            + " non-retryable (input-shaped) error ({}) -"
                            + " not retrying on fallback provider {}, it"
                            + " would reject this the same way",
                    agent.getProviderName(),
                    t.agentId,
                    t.sessionId,
                    e.getMessage(),
                    agent.getFallbackProviderName());
            return Flux.error(e);
        }
        // The fallback runs the turn again from the start, tools included. "No text has streamed" does
        // not mean nothing happened: a tool-calling turn streams no text while it works. A turn that
        // already ran a tool with side effects is not replayed (see FailoverReplayGate).
        if (t.owner.replayBlockedBy(t.activity(), agent, "byok-fallback", t.stepEvents).isPresent()) {
            return Flux.error(e);
        }
        ChatTurnExecutor.log.warn(
                "Provider {} failed for agent {} session {} ({}),"
                        + " retrying once on fallback provider {}",
                agent.getProviderName(),
                t.agentId,
                t.sessionId,
                e.getMessage(),
                agent.getFallbackProviderName());
        ChatClient fallbackClient;
        // This onErrorResume callback runs on a Reactor scheduler thread, not the servlet request
        // thread that set TenantContext - the same ThreadLocal-doesn't-survive-a-reactive-hop gap
        // TenantContextPropagatingToolCallback's javadoc documents for tool calls. Without
        // re-applying the turn's tenant here (ADR-0022: the explicit TurnContext's scope),
        // credentialResolver.resolveActiveKey below runs with an empty TenantContext,
        // TenantSchemaResolver silently falls back to the public schema, the fallback provider's
        // BYOK credential (which lives in the tenant's own schema) is never found, and a
        // perfectly-configured, ACTIVE fallback provider throws ProviderNotActiveException -
        // masking the real failure with a second, spurious one and defeating the entire failover
        // path.
        try {
            fallbackClient =
                    t.context.scope().get(() -> resolveFallbackClient(agent));
        } catch (RuntimeException fallbackSetupFailure) {
            // The fallback itself isn't configured correctly (e.g. its provider was never
            // activated) - surface the ORIGINAL error, not this one, since that's what actually
            // explains why the turn failed from the user's perspective.
            ChatTurnExecutor.log.warn(
                    "Fallback provider {} could not be resolved for" + " agent {}",
                    agent.getFallbackProviderName(),
                    t.agentId,
                    fallbackSetupFailure);
            t.owner.issues.recordFallbackAlsoFailedIssue(
                    t.context.tenantContext(), t.agentId, agent, e, fallbackSetupFailure);
            return Flux.error(e);
        }
        t.owner.issues.recordFallbackTriggeredIssue(t.context.tenantContext(), t.agentId, agent, e);
        t.turn.modelCall(
                String.valueOf(agent.getFallbackProviderName()),
                agent.getFallbackModel(),
                "fallback");
        TurnLifecycleEvents.failover(t.stepEvents, agent.getFallbackModel(), e);
        // Flat one-shot retry - no recursive fallback-of-fallback, no second onErrorResume
        // chained here, so this can never loop.
        return t.content(fallbackClient, true)
                // Same genuine mid-stream idle timeout as the initial attempt - this is the
                // fallback-provider replacement Flux.
                .transform(t::idleTimeout)
                // doOnError runs BEFORE onErrorMap below, so it still sees the raw fallbackError
                // rather than the translated UpstreamProviderException.
                .doOnError(
                        fallbackError ->
                                t.owner.issues.recordFallbackAlsoFailedIssue(
                                        t.context.tenantContext(),
                                        t.agentId,
                                        agent,
                                        e,
                                        fallbackError))
                .onErrorMap(
                        fallbackError ->
                                t.owner.providerErrorTranslator.translate(
                                        fallbackError, agent.getFallbackProviderName()));
    }

    /**
     * RL GEN_PARAMS scope boundary: deliberately agent.getTemperature()/getTopP(), not
     * effTemperature/effTopP - the fallback provider is a different provider/model entirely, only
     * reached after the PRIMARY already failed. A disaster-recovery path is not where a bandit
     * arm's exploration should apply; same "BYOK-only, no RL" kind of documented scope boundary
     * #delegateCall already has.
     */
    private ChatClient resolveFallbackClient(Agent agent) {
        String fallbackApiKey =
                t.owner.credentialResolver.resolveActiveKey(agent.getFallbackProviderName());
        return t.owner.llmGatewayService.resolveClient(
                t.context.tenantId(),
                new GatewayCallSpec(
                        agent.getFallbackProviderName(),
                        fallbackApiKey,
                        agent.getFallbackModel(),
                        agent.getMaxTokens(),
                        agent.getTemperature(),
                        agent.getTopP(),
                        null,
                        providerOptions(agent.getFallbackProviderName(), agent)));
    }

    /**
     * True for a failure that is a deliberate STOP decided by the platform, not a transient fault:
     * {@link ToolStepLimitExceededException}, {@link GuardrailViolationException} and {@link
     * LlmGatewayContentPolicyViolationException}, directly or as the cause of a wrapper. These pass
     * through provider-error translation untouched and must never be recovered into a normal
     * completion.
     */
    static boolean isPolicyStop(Throwable error) {
        int depth = 0;
        for (Throwable e = error; e != null && depth < 10; e = e.getCause(), depth++) {
            if (e instanceof ToolStepLimitExceededException
                    || e instanceof GuardrailViolationException
                    || e instanceof LlmGatewayContentPolicyViolationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Half response" fix (2026-08-10): a tool call that errors or times out AFTER some text has
     * already reached the client falls through BOTH retry branches above unretried - each is
     * deliberately gated on accumulated.isEmpty() (switching provider/model mid-stream, or
     * replaying tools-disabled, would interleave a second independent answer after tokens already
     * committed to the client, not extend the first one cleanly) - so this is the one realistic
     * failure shape neither branch was ever meant to cover. Before this operator, that left
     * exactly one outcome: Flux.error(e) propagates raw and the SSE stream just stops
     * mid-sentence - the "half response"/dead-stream bug users reported. A clean retry isn't safe
     * here for the same reason the branches above refuse one (both rebuild the ENTIRE prompt from
     * scratch via a brand-new provider call; there is no "resume from where we left off"
     * primitive on this path), so instead of retrying, close the stream the way a human would:
     * say the lookup failed and complete normally rather than dying silently. Reuses
     * INTERRUPTED_REPLY_MARKER - the exact same honest-truncation wording {@code
     * persistInterruptedAssistantTurn} already writes to session memory on a hard error/cancel -
     * rather than inventing new user-facing copy for what is, from the user's perspective, the
     * same "your reply got cut off" situation. Completing normally (Flux.just + implicit
     * onComplete) instead of erroring also means withAssistantTurnPersistence's ordinary
     * doOnComplete path persists the marked text itself; doOnError's own
     * persistInterruptedAssistantTurn is untouched for every failure that still reaches it
     * (anything before a single token streams, or a genuine cancel) - ADR-007's persistence
     * contract is unchanged, this only changes what the CLIENT sees once text was already in
     * flight.
     *
     * <p>Deliberately not narrowed to UpstreamProviderException / a tool-specific exception type:
     * once accumulated is non-empty every remaining FAULT - regardless of which subsystem raised
     * it - produces the identical "half response" symptom for the user, so the safe-completion
     * behavior below applies uniformly. The exception is a policy stop ({@link #isPolicyStop}),
     * which is propagated.
     */
    Flux<String> closeHalfResponse(Throwable e) {
        // Under terminal-only streaming (app.chat.parity.fast-terminal-rounds-only) accumulated only
        // fills at the terminal round's end, so a mid-loop failure arrives here "empty" too - the
        // error path stays identical: nothing the person saw was delivered, and the exception
        // mapper reports the failure rather than a graceful close pretending mid-work was output.
        if (t.accumulated.isEmpty()) {
            return Flux.error(e);
        }
        // A policy stop is not the "half response" accident this operator exists for: the tool-step
        // ceiling (a deliberate runaway-loop hard stop), a guardrail violation or a gateway content
        // policy violation says the turn was STOPPED, and completing normally with a truncation
        // marker would present a stopped task as a finished one. Let it reach the caller.
        if (isPolicyStop(e)) {
            ChatTurnExecutor.log.warn(
                    "Agent {} session {} was stopped by policy after partial text had streamed ({}) - propagating it,"
                            + " not closing the stream as a normal completion",
                    t.agentId,
                    t.sessionId,
                    e.getClass().getSimpleName());
            return Flux.error(e);
        }
        ChatTurnExecutor.log.warn(
                "Agent {} session {} failed after partial text had"
                        + " already streamed to the client ({}) - closing"
                        + " the stream gracefully instead of propagating"
                        + " the raw error",
                t.agentId,
                t.sessionId,
                e.getMessage(),
                e);
        // The stream now completes normally, but what was sent is not an answer: record that, so the
        // dispatcher does not cache the cut-off text and the turn does not announce or judge it.
        t.context.reply().mark(TurnReplyOutcome.Kind.INTERRUPTED);
        return Flux.just(INTERRUPTED_REPLY_MARKER);
    }
}
