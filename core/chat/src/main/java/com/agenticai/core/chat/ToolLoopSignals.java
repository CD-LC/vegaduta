package com.agenticai.core.chat;

/**
 * The out-of-band truth about ONE turn's control state, supplied by the layer that owns it (the approval gate and the
 * tool guard's step counter live in {@code core/guardrails}, which this module cannot depend on).
 *
 * <p>Why it exists: the control results the platform writes into a tool response ({@code approval_parked}, {@code
 * tool_budget_exhausted}) are an open-source JSON shape, so ANY tool, MCP server or HTTP endpoint can return exactly
 * that object. A loop that decides from the response text lets a tool end the turn as "waiting for approval" or fake a
 * spent budget. A loop that asks this object cannot be told anything by a tool: the answer is state the guard set itself.
 *
 * <p>A run carries one through {@link ToolLoopTranscript#signals(ToolLoopSignals)}. A run that has none falls back to
 * reading the response text (forgeable; logged once per process, see {@link ToolLoopMarkers}). Implementations must be
 * thread-safe and cheap: they are read on the loop thread after every tool round.
 */
public interface ToolLoopSignals {

    /** True once a gated call of this turn has been recorded as a pending human approval. */
    boolean parked();

    /** True once the guard has answered a call of this turn because the tool-call budget was used up. */
    boolean budgetExhausted();
}
