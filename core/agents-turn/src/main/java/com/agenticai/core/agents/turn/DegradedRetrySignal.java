package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
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
     * A CAREFUL/RIGOROUS/PLANNED turn hit {@code app.llm.http.buffered-turn-timeout} and is being
     * re-run as one direct FAST turn without tools, so the person gets an answer instead of
     * "every model failed". Metric plus, on a step-event turn, a {@code guardrail} event that
     * says so. Fail-open.
     */
    static void flagDepthTimeout(
            MeterRegistry meterRegistry, Agent agent, String depth, AgentStepEventEmitter stepEvents) {
        String provider =
                agent.getProviderName() != null ? String.valueOf(agent.getProviderName()) : "unknown";
        try {
            meterRegistry.counter(TIMEOUT_METRIC, "provider", provider, "depth", depth).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record {}", TIMEOUT_METRIC, e);
        }
        log.warn(
                "Agent {} ({} depth) ran out of time before answering; answering at FAST depth without"
                        + " tools instead. If this repeats, set the agent's reasoning depth to FAST or"
                        + " run it on a larger or faster model.",
                agent.getId(),
                depth);
        if (stepEvents != null && stepEvents.isEnabled()) {
            try {
                stepEvents.guardrail(
                        "depth",
                        "Degraded: the "
                                + depth.toLowerCase(java.util.Locale.ROOT)
                                + " answer took too long on this model, so this reply was produced"
                                + " directly, without tools. Set the agent to FAST depth to avoid this.");
            } catch (RuntimeException e) {
                log.debug("Could not emit the depth-timeout step event", e);
            }
        }
    }

    /**
     * A {@value #METRIC} counter and, when the caller asked for step events, a {@code guardrail}
     * event (the existing event type for "something was withheld from this turn" - here, the
     * agent's tools) naming the reason. Fail-open: neither may break the retry.
     */
    static void flag(MeterRegistry meterRegistry, Agent agent, AgentStepEventEmitter stepEvents) {
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
                        "Degraded: the provider rejected this turn's tool calls (known Gemini"
                                + " thought_signature bug), so the answer below was generated with"
                                + " tools disabled.");
            } catch (RuntimeException e) {
                log.debug("Could not emit the tools-disabled step event", e);
            }
        }
    }
}
