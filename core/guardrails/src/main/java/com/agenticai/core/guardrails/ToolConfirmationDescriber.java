package com.agenticai.core.guardrails;

/**
 * Optional capability of a tool callback: describe, in plain language, what one specific call is about to do, for
 * the human who is asked to confirm it.
 *
 * <p>When {@link GuardedToolCallback} parks a call for approval it records an approval request whose summary is
 * shown on the confirmation card. The default summary is the bare {@code "Tool call: <name>"}, which tells a person
 * nothing about a tool whose arguments are the whole point (a recurring schedule, for instance, is only safe to
 * approve if its time and destination are visible). A tool that implements this interface supplies a better summary
 * built from its own arguments; a tool that does not behaves exactly as before.
 *
 * <p>Implementations must be cheap and pure, and must not throw - the caller falls back to the default summary on
 * any exception, because describing a call must never be the reason a gate fails to record it.
 */
public interface ToolConfirmationDescriber {

    /**
     * @param toolInput the call's raw JSON arguments, exactly as the model produced them
     * @return a short plain-language description (may span a few lines), or {@code null}/blank for "use the default"
     */
    String describeForConfirmation(String toolInput);
}
