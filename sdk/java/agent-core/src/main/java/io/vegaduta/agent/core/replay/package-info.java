/**
 * Policy replay and shadow mode: evaluate a candidate {@link
 * io.vegaduta.agent.core.GovernancePolicy} against recorded tool calls, or alongside the live gate,
 * before enforcing it.
 *
 * <p>{@link io.vegaduta.agent.core.replay.PolicyReplay} answers "if this policy had been active
 * over the recorded period, what would have been blocked or escalated, and how many human reviews
 * would it have cost". {@link io.vegaduta.agent.core.replay.ShadowGate} answers the same question
 * live without changing a single verdict. {@link io.vegaduta.agent.core.replay.PolicyDiff} says
 * which knobs differ between two policies.
 */
@io.vegaduta.agent.api.Experimental
package io.vegaduta.agent.core.replay;
