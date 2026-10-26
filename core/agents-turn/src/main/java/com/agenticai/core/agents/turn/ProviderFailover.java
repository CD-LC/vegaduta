package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatClientResolver.providerOptions;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The BYOK provider-fallback wrapper and the replay gate, split out of {@link ChatTurnExecutor}
 * when the file ran past its size baseline (the bodies moved verbatim). Holds the executor so the
 * volatile {@code replayGate}/{@code resilience}/{@code meterRegistry} it swaps in tests are read
 * live, exactly as before.
 */
final class ProviderFailover {

    private final ChatTurnExecutor turns;

    ProviderFailover(ChatTurnExecutor turns) {
        this.turns = turns;
    }

    /**
     * @param promptExecutor builds and runs one full prompt against whichever {@link ChatClient}
     *     it's given - called once against {@code primaryClient}, and again against a
     *     freshly-resolved fallback client only if the first attempt throws.
     */
    String callWithProviderFallback(
            String tenantId,
            Agent agent,
            ChatClient primaryClient,
            Function<ChatClient, String> promptExecutor) {
        return callWithProviderFallback(tenantId, agent, primaryClient, promptExecutor, null, null);
    }

    /**
     * As above, for a turn whose tool activity is known: a failure that comes after the turn ran
     * tools with side effects is not retried on the fallback provider (it would run the turn, tools
     * included, a second time) unless the effect journal recorded each of them - see {@link
     * FailoverReplayGate}. The original failure is rethrown, with the tools that ran named in the
     * log, the metric and, when the turn has step events, a note.
     *
     * @param activity the turn's tool activity; {@code null} skips the check (a delegated call has
     *     none)
     */
    String callWithProviderFallback(
            String tenantId,
            Agent agent,
            ChatClient primaryClient,
            Function<ChatClient, String> promptExecutor,
            TurnToolActivity activity,
            AgentStepEventEmitter stepEvents) {
        try {
            return promptExecutor.apply(primaryClient);
        } catch (RuntimeException e) {
            if (e instanceof ToolStepLimitExceededException
                    || e instanceof GuardrailViolationException
                    || e instanceof LlmGatewayContentPolicyViolationException
                    || agent.getFallbackProviderName() == null) {
                throw e;
            }
            // Classify BEFORE deciding to fall back. translate() is idempotent
            // (an UpstreamProviderException passes through unchanged), so this
            // never double-wraps, and the caller's own onErrorMap downstream
            // treats the classified exception the same way.
            UpstreamProviderException classified =
                    e instanceof UpstreamProviderException upstream
                            ? upstream
                            : turns.providerErrorTranslator.translate(e, agent.getProviderName());
            // classified is contractually never null (translate wraps every
            // Throwable) - the null-check only shields mocked translators from
            // an NPE, degrading to the pre-gate "always fall back" behavior.
            if (classified != null && !HostedFailoverPolicy.isRetryable(classified)) {
                ChatTurnExecutor.log.warn(
                        "Provider {} failed for agent {} with a non-retryable (input-shaped) error"
                                + " ({}) - not retrying on fallback provider {}, it would reject this"
                                + " the same way",
                        agent.getProviderName(),
                        agent.getId(),
                        classified.getMessage(),
                        agent.getFallbackProviderName());
                throw classified;
            }
            if (replayBlockedBy(activity, agent, "byok-fallback", stepEvents).isPresent()) {
                throw classified != null ? classified : e;
            }
            try {
                String fallbackApiKey =
                        turns.credentialResolver.resolveActiveKey(agent.getFallbackProviderName());
                // RL GEN_PARAMS scope boundary - see the identical note at
                // streamFast's inline fallback above; same reasoning applies to
                // this shared callWithProviderFallback helper.
                ChatClient fallbackClient =
                        turns.llmGatewayService.resolveClient(
                                tenantId,
                                new GatewayCallSpec(
                                        agent.getFallbackProviderName(),
                                        fallbackApiKey,
                                        agent.getFallbackModel(),
                                        agent.getMaxTokens(),
                                        agent.getTemperature(),
                                        agent.getTopP(),
                                        null,
                                        providerOptions(agent.getFallbackProviderName(), agent)));
                ChatTurnExecutor.log.warn(
                        "Provider {} failed for agent {} ({}), retrying once on fallback provider"
                                + " {}",
                        agent.getProviderName(),
                        agent.getId(),
                        e.getMessage(),
                        agent.getFallbackProviderName());
                turns.issues.recordFallbackTriggeredIssue(agent.getId(), agent, e);
                TurnLifecycleEvents.failover(stepEvents, agent.getFallbackModel(), e);
                return promptExecutor.apply(fallbackClient);
            } catch (RuntimeException fallbackFailure) {
                ChatTurnExecutor.log.warn(
                        "Fallback provider {} could not be resolved/used for agent {}",
                        agent.getFallbackProviderName(),
                        agent.getId(),
                        fallbackFailure);
                // Covers BOTH failure shapes uniformly (resolveActiveKey/
                // resolveClient throwing above, or promptExecutor.apply
                // itself throwing) - this catch can't tell which happened,
                // and doesn't need to: either way the fallback didn't
                // rescue the turn. If resolution never even succeeded,
                // recordFallbackTriggeredIssue above never ran, so only this
                // one issue is recorded, not both.
                turns.issues.recordFallbackAlsoFailedIssue(agent.getId(), agent, e, fallbackFailure);
                throw e;
            }
        }
    }

    /**
     * Whether failing {@code agent}'s turn over to another provider is blocked because the turn
     * already ran tools: empty when it may be replayed, else the tools that stand in the way. A
     * block is counted, logged and, on a step-event turn, told to the consumer; see {@link
     * FailoverReplayGate}.
     *
     * @param path which failover this is (for the metric and the log)
     */
    java.util.Optional<String> replayBlockedBy(
            TurnToolActivity activity, Agent agent, String path, AgentStepEventEmitter stepEvents) {
        FailoverReplayGate.Verdict verdict = turns.replayGate.check(activity);
        if (verdict.allowed()) {
            return java.util.Optional.empty();
        }
        DegradedRetrySignal.flagReplayBlocked(
                turns.meterRegistry(), agent, path, verdict.toolsThatRan(), turns.resilience, stepEvents);
        return java.util.Optional.of(verdict.toolsThatRan());
    }
}
