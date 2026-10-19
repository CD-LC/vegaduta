package com.agenticai.core.agents.core;

/**
 * Direct feedback: "give this as an extra feature instead of [changing] the
 * simple workflow... make it available in premium and unlimited [plans]."
 * This is a one-time, optional "Quick setup" preset - not a new persisted
 * identity (see {@code AgentService#applyTier}'s javadoc for why nothing is
 * stored on {@link Agent} for "which tier this agent is"). Picking a tier
 * just pre-fills three already-existing, already-independently-editable
 * knobs in one click: {@code Agent.model} (within its current provider),
 * {@link ReasoningDepth}, and {@link AgentAutonomyTier}. A tenant can still
 * hand-edit any of the three afterward exactly as before - applying a tier
 * is a shortcut, never a lock.
 *
 * <ul>
 *   <li>{@link #CONSUMER} - cheapest/fastest model on the agent's current
 *       provider, {@code FAST} reasoning, {@code SEMI_AUTONOMOUS} - today's
 *       defaults for a new agent. Basic single-turn queries.</li>
 *   <li>{@link #PROFESSIONAL} - mid-tier model, {@code CAREFUL} reasoning
 *       (self-consistency check), {@code SEMI_AUTONOMOUS}. Business
 *       workflows, multi-step Quick Tasks.</li>
 *   <li>{@link #EXPERT} - top-tier model, {@code PLANNED} reasoning
 *       (plan-and-execute), {@code SUPERVISED} - the extra human-sign-off
 *       gate is a deliberate safety pairing for the tier that chains the
 *       most unattended tool calls per turn, not an oversight.</li>
 * </ul>
 *
 * <p>Gated to {@code TenantPlan.PREMIUM}/{@code UNLIMITED} - see {@code
 * AgentService#applyTier}. FREE-plan tenants keep full manual access to the
 * same three underlying fields; only the one-click preset shortcut is
 * Premium+.
 */
public enum AgentTier {
    CONSUMER,
    PROFESSIONAL,
    EXPERT
}
