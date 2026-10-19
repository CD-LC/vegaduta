package com.agenticai.core.agents.core;

/**
 * Agent Intelligence research doc (July 2026) - how much deliberation this
 * agent's tool calls get before they execute, per turn. Mirrors the
 * temperature/topP/maxTokens pattern (a per-agent tuning knob, changeable
 * after creation via {@code PATCH /api/agents/{id}/reasoning-depth}), but
 * this one controls the guardrail/verification pipeline in {@code
 * GuardedToolCallback}, not the model's own sampling.
 *
 * <ul>
 *   <li>{@link #FAST} - today's behavior, unchanged: no extra verification
 *       pass, lowest latency. Right default for high-volume, low-risk
 *       agents (FAQ bots, simple lookups).</li>
 *   <li>{@link #CAREFUL} -
 *       self-consistency re-checks the model's own final answer once (via
 *       {@code AnswerConsistencyChecker}) before it's shown, revising it
 *       once if the check finds a genuine problem.</li>
 *   <li>{@link #RIGOROUS} - CAREFUL's self-consistency check, plus
 *       a stricter Reflexion self-critique pass (via {@code
 *       ReflexionService}), plus every tool call this agent makes is
 *       first checked by an independent verifier call (see {@code
 *       ToolCallVerifierService}) before it's allowed to execute: "does
 *       this tool call plausibly match what the user actually asked for?"
 *       Costs several extra lightweight model calls per turn; the right
 *       default for agents that take side-effecting actions (sends a
 *       message, spends money, deletes/writes data).</li>
 *   <li>{@link #PLANNED} - Plan-and-Execute: before the normal
 *       tool-calling loop starts, one cheap planning call breaks the
 *       request into an ordered step list (via {@code TaskPlanningService}),
 *       surfaced to the model as context and to the caller/trace as a
 *       distinct plan entry - see that service's javadoc. Opt-in and
 *       independent of CAREFUL/RIGOROUS's answer-quality checks - today's
 *       build does not combine PLANNED with those extra passes in the same
 *       turn.</li>
 * </ul>
 */
public enum ReasoningDepth {
    FAST,
    CAREFUL,
    RIGOROUS,
    PLANNED
}
