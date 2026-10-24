package com.agenticai.core.chat;

import com.agenticai.core.prompts.PromptResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place a tool-capable {@code ChatModel} in this package gets its
 * {@link ToolCallingManager} from, so that every provider - not just the one
 * whose incident happened to be diagnosed first - is protected from the two
 * ways a model's own tool call can kill a chat turn mid-stream. See {@link
 * ArgumentSanitizingToolCallingManager} for both incidents and the full
 * reasoning; the short version is that a model emitting non-JSON tool-call
 * {@code arguments}, or a tool <em>name</em> that was never offered, must
 * degrade to "the turn still completes" rather than an exception thrown after
 * the response headers have already been flushed to the browser.
 *
 * <p><b>Why a bean rather than the one-liner repeated per factory.</b> The
 * wrapper was originally wired into {@link SarvamAgentChatClientFactory}
 * alone, because Sarvam is where the hallucinated-tool-name incidents were
 * observed. That left the other twelve factories - including {@link
 * HostedLlmChatClientFactory}, the PLATFORM_HOSTED pool every FREE-tier agent
 * runs on - still on Spring AI's default manager and still able to hard-fail a
 * turn. Nothing about either failure is Sarvam-specific: both are ordinary
 * small-model behaviour, and the FREE-tier pool is precisely where the
 * smallest, cheapest models get used. Routing every factory through one
 * injected collaborator makes "which providers are protected?" answerable by
 * looking at one constructor parameter per factory instead of reading thirteen
 * builder chains, and gives the wrapping policy a single place to change.
 *
 * <p><b>Not exposed as a {@code ToolCallingManager} bean</b> on purpose: Spring
 * AI's own {@code ToolCallingAutoConfiguration} contributes a {@code
 * ToolCallingManager} bean (wired to the application's {@code
 * ToolCallbackResolver}/{@code ToolExecutionExceptionProcessor}) that other
 * Spring AI machinery may resolve. Publishing a second one of that type would
 * either collide with it or silently replace it app-wide; a distinct type
 * keeps this strictly opt-in at the call sites that ask for it.
 */
@Component
public class ResilientToolCallingManagerFactory {

    private static final Logger log = LoggerFactory.getLogger(ResilientToolCallingManagerFactory.class);

    /**
     * One shared instance, matching what these factories effectively had
     * before: each Spring AI {@code ChatModel.Builder} defaults its manager to
     * a {@code static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER =
     * ToolCallingManager.builder().build()} shared across every instance of
     * that model class. Both the delegate and the wrapper are stateless (the
     * wrapper holds only its delegate and an {@link ObjectMapper}, and derives
     * everything else from the per-call {@code Prompt}/{@code ChatResponse}),
     * so sharing is safe and avoids rebuilding a manager per chat call - these
     * factories construct a fresh client on every single turn (see {@link
     * AnthropicAgentChatClientFactory}'s javadoc on that known cost).
     *
     * <p>{@code ToolCallingManager.builder()} as the delegate is deliberate
     * rather than injecting the application's autoconfigured {@code
     * ToolCallingManager}: it keeps Spring AI's default callback resolver, so
     * the tools a model can reach are exactly what they were before this
     * wrapper existed. Two things are set on it on purpose (2026-09-28):
     * <ul>
     *   <li>the application's {@link ObservationRegistry} (NOOP when there is
     *       none), so every tool execution produces Spring AI's own {@code
     *       spring.ai.tool} observation instead of being invisible;</li>
     *   <li>a {@link RedactingToolExecutionExceptionProcessor}, so a failed
     *       tool's exception message is scrubbed of credential-shaped text and
     *       capped before it is sent to the provider as a tool result.</li>
     * </ul>
     * The wrapper additionally caps the internal tool loop per model invocation
     * ({@code app.chat.tools.max-rounds-per-invocation}; by default derived from
     * the per-turn call ceiling, see {@link ToolLoopProperties#effectiveMaxRounds})
     * and turns plain runtime exceptions from a callback into tool results - see
     * {@link ArgumentSanitizingToolCallingManager}'s javadoc.
     */
    private final ToolCallingManager manager;

    /** Shipped value of {@code app.guardrails.max-tool-calls-per-turn} (application.yml), for a build without it. */
    public static final int DEFAULT_CALLS_PER_TURN = 15;
    /** Shipped value of {@code app.guardrails.step-limit.autonomous-multiplier}, for a build without it. */
    public static final int DEFAULT_AUTONOMOUS_MULTIPLIER = 2;
    /** Shipped value of {@code app.guardrails.step-limit.grace-calls}, for a build without it. */
    public static final int DEFAULT_GRACE_CALLS = 10;

    /**
     * Test/legacy seam: no observations, no metrics, the shipped defaults - which is a round cap that
     * follows the default call ceiling (15 calls, doubled for the most autonomous tier).
     */
    public ResilientToolCallingManagerFactory(ObjectMapper objectMapper) {
        this(objectMapper, null, null, new ToolLoopProperties(), DEFAULT_CALLS_PER_TURN,
            DEFAULT_AUTONOMOUS_MULTIPLIER, DEFAULT_GRACE_CALLS, true, RepeatToolCallGuard.DEFAULT_TOOLS);
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
        int clearingKeepLast
    ) {
        this(objectMapper, observationRegistry, meterRegistry, maxToolRounds, maxUnknownToolCalls,
            clearingEnabled, clearingThresholdChars, clearingKeepLast, true);
    }

    /** Pre-repeat-guard signature (test harnesses): the guard is on with its default tools, as in production. */
    public ResilientToolCallingManagerFactory(
        ObjectMapper objectMapper,
        ObjectProvider<ObservationRegistry> observationRegistry,
        ObjectProvider<MeterRegistry> meterRegistry,
        int maxToolRounds,
        int maxUnknownToolCalls,
        boolean clearingEnabled,
        int clearingThresholdChars,
        int clearingKeepLast,
        boolean argumentValidation
    ) {
        this(objectMapper, observationRegistry, meterRegistry, maxToolRounds, maxUnknownToolCalls,
            clearingEnabled, clearingThresholdChars, clearingKeepLast, argumentValidation,
            RepeatToolCallGuard.DEFAULT_TOOLS);
    }

    /** Explicit-values signature (test harnesses): the caps are exactly the numbers given. */
    public ResilientToolCallingManagerFactory(
        ObjectMapper objectMapper,
        ObjectProvider<ObservationRegistry> observationRegistry,
        ObjectProvider<MeterRegistry> meterRegistry,
        int maxToolRounds,
        int maxUnknownToolCalls,
        boolean clearingEnabled,
        int clearingThresholdChars,
        int clearingKeepLast,
        boolean argumentValidation,
        String repeatGuardTools
    ) {
        this(objectMapper, observationRegistry, meterRegistry,
            explicit(maxToolRounds, maxUnknownToolCalls, clearingEnabled, clearingThresholdChars, clearingKeepLast),
            DEFAULT_CALLS_PER_TURN, DEFAULT_AUTONOMOUS_MULTIPLIER, DEFAULT_GRACE_CALLS, argumentValidation,
            repeatGuardTools);
    }

    private static ToolLoopProperties explicit(
        int maxToolRounds, int maxUnknownToolCalls, boolean clearingEnabled, int thresholdChars, int keepLast
    ) {
        ToolLoopProperties props = new ToolLoopProperties();
        props.setMaxRoundsPerInvocation(maxToolRounds);
        if (maxUnknownToolCalls > 0) {
            props.setMaxUnknownCallsPerInvocation(maxUnknownToolCalls);
        }
        props.getResultClearing().setEnabled(clearingEnabled);
        props.getResultClearing().setThresholdChars(thresholdChars);
        props.getResultClearing().setKeepLast(keepLast);
        return props;
    }

    @Autowired
    public ResilientToolCallingManagerFactory(
        ObjectMapper objectMapper,
        ObjectProvider<ObservationRegistry> observationRegistry,
        ObjectProvider<MeterRegistry> meterRegistry,
        // app.chat.tools.*: round cap, unknown-tool cap, result clearing, the wording of the stops.
        ToolLoopProperties loop,
        // The turn's call budget, the top autonomy multiplier and the grace past the ceiling, only to derive
        // the round cap from them when app.chat.tools.max-rounds-per-invocation is 0. guardrails owns all
        // three keys; this module cannot depend on it, so it reads the keys (the defaults repeated here are
        // pinned against application.yml and guardrails' own by a test in core/app).
        @Value("${app.guardrails.max-tool-calls-per-turn:" + DEFAULT_CALLS_PER_TURN + "}") int callsPerTurn,
        @Value("${app.guardrails.step-limit.autonomous-multiplier:" + DEFAULT_AUTONOMOUS_MULTIPLIER
            + "}") int autonomousMultiplier,
        @Value("${app.guardrails.step-limit.grace-calls:" + DEFAULT_GRACE_CALLS + "}") int graceCalls,
        // A call whose arguments miss a required property is answered with the schema instead of
        // being run - see ArgumentSanitizingToolCallingManager#withArgumentValidation.
        @Value("${app.chat.tools.argument-validation.enabled:true}") boolean argumentValidation,
        // Idempotent lookups a model may not repeat verbatim inside one reply (blank = off, * = every tool);
        // see RepeatToolCallGuard.
        @Value("${app.chat.tools.repeat-guard.tools:" + RepeatToolCallGuard.DEFAULT_TOOLS + "}") String repeatGuardTools
    ) {
        ObservationRegistry observations = observationRegistry == null
            ? ObservationRegistry.NOOP
            : observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
        MeterRegistry meters = meterRegistry == null ? null : meterRegistry.getIfAvailable();
        ToolLoopProperties props = loop != null ? loop : new ToolLoopProperties();
        int highestCallCeiling = Math.max(1, callsPerTurn) * Math.max(1, autonomousMultiplier);
        int maxToolRounds = props.effectiveMaxRounds(callsPerTurn, autonomousMultiplier, graceCalls);
        if (maxToolRounds < highestCallCeiling) {
            log.warn("app.chat.tools.max-rounds-per-invocation={} is below the highest per-turn tool-call ceiling "
                + "({}): a reply is stopped at {} tool rounds even where the call ceiling allows more. Set it to 0 "
                + "to derive it from the call ceiling.", maxToolRounds, highestCallCeiling, maxToolRounds);
        }
        org.springframework.ai.model.tool.DefaultToolCallingManager delegate = ToolCallingManager.builder()
            .observationRegistry(observations)
            .toolExecutionExceptionProcessor(new RedactingToolExecutionExceptionProcessor())
            .build();
        // OTel GenAI shape for the tool spans: "execute_tool <name>", content off.
        delegate.setObservationConvention(new GenAiToolObservationConvention());
        this.manager = new ArgumentSanitizingToolCallingManager(
            delegate, objectMapper, maxToolRounds, props.getMaxUnknownCallsPerInvocation(), true, meters)
            .withResultClearing(ToolResultClearing.from(props.getResultClearing()))
            .withStopTexts(props.getBudgetExhaustedMessage(), props.getHardStopReply())
            .withArgumentValidation(argumentValidation)
            .withRepeatGuard(repeatGuardTools);
    }

    /**
     * The prompt registry the manager reads the text of its stops from (the tool-budget results and the closing
     * reply, the answer to an unknown tool name). Setter-injected so the constructor, built by hand in tests,
     * stays as it is; without it the manager uses the shipped wording.
     */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        if (manager instanceof ArgumentSanitizingToolCallingManager sanitizing) {
            sanitizing.withPrompts(resolver);
        }
    }

    /** The manager to hand to {@code ChatModel.Builder#toolCallingManager}. */
    public ToolCallingManager create() {
        return manager;
    }
}
