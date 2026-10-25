package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.runtime.AgentRuntime;
import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.agents.tools.SmallModelProperties;
import com.agenticai.core.agents.tools.ToolReplaySafety;
import com.agenticai.core.chat.LlmRetryPolicy;
import com.agenticai.core.chat.LlmRetryProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The turn-level settings {@link AgentChatService} is configured with: the three durations, the
 * continuation limit, and the typed policies a turn consults when it runs long or a provider fails
 * ({@code app.chat.finish.*}, {@code app.chat.resilience.*}, {@code app.llm.retry.*}).
 */
@Component
final class ChatTimeouts {

    private final Duration streamIdleTimeout;
    private final Duration bufferedTurnTimeout;

    /**
     * The FAST stream's overall wall-clock ceiling ({@code app.llm.http.fast-turn-timeout}) - see the
     * matching field on {@link ChatTurnExecutor}. The shipped default stands in for the test-seam
     * constructors, which take only the older three durations.
     */
    private Duration fastTurnTimeout = Duration.ofMinutes(10);

    private final Duration bufferedKeepAliveInterval;
    private final int maxContinuations;
    private final ChatFinishProperties finish;
    private final ChatResilienceProperties resilience;
    private final LlmRetryPolicy retryPolicy;
    private final FailoverReplayGate replayGate;
    private TurnContinuation continuation = new TurnContinuation(new ContinuationProperties());
    private FollowUpProperties followUp = new FollowUpProperties();
    private ContinuationProperties continuationProperties = new ContinuationProperties();
    private ToolLedgerProperties toolLedger = new ToolLedgerProperties();
    private ChatParityProperties parity = new ChatParityProperties();
    private ChatCacheProperties cache = new ChatCacheProperties();
    private SmallModelProperties smallModel = new SmallModelProperties();
    private LocalModelTurnProperties localModel = new LocalModelTurnProperties();
    private ChatLimitsProperties limits = new ChatLimitsProperties();
    private AgentRuntime runtime;
    private BehaviourSettings behaviour = BehaviourSettings.platformOnly();

    /** Test seam: continuation off. */
    ChatTimeouts(
            Duration streamIdleTimeout,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval) {
        this(streamIdleTimeout, bufferedTurnTimeout, bufferedKeepAliveInterval, 0);
    }

    /** Test seam: the shipped {@code app.chat.finish.*} defaults. */
    ChatTimeouts(
            Duration streamIdleTimeout,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval,
            int maxContinuations) {
        this(
                streamIdleTimeout,
                bufferedTurnTimeout,
                bufferedKeepAliveInterval,
                maxContinuations,
                new ChatFinishProperties());
    }

    /** Test seam: an explicit finish policy, the shipped defaults for everything else. */
    ChatTimeouts(
            Duration streamIdleTimeout,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval,
            int maxContinuations,
            ChatFinishProperties finish) {
        this(
                streamIdleTimeout,
                bufferedTurnTimeout,
                bufferedKeepAliveInterval,
                maxContinuations,
                finish,
                new ChatResilienceProperties(),
                LlmRetryPolicy.defaults(),
                null);
    }

    /** Test seam: the shipped defaults for everything but the finish policy. */
    ChatTimeouts(
            Duration streamIdleTimeout,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval,
            int maxContinuations,
            ObjectProvider<ChatFinishProperties> finish) {
        this(
                streamIdleTimeout,
                bufferedTurnTimeout,
                bufferedKeepAliveInterval,
                maxContinuations,
                finish.getIfAvailable(ChatFinishProperties::new),
                new ChatResilienceProperties(),
                LlmRetryPolicy.defaults(),
                null);
    }

    @Autowired
    ChatTimeouts(
            @Value("${app.llm.http.stream-idle-timeout:90s}") Duration streamIdleTimeout,
            @Value("${app.llm.http.buffered-turn-timeout:240s}") Duration bufferedTurnTimeout,
            @Value("${app.llm.http.fast-turn-timeout:600s}") Duration fastTurnTimeout,
            @Value("${app.chat.sse.buffered-keepalive-interval:20s}")
                    Duration bufferedKeepAliveInterval,
            @Value("${app.chat.continuation.max-passes:8}") int maxContinuations,
            ObjectProvider<ChatFinishProperties> finish,
            ObjectProvider<ChatResilienceProperties> resilience,
            ObjectProvider<LlmRetryProperties> retry,
            ObjectProvider<ToolReplaySafety> replaySafety,
            ObjectProvider<ContinuationProperties> continuationProperties,
            ObjectProvider<FollowUpProperties> followUp) {
        this(
                streamIdleTimeout,
                bufferedTurnTimeout,
                bufferedKeepAliveInterval,
                maxContinuations,
                finish.getIfAvailable(ChatFinishProperties::new),
                resilience.getIfAvailable(ChatResilienceProperties::new),
                new LlmRetryPolicy(retry.getIfAvailable(LlmRetryProperties::new)),
                // Looked up when a tool is judged, not now: the classification beans may not exist yet.
                name -> {
                    ToolReplaySafety safety = replaySafety.getIfAvailable();
                    return safety == null ? Optional.empty() : safety.replaySafeVerdict(name);
                });
        this.fastTurnTimeout = fastTurnTimeout == null ? Duration.ofMinutes(10) : fastTurnTimeout;
        useConversationPolicies(
                continuationProperties.getIfAvailable(ContinuationProperties::new),
                followUp.getIfAvailable(FollowUpProperties::new));
    }

    /** Test seam: every policy explicit; {@code classification} null = judge tools by name only. */
    ChatTimeouts(
            Duration streamIdleTimeout,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval,
            int maxContinuations,
            ChatFinishProperties finish,
            ChatResilienceProperties resilience,
            LlmRetryPolicy retryPolicy,
            Function<String, Optional<Boolean>> classification) {
        this.streamIdleTimeout = streamIdleTimeout;
        this.bufferedTurnTimeout = bufferedTurnTimeout;
        this.bufferedKeepAliveInterval = bufferedKeepAliveInterval;
        this.maxContinuations = maxContinuations;
        this.finish = finish;
        this.resilience = resilience;
        this.retryPolicy = retryPolicy;
        this.replayGate = new FailoverReplayGate(resilience, classification);
    }

    /**
     * Sets the continuation ({@code app.chat.continuation.*}) and short-follow-up ({@code
     * app.chat.follow-up.*}) policies; until it is called the shipped defaults apply.
     */
    void useConversationPolicies(ContinuationProperties continuation, FollowUpProperties followUp) {
        this.continuationProperties = continuation;
        this.continuation = new TurnContinuation(continuation);
        this.followUp = followUp;
    }

    /**
     * Sets {@code app.chat.tool-ledger.*} (the record of what a turn's tools did, stored with the
     * assistant turn and replayed to the model); until it is called the shipped defaults apply.
     */
    @Autowired(required = false)
    void useToolLedger(ToolLedgerProperties toolLedger) {
        this.toolLedger = toolLedger == null ? new ToolLedgerProperties() : toolLedger;
    }

    /**
     * Sets {@code app.chat.parity.*} (where the streamed and the buffered turn deliberately differ, and the
     * limits on the buffered verifiers); until it is called the shipped defaults apply.
     */
    @Autowired(required = false)
    void useParity(ChatParityProperties parity) {
        this.parity = parity == null ? new ChatParityProperties() : parity;
    }

    /**
     * Sets {@code app.chat.cache.*} (what the response caches may serve and store, and what they key on);
     * until it is called the shipped defaults apply.
     */
    @Autowired(required = false)
    void useCache(ChatCacheProperties cache) {
        this.cache = cache == null ? new ChatCacheProperties() : cache;
    }

    /**
     * Sets {@code app.chat.limits.*} (limits of the turn classes); until it is called the shipped defaults apply.
     */
    @Autowired(required = false)
    void useLimits(ChatLimitsProperties limits) {
        this.limits = limits == null ? new ChatLimitsProperties() : limits;
    }

    /**
     * Sets {@code app.chat.small-model.*} (the tools a small-model turn offers and how they are ranked);
     * until it is called the shipped defaults apply.
     */
    @Autowired(required = false)
    void useSmallModel(SmallModelProperties smallModel) {
        this.smallModel = smallModel == null ? new SmallModelProperties() : smallModel;
    }

    /**
     * Sets {@code app.chat.local-model.*} (how a turn adapts to a model on the person's own machine); until it is
     * called the shipped defaults apply.
     */
    @Autowired(required = false)
    void useLocalModel(LocalModelTurnProperties localModel) {
        this.localModel = localModel == null ? new LocalModelTurnProperties() : localModel;
    }

    /**
     * Sets the resolver of the per-tenant / per-agent runtime limits ({@code app.agent-runtime.*}); until it is
     * called (hand-built fixtures) every turn runs under the platform settings this class was built with.
     */
    @Autowired(required = false)
    void useRuntime(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Sets the settings catalog the turn reads its curated behaviour settings from ({@code chat.finish.*},
     * {@code chat.cache.*}, {@code chat.retry.maxAttempts}, ... see docs/settings.md); until it is called (hand-built
     * fixtures) every turn runs under the platform values. The resolver is looked up when a setting is read, not now.
     */
    @Autowired(required = false)
    void useSettings(ObjectProvider<SettingsResolver> resolver) {
        this.behaviour = BehaviourSettings.lazy(resolver::getIfAvailable);
        if (retryPolicy != LlmRetryPolicy.defaults()) {
            retryPolicy.useSettings(resolver::getIfAvailable);
        }
    }

    /** The turn's view of the settings catalog; the platform values when there is none. */
    BehaviourSettings behaviour() {
        return behaviour;
    }

    /** The runtime-limit resolver, or null when the turn runs under the platform settings alone. */
    AgentRuntime runtime() {
        return runtime;
    }

    /** {@code app.chat.limits.*}: limits of the turn classes. */
    ChatLimitsProperties limits() {
        return limits;
    }

    /** {@code app.chat.small-model.*}: the tool cap and ranking weights of small-model mode. */
    SmallModelProperties smallModel() {
        return smallModel;
    }

    /** {@code app.chat.local-model.*}: how a turn adapts to a model on the person's own machine. */
    LocalModelTurnProperties localModel() {
        return localModel;
    }

    /** {@code app.chat.cache.*}: what the response caches may serve and store, and what they key on. */
    ChatCacheProperties cache() {
        return cache;
    }

    /** {@code app.chat.tool-ledger.*}: the stored, replayed record of what a turn's tools did. */
    ToolLedgerProperties toolLedger() {
        return toolLedger;
    }

    /** {@code app.chat.parity.*}: the explicit stream-versus-buffered differences. */
    ChatParityProperties parity() {
        return parity;
    }

    /** The wording-and-signals decision of whether a stopped reply is continued. */
    TurnContinuation continuation() {
        return continuation;
    }

    /** {@code app.chat.continuation.*}: which models the wording check applies to, and the patterns. */
    ContinuationProperties continuationProperties() {
        return continuationProperties;
    }

    /** {@code app.chat.follow-up.*}: how a short follow-up message is handled. */
    FollowUpProperties followUp() {
        return followUp;
    }

    /** {@code app.chat.resilience.*}: tool-aware idle timeout, replay after tools, timeout notices. */
    ChatResilienceProperties resilience() {
        return resilience;
    }

    /** {@code app.llm.retry.*}: the backoff a streaming turn applies before its first token. */
    LlmRetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /** Whether a turn that ran tools may be replayed on another provider. */
    FailoverReplayGate replayGate() {
        return replayGate;
    }

    /** {@code app.chat.finish.*}: what a turn does with the provider's finish reason. */
    ChatFinishProperties finish() {
        return finish;
    }

    int maxContinuations() {
        return maxContinuations;
    }

    Duration streamIdleTimeout() {
        return streamIdleTimeout;
    }

    Duration bufferedTurnTimeout() {
        return bufferedTurnTimeout;
    }

    /** {@code app.llm.http.fast-turn-timeout}: the FAST stream's overall wall-clock ceiling. */
    Duration fastTurnTimeout() {
        return fastTurnTimeout;
    }

    Duration bufferedKeepAliveInterval() {
        return bufferedKeepAliveInterval;
    }
}
