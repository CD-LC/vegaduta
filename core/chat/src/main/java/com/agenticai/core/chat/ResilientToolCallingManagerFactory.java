package com.agenticai.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place a tool-capable {@code ChatModel} in this package gets its {@link
 * ToolCallingManager} from, so that every provider - not just the one whose incident happened to be
 * diagnosed first - is protected from the two ways a model's own tool call can kill a chat turn
 * mid-stream. See {@link ArgumentSanitizingToolCallingManager} for both incidents and the full
 * reasoning; the short version is that a model emitting non-JSON tool-call {@code arguments}, or a
 * tool <em>name</em> that was never offered, must degrade to "the turn still completes" rather than
 * an exception thrown after the response headers have already been flushed to the browser.
 *
 * <p><b>Why a bean rather than the one-liner repeated per factory.</b> The wrapper was originally
 * wired into {@link SarvamAgentChatClientFactory} alone, because Sarvam is where the
 * hallucinated-tool-name incidents were observed. That left the other twelve factories - including
 * {@link HostedLlmChatClientFactory}, the PLATFORM_HOSTED pool every FREE-tier agent runs on -
 * still on Spring AI's default manager and still able to hard-fail a turn. Nothing about either
 * failure is Sarvam-specific: both are ordinary small-model behaviour, and the FREE-tier pool is
 * precisely where the smallest, cheapest models get used. Routing every factory through one
 * injected collaborator makes "which providers are protected?" answerable by looking at one
 * constructor parameter per factory instead of reading thirteen builder chains, and gives the
 * wrapping policy a single place to change.
 *
 * <p><b>Not exposed as a {@code ToolCallingManager} bean</b> on purpose: Spring AI's own {@code
 * ToolCallingAutoConfiguration} contributes a {@code ToolCallingManager} bean (wired to the
 * application's {@code ToolCallbackResolver}/{@code ToolExecutionExceptionProcessor}) that other
 * Spring AI machinery may resolve. Publishing a second one of that type would either collide with
 * it or silently replace it app-wide; a distinct type keeps this strictly opt-in at the call sites
 * that ask for it.
 */
@Component
public class ResilientToolCallingManagerFactory {

    /**
     * One shared instance, matching what these factories effectively had before: each Spring AI
     * {@code ChatModel.Builder} defaults its manager to a {@code static final ToolCallingManager
     * DEFAULT_TOOL_CALLING_MANAGER = ToolCallingManager.builder().build()} shared across every
     * instance of that model class. Both the delegate and the wrapper are stateless (the wrapper
     * holds only its delegate and an {@link ObjectMapper}, and derives everything else from the
     * per-call {@code Prompt}/{@code ChatResponse}), so sharing is safe and avoids rebuilding a
     * manager per chat call - these factories construct a fresh client on every single turn (see
     * {@link AnthropicAgentChatClientFactory}'s javadoc on that known cost).
     *
     * <p>{@code ToolCallingManager.builder()} as the delegate is deliberate rather than injecting
     * the application's autoconfigured {@code ToolCallingManager}: it keeps Spring AI's default
     * callback resolver, so the tools a model can reach are exactly what they were before this
     * wrapper existed. Two things are set on it on purpose (2026-09-28):
     *
     * <ul>
     *   <li>the application's {@link ObservationRegistry} (NOOP when there is none), so every tool
     *       execution produces Spring AI's own {@code spring.ai.tool} observation instead of being
     *       invisible;
     *   <li>a {@link RedactingToolExecutionExceptionProcessor}, so a failed tool's exception
     *       message is scrubbed of credential-shaped text and capped before it is sent to the
     *       provider as a tool result.
     * </ul>
     *
     * The wrapper additionally caps the internal tool loop per model invocation ({@code
     * app.chat.tools.max-rounds-per-invocation}, default {@value
     * ArgumentSanitizingToolCallingManager#DEFAULT_MAX_TOOL_ROUNDS}) and turns plain runtime
     * exceptions from a callback into tool results - see {@link
     * ArgumentSanitizingToolCallingManager}'s javadoc.
     */
    private final ToolCallingManager manager;

    /** Test/legacy seam: no observations, no metrics, default caps. */
    public ResilientToolCallingManagerFactory(ObjectMapper objectMapper) {
        this(
                objectMapper,
                null,
                null,
                ArgumentSanitizingToolCallingManager.DEFAULT_MAX_TOOL_ROUNDS,
                ArgumentSanitizingToolCallingManager.DEFAULT_MAX_UNKNOWN_TOOL_CALLS,
                true,
                ToolResultClearing.DEFAULT_THRESHOLD_CHARS,
                ToolResultClearing.DEFAULT_KEEP_LAST);
    }

    /** Pre-argument-validation signature (test harnesses); validation on, as in production. */
    public ResilientToolCallingManagerFactory(
            ObjectMapper objectMapper,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry,
            int maxToolRounds,
            int maxUnknownToolCalls,
            boolean clearingEnabled,
            int clearingThresholdChars,
            int clearingKeepLast) {
        this(
                objectMapper,
                observationRegistry,
                meterRegistry,
                maxToolRounds,
                maxUnknownToolCalls,
                clearingEnabled,
                clearingThresholdChars,
                clearingKeepLast,
                true);
    }

    /**
     * Pre-repeat-guard signature (test harnesses): the guard is on with its default tools, as in
     * production.
     */
    public ResilientToolCallingManagerFactory(
            ObjectMapper objectMapper,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry,
            int maxToolRounds,
            int maxUnknownToolCalls,
            boolean clearingEnabled,
            int clearingThresholdChars,
            int clearingKeepLast,
            boolean argumentValidation) {
        this(
                objectMapper,
                observationRegistry,
                meterRegistry,
                maxToolRounds,
                maxUnknownToolCalls,
                clearingEnabled,
                clearingThresholdChars,
                clearingKeepLast,
                argumentValidation,
                RepeatToolCallGuard.DEFAULT_TOOLS);
    }

    @Autowired
    public ResilientToolCallingManagerFactory(
            ObjectMapper objectMapper,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value(
                            "${app.chat.tools.max-rounds-per-invocation:"
                                    + ArgumentSanitizingToolCallingManager.DEFAULT_MAX_TOOL_ROUNDS
                                    + "}")
                    int maxToolRounds,
            @Value(
                            "${app.chat.tools.max-unknown-calls-per-invocation:"
                                    + ArgumentSanitizingToolCallingManager
                                            .DEFAULT_MAX_UNKNOWN_TOOL_CALLS
                                    + "}")
                    int maxUnknownToolCalls,
            @Value("${app.chat.tools.result-clearing.enabled:true}") boolean clearingEnabled,
            @Value(
                            "${app.chat.tools.result-clearing.threshold-chars:"
                                    + ToolResultClearing.DEFAULT_THRESHOLD_CHARS
                                    + "}")
                    int clearingThresholdChars,
            @Value(
                            "${app.chat.tools.result-clearing.keep-last:"
                                    + ToolResultClearing.DEFAULT_KEEP_LAST
                                    + "}")
                    int clearingKeepLast,
            // A call whose arguments miss a required property is answered with the schema instead
            // of
            // being run - see ArgumentSanitizingToolCallingManager#withArgumentValidation.
            @Value("${app.chat.tools.argument-validation.enabled:true}") boolean argumentValidation,
            // Idempotent lookups a model may not repeat verbatim inside one reply (blank = off, * =
            // every tool);
            // see RepeatToolCallGuard.
            @Value("${app.chat.tools.repeat-guard.tools:" + RepeatToolCallGuard.DEFAULT_TOOLS + "}")
                    String repeatGuardTools) {
        ObservationRegistry observations =
                observationRegistry == null
                        ? ObservationRegistry.NOOP
                        : observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
        MeterRegistry meters = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        org.springframework.ai.model.tool.DefaultToolCallingManager delegate =
                ToolCallingManager.builder()
                        .observationRegistry(observations)
                        .toolExecutionExceptionProcessor(
                                new RedactingToolExecutionExceptionProcessor())
                        .build();
        // OTel GenAI shape for the tool spans: "execute_tool <name>", content off.
        delegate.setObservationConvention(new GenAiToolObservationConvention());
        this.manager =
                new ArgumentSanitizingToolCallingManager(
                                delegate,
                                objectMapper,
                                maxToolRounds,
                                maxUnknownToolCalls,
                                true,
                                meters)
                        .withResultClearing(
                                new ToolResultClearing(
                                        clearingEnabled, clearingThresholdChars, clearingKeepLast))
                        .withArgumentValidation(argumentValidation)
                        .withRepeatGuard(repeatGuardTools);
    }

    /** The manager to hand to {@code ChatModel.Builder#toolCallingManager}. */
    public ToolCallingManager create() {
        return manager;
    }
}
