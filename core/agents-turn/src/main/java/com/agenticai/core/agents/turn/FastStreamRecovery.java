package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.AgentChatService.INTERRUPTED_REPLY_MARKER;
import static com.agenticai.core.agents.turn.ChatClientResolver.providerOptions;
import static com.agenticai.core.agents.turn.ChatPromptDirectives.isKnownThoughtSignatureBug;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.security.logging.LogSafe;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * The FAST stream's recovery ladder, moved out of {@link ChatTurnExecutor#streamFast} (extract-only
 * split). {@link FastStreamTurn#stream} chains the three branches as {@code onErrorResume}
 * operators in this order: the same-provider tools-disabled retry for the known Gemini
 * thought_signature bug, the one-shot fallback-provider retry, and the graceful close of a stream
 * that fails after text already reached the client. The first two only ever fire before any token
 * has streamed.
 */
final class FastStreamRecovery {

    private final FastStreamTurn t;

    FastStreamRecovery(FastStreamTurn turn) {
        this.t = turn;
    }

    /**
     * Known, OPEN upstream Spring AI bug (spring-projects/spring-ai#5466, confirmed live 2026-08-04
     * via a real Gemini tool-calling turn and traced to bytecode:
     * GoogleGenAiChatModel#responseCandidateToGeneration uses an allMatch check that
     * drops/mis-builds a functionCall part whenever Gemini also returns a "thinking" part alongside
     * it, so the resulting AssistantMessage.ToolCall never carries the thought_signature newer
     * Gemini models require on the NEXT turn - Gemini then 400s with "Function call is missing a
     * thought_signature". Not fixable from our code (Spring AI drops the field before
     * AgentChatService ever sees the message; AssistantMessage.ToolCall has no field for it
     * regardless) and not yet merged upstream, so this is a workaround, not a fix: retry the SAME
     * turn, SAME provider/model, with tools disabled - the bug only triggers when a functionCall
     * part is present, so removing tools from this one retry sidesteps it entirely. Same "only
     * before any token has streamed" safety gate as the fallback-provider branch below, and tried
     * FIRST (this branch is earlier in the chain) since it's a same-provider, no-credential-lookup,
     * cheaper retry than switching providers - see that branch's own comment for why the token gate
     * is required either way.
     */
    Flux<String> retryWithoutToolsOnThoughtSignatureBug(Throwable e) {
        Agent agent = t.agent;
        if (!(e instanceof UpstreamProviderException upstreamE)
                || !t.accumulated.isEmpty()
                || t.tools.isEmpty()
                || !isKnownThoughtSignatureBug(upstreamE)) {
            return Flux.error(e);
        }
        ChatTurnExecutor.log.warn(
                "Provider {} failed for agent {} session {} with the"
                        + " known Spring AI Gemini thought_signature bug"
                        + " (spring-projects/spring-ai#5466) - retrying"
                        + " once on the same provider/model with tools"
                        + " disabled for this turn",
                LogSafe.clean(agent.getProviderName()),
                LogSafe.clean(t.agentId),
                LogSafe.clean(t.sessionId));
        // Not presented as a normal turn any more (2026-09-28): the answer that follows was
        // produced with NONE of the agent's tools, so a step-event consumer is told so, and the
        // degradation is counted.
        t.owner.flagToolsDisabledRetry(agent, t.stepEvents);
        t.turn.modelCall(
                String.valueOf(agent.getProviderName()), agent.getModel(), "retry-tools-disabled");
        // Deliberately NO toolCallbacks - see the comment above.
        return t.content(t.chatClient, false)
                // Same genuine mid-stream idle timeout as the initial attempt - this is a
                // replacement Flux, not a continuation, so it needs its own operator instance.
                .timeout(t.owner.streamIdleTimeout)
                .onErrorMap(
                        retryError ->
                                t.owner.providerErrorTranslator.translate(
                                        retryError, agent.getProviderName()));
    }

    /**
     * #815 Gap 4 - graceful degradation. Only ever fires when NO tokens have streamed yet
     * (accumulated.isEmpty()): once even one token has reached the client, the HTTP/SSE response is
     * already committed to this provider's output - switching providers mid-stream would interleave
     * two different models' text, not fix anything. That means this only helps the common failure
     * shape (auth/billing/rate-limit/model-not-found errors, which reject before any output), not a
     * provider that dies mid-answer - an honest limitation, not an oversight (the "inherent limit
     * of HTTP streaming" note in {@link FastStreamTurn#stream}, same root cause).
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
                    LogSafe.clean(agent.getProviderName()),
                    LogSafe.clean(t.agentId),
                    LogSafe.clean(t.sessionId),
                    LogSafe.clean(e.getMessage()),
                    LogSafe.clean(agent.getFallbackProviderName()));
            return Flux.error(e);
        }
        ChatTurnExecutor.log.warn(
                "Provider {} failed for agent {} session {} ({}),"
                        + " retrying once on fallback provider {}",
                LogSafe.clean(agent.getProviderName()),
                LogSafe.clean(t.agentId),
                LogSafe.clean(t.sessionId),
                LogSafe.clean(e.getMessage()),
                LogSafe.clean(agent.getFallbackProviderName()));
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
            fallbackClient = t.context.scope().get(() -> resolveFallbackClient(agent));
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
        // Flat one-shot retry - no recursive fallback-of-fallback, no second onErrorResume
        // chained here, so this can never loop.
        return t.content(fallbackClient, true)
                // Same genuine mid-stream idle timeout as the initial attempt - this is the
                // fallback-provider replacement Flux.
                .timeout(t.owner.streamIdleTimeout)
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
     * "Half response" fix (2026-08-10): a tool call that errors or times out AFTER some text has
     * already reached the client falls through BOTH retry branches above unretried - each is
     * deliberately gated on accumulated.isEmpty() (switching provider/model mid-stream, or
     * replaying tools-disabled, would interleave a second independent answer after tokens already
     * committed to the client, not extend the first one cleanly) - so this is the one realistic
     * failure shape neither branch was ever meant to cover. Before this operator, that left exactly
     * one outcome: Flux.error(e) propagates raw and the SSE stream just stops mid-sentence - the
     * "half response"/dead-stream bug users reported. A clean retry isn't safe here for the same
     * reason the branches above refuse one (both rebuild the ENTIRE prompt from scratch via a
     * brand-new provider call; there is no "resume from where we left off" primitive on this path),
     * so instead of retrying, close the stream the way a human would: say the lookup failed and
     * complete normally rather than dying silently. Reuses INTERRUPTED_REPLY_MARKER - the exact
     * same honest-truncation wording {@code persistInterruptedAssistantTurn} already writes to
     * session memory on a hard error/cancel - rather than inventing new user-facing copy for what
     * is, from the user's perspective, the same "your reply got cut off" situation. Completing
     * normally (Flux.just + implicit onComplete) instead of erroring also means
     * withAssistantTurnPersistence's ordinary doOnComplete path persists the marked text itself;
     * doOnError's own persistInterruptedAssistantTurn is untouched for every failure that still
     * reaches it (anything before a single token streams, or a genuine cancel) - ADR-007's
     * persistence contract is unchanged, this only changes what the CLIENT sees once text was
     * already in flight.
     *
     * <p>Deliberately not narrowed to UpstreamProviderException / a tool-specific exception type:
     * by this point in the chain the upstream onErrorMap has already made the one routing decision
     * that matters (GuardrailViolationException/ToolStepLimitExceededException pass through
     * untranslated, everything else becomes an UpstreamProviderException), and once accumulated is
     * non-empty every remaining error - regardless of which subsystem raised it - produces the
     * identical "half response" symptom for the user, so the safe-completion behavior below applies
     * uniformly.
     */
    Flux<String> closeHalfResponse(Throwable e) {
        if (t.accumulated.isEmpty()) {
            return Flux.error(e);
        }
        ChatTurnExecutor.log.warn(
                "Agent {} session {} failed after partial text had"
                        + " already streamed to the client ({}) - closing"
                        + " the stream gracefully instead of propagating"
                        + " the raw error",
                LogSafe.clean(t.agentId),
                LogSafe.clean(t.sessionId),
                LogSafe.clean(e.getMessage()),
                e);
        return Flux.just(INTERRUPTED_REPLY_MARKER);
    }
}
