package com.agenticai.core.agents.core;

/**
 * Agent Intelligence research doc (July 2026) - how much deliberation this
 * agent's tool calls get before they execute, per turn. Mirrors the
 * temperature/topP/maxTokens pattern (a per-agent tuning knob, changeable
 * after creation via {@code PATCH /api/agents/{id}/reasoning-depth}), but
 * this one chooses how the turn is run and checked, not the model's own
 * sampling. It is read in two places: {@code ChatTurnDispatcher} routes
 * FAST to the streaming path and CAREFUL / RIGOROUS / PLANNED to the buffered
 * path ({@code BufferedTurnRunner}, which holds the reply until its answer
 * checks have run), and only RIGOROUS also switches on the per-tool-call
 * verifier in {@code GuardedToolCallback}, resolved once per turn by {@code
 * AgentToolProvider} (and only when the user's message carries a verifiable
 * intent). CAREFUL never touches {@code GuardedToolCallback}.
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
