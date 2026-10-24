package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.prompts.PromptResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the Gemini thought_signature workaround's degraded retry (a turn silently re-run without
 * its tools) visible. Moved out of {@link ChatTurnExecutor} unchanged, to keep that file under the
 * file-size ratchet.
 */
final class DegradedRetrySignal {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private DegradedRetrySignal() {}

    /**
     * Counter incremented every time a turn is silently re-run WITHOUT its tools (today: the Gemini
     * thought_signature workaround below). Tagged with the provider and the reason; the user still
     * gets an answer, but one produced with none of the agent's tools, which is a quality
     * degradation worth seeing on a dashboard rather than only in a WARN line.
     */
    static final String METRIC = "chat.degraded.tools_disabled";

    /** Counter for a buffered-depth turn that ran out of time and was answered at FAST depth instead. */
    static final String TIMEOUT_METRIC = "chat.degraded.depth_timeout";

    /** Counter for a buffered-depth turn that ran out of time after tools ran and was reported, not re-answered. */
    static final String TIMEOUT_PARTIAL_METRIC = "chat.degraded.depth_timeout_partial";

    /** Counter for a provider failover that was refused because the turn had already run tools. */
    static final String REPLAY_BLOCKED_METRIC = "chat.failover.blocked";

    /** True when {@code error} is, or was caused by, a Reactor/JDK timeout (the buffered turn's deadline). */
    static boolean isTimeout(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            // ProviderErrorTranslator may have re-wrapped the TimeoutException with only its text kept.
            String message = current.getMessage();
            if (message != null && message.contains("Did not observe any item or terminal signal")) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    /**
     * A CAREFUL/RIGOROUS/PLANNED turn hit its time limit before any tool ran and is being answered again
     * as one direct FAST turn, tools included, so the person gets an answer instead of "every model
     * failed". Metric plus, on a step-event turn, a {@code guardrail} event that says so. Fail-open.
     */
    static void flagDepthTimeout(
            MeterRegistry meterRegistry,
            Agent agent,
            String depth,
            ChatResilienceProperties texts,
            AgentStepEventEmitter stepEvents) {
        String provider =
                agent.getProviderName() != null ? String.valueOf(agent.getProviderName()) : "unknown";
        try {
            meterRegistry.counter(TIMEOUT_METRIC, "provider", provider, "depth", depth).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {}", TIMEOUT_METRIC, e);
        }
        log.warn(
                "Agent {} ({} depth) ran out of time before answering and before any tool ran; answering at FAST"
                        + " depth instead. If this repeats, set the agent's reasoning depth to FAST or"
                        + " run it on a larger or faster model.",
                agent.getId(),
                depth);
        emitGuardrail(stepEvents, "depth", texts.renderDegradedNotice(depth));
    }

    /**
     * A CAREFUL/RIGOROUS/PLANNED turn hit its time limit AFTER tools ran. The turn is reported with what
     * ran instead of being answered again (an answer written without the tool results can claim anything,
     * and a second run could repeat the tools). Counted and logged.
     */
    static void flagDepthTimeoutPartial(MeterRegistry meterRegistry, Agent agent, String depth, String tools) {
        String provider =
                agent.getProviderName() != null ? String.valueOf(agent.getProviderName()) : "unknown";
        try {
            meterRegistry.counter(TIMEOUT_PARTIAL_METRIC, "provider", provider, "depth", depth).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {}", TIMEOUT_PARTIAL_METRIC, e);
        }
        log.warn(
                "Agent {} ({} depth) ran out of time after running tools ({}); reporting the steps that ran instead"
                        + " of answering again without them.",
                agent.getId(),
                depth,
                tools);
    }

    /**
     * A provider failure was NOT retried on another provider because the turn had already run tools ({@code
     * tools}). Counted, logged, and on a step-event turn told to the consumer. Fail-open.
     */
    static void flagReplayBlocked(
            MeterRegistry meterRegistry,
            Agent agent,
            String path,
            String tools,
            ChatResilienceProperties texts,
            AgentStepEventEmitter stepEvents) {
        String provider =
                agent.getProviderName() != null ? String.valueOf(agent.getProviderName()) : "unknown";
        try {
            meterRegistry.counter(REPLAY_BLOCKED_METRIC, "provider", provider, "path", path).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {}", REPLAY_BLOCKED_METRIC, e);
        }
        log.warn(
                "Agent {}: the provider failed after the turn ran tools ({}); not failing over ({}) because the"
                        + " fallback would run the turn, and those tools, a second time.",
                agent.getId(),
                tools,
                path);
        emitGuardrail(stepEvents, "failover", texts.renderReplayBlockedNotice(tools));
    }

    private static void emitGuardrail(AgentStepEventEmitter stepEvents, String subject, String text) {
        if (stepEvents != null && stepEvents.isEnabled()) {
            try {
                stepEvents.guardrail(subject, text);
            } catch (RuntimeException e) {
                log.debug("Could not emit the {} step event", subject, e);
            }
        }
    }

    /**
     * A {@value #METRIC} counter and, when the caller asked for step events, a {@code guardrail}
     * event (the existing event type for "something was withheld from this turn" - here, the
     * agent's tools) naming the reason. Fail-open: neither may break the retry.
     */
    static void flag(MeterRegistry meterRegistry, Agent agent, AgentStepEventEmitter stepEvents) {
        flag(meterRegistry, agent, stepEvents, null);
    }

    /** Registry key of the step-event text; see {@code prompts/en/message.degraded.tools_disabled.md}. */
    static final String TOOLS_DISABLED_KEY = "message.degraded.tools_disabled";

    /**
     * As {@link #flag(MeterRegistry, Agent, AgentStepEventEmitter)}, with the step-event text the caller
     * resolved for the agent ({@code message.degraded.tools_disabled}); {@code null} = the shipped text.
     */
    static void flag(
            MeterRegistry meterRegistry, Agent agent, AgentStepEventEmitter stepEvents, String text) {
        String provider =
                agent.getProviderName() != null
                        ? String.valueOf(agent.getProviderName())
                        : "unknown";
        try {
            meterRegistry
                    .counter(METRIC, "provider", provider, "reason", "gemini_thought_signature")
                    .increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {}", METRIC, e);
        }
        if (stepEvents != null && stepEvents.isEnabled()) {
            try {
                stepEvents.guardrail(
                        "tools",
                        text != null
                                ? text
                                : PromptResolver.defaultsOnly().catalog().defaultText(TOOLS_DISABLED_KEY));
            } catch (RuntimeException e) {
                log.debug("Could not emit the tools-disabled step event", e);
            }
        }
    }
}
