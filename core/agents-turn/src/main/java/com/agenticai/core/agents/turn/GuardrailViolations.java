package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.guardrails.GuardrailViolationException;

/** The {@code guardrail} step event for a turn stream's error path (split from the dispatcher). */
final class GuardrailViolations {

    private GuardrailViolations() {}

    /** Emits the {@code guardrail} step event when {@code error} wraps a guardrail violation. */
    static void report(AgentStepEventEmitter stepEvents, Throwable error) {
        GuardrailViolationException violation = find(error);
        if (violation != null) {
            stepEvents.guardrail(violation.getToolName(), reason(violation));
        }
    }

    /**
     * Finds a {@link GuardrailViolationException} anywhere in {@code error}'s cause chain, for the
     * step-event {@code guardrail} hook in {@link AgentChatService#chat}. The buffered depths run
     * their {@code .call()} inside {@code Flux.defer} and provider-fallback wrappers, so the
     * exception can arrive wrapped (a {@code CompletionException}, an {@code
     * IllegalStateException}) rather than raw - same reason {@code
     * AgentChatStreamResponder#midStreamFailureMessage} walks the chain, and the same bounded walk:
     * a cause chain can be cyclic and this runs on a live stream's error path.
     */
    static GuardrailViolationException find(Throwable error) {
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
    static String reason(GuardrailViolationException violation) {
        String message = violation.getMessage();
        if (message == null) {
            return "";
        }
        String prefix = "Guardrail blocked output from tool '" + violation.getToolName() + "': ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }
}
