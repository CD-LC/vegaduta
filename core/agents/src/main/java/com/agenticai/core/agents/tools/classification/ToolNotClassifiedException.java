package com.agenticai.core.agents.tools.classification;

/**
 * Thrown by {@link ToolClassificationDecision#classificationOrThrow()} when a
 * caller that cannot proceed without a classification asks for one that does not
 * exist.
 *
 * <p><b>Why unchecked.</b> Every call site on the tool-dispatch path is already
 * inside a boundary that turns a throw into a refusal the model can read
 * ({@code GuardedToolCallback}'s soft-stop shape) or into a startup failure
 * ({@code AgentToolProvider} registration validation). A checked exception would
 * force {@code try/catch} into the middle of the dispatch chain, and the
 * historically observed outcome of that in tool-calling code is an empty catch
 * block - which is precisely the fail-open behaviour this whole package exists to
 * make impossible.
 *
 * <p>This is deliberately <b>not</b> the normal way to handle an unclassified
 * tool. On the request path the right shape is to pattern-match on
 * {@link ToolClassificationDecision} and return a readable refusal naming the
 * tool and {@link ToolClassificationDecision.Reason}, so the operator sees
 * "unclassified - blocked" in the console rather than a stack trace. Reserve the
 * throwing accessor for registration-time and test-time assertions, where failing
 * the whole operation is the intended result.
 */
public class ToolNotClassifiedException extends RuntimeException {

    private final String toolName;
    private final ToolClassificationDecision.Reason reason;

    public ToolNotClassifiedException(String toolName, ToolClassificationDecision.Reason reason, String detail) {
        super(detail);
        this.toolName = toolName;
        this.reason = reason;
    }

    /** The tool name that had no usable classification - kept structured so a handler does not have to parse the message. */
    public String getToolName() {
        return toolName;
    }

    /** Which of the three failure modes occurred; drives whether the fix is "classify it" or "a human must clear the quarantine". */
    public ToolClassificationDecision.Reason getReason() {
        return reason;
    }
}
