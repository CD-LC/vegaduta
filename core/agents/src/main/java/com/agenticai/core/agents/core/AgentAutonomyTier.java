package com.agenticai.core.agents.core;

/**
 * #815 Governance follow-up - {@code AI-GOVERNANCE-ASSESSMENT.md}'s own
 * "deliberately NOT attempted" section flagged this as the top next
 * governance investment, and warned against shipping "a setting that
 * doesn't actually change agent behavior." This is the real thing, not a
 * cosmetic label - it drives two already-enforced controls (see {@code
 * AgentToolProvider#guardAndLog} and {@code GuardedToolCallback}):
 *
 * <ol>
 *   <li>The tool-call step ceiling (Autonomy/Guardrails Strategy Stage A) -
 *       tighter for {@link #SUPERVISED}, unchanged for {@link
 *       #SEMI_AUTONOMOUS}, looser for {@link #FULLY_AUTONOMOUS}.</li>
 *   <li>Whether a tool call needs human sign-off before it runs at all.
 *       {@link #SEMI_AUTONOMOUS} keeps today's exact behavior - it defers to
 *       whatever the tenant configured via {@code ApprovalPolicyService}'s
 *       per-action-type policy. {@link #SUPERVISED} gates every tool call
 *       regardless of that policy (the tenant is saying "human sign-off on
 *       everything this agent does, no exceptions"). {@link
 *       #FULLY_AUTONOMOUS} never gates a tool call (the tenant is saying
 *       "I trust this agent to act without asking").</li>
 * </ol>
 *
 * <p>Deliberately does NOT attempt a "read-only vs. destructive" tool
 * taxonomy the way the build plan's first draft sketched - that
 * classification doesn't exist anywhere in this codebase today (a
 * user-created SpEL tool, an MCP server's tool, a built-in tool are all
 * opaque strings to the approval layer) and inventing one hastily would be
 * more likely to be wrong than useful. The three-way always-gate/
 * policy-as-configured/never-gate split above is coarser but honest: every
 * word of it is backed by code that actually runs.
 *
 * <p>{@link #SEMI_AUTONOMOUS} is the default for every pre-existing agent
 * (migration V74's backfill) and every newly created one, so this column's
 * mere existence changes nothing until a tenant explicitly picks a
 * different tier for a specific agent.
 */
public enum AgentAutonomyTier {
    SUPERVISED,
    SEMI_AUTONOMOUS,
    FULLY_AUTONOMOUS
}
