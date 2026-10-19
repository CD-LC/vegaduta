// Shared vocabulary + honest math for the RL bandit surface (core/rl).
//
// Kept out of the components so the two things that are easy to get WRONG
// live in exactly one place with the reasoning attached:
//
//  1. Which policy dimensions are actually consulted by the runtime. All five
//     dimensions are now read by AgentChatService#chat (2026-08-15 wiring):
//     MODEL_ROUTE (buildChatClientFor), TOOL_SUBSET (resolveToolsFor result
//     filtering), PROMPT_VARIANT (systemPrompt override), and GEN_PARAMS/
//     REASONING_DEPTH (both gated on the agent's TuningMode being SIMPLE -
//     an explicit MANUAL setting always wins). `active` exists so a FUTURE
//     dimension added to PolicyDimension without runtime wiring yet doesn't
//     silently claim to work - see dimensionMeta's fallback.
//
//  2. What alpha/beta mean. See observedRewardRate().
import type { RlPolicyArm } from "./api";

export type RlDimension = "MODEL_ROUTE" | "REASONING_DEPTH" | "GEN_PARAMS" | "TOOL_SUBSET" | "PROMPT_VARIANT";

export interface RlDimensionMeta {
  /** Human label for the dimension. */
  label: string;
  /** What the policy would vary, in plain language. */
  description: string;
  /**
   * True only when some backend code path actually consults this dimension at
   * request time. False means enabling it changes nothing - the UI must say so
   * instead of offering a toggle that silently does nothing.
   */
  active: boolean;
}

export const RL_DIMENSIONS: Record<string, RlDimensionMeta> = {
  MODEL_ROUTE: {
    label: "Model routing",
    description: "Learns which model answers this agent's turns best, and shifts traffic toward it.",
    active: true,
  },
  REASONING_DEPTH: {
    label: "Reasoning depth",
    description:
      "Varies how much deliberation each turn gets (fast / careful / rigorous). Only applies while the agent's tuning mode is Simple — an explicit Manual reasoning depth always wins.",
    active: true,
  },
  GEN_PARAMS: {
    label: "Generation parameters",
    description:
      "Varies temperature and top-p between precise, balanced and creative settings. Only applies while the agent's tuning mode is Simple — explicit Manual values always win.",
    active: true,
  },
  TOOL_SUBSET: {
    label: "Tool subset",
    description: "Varies which subset of the agent's tools is offered on a given turn.",
    active: true,
  },
  PROMPT_VARIANT: {
    label: "Prompt variant",
    description: "Varies which alternate system prompt the turn runs with.",
    active: true,
  },
};

/** Metadata for a dimension, with a safe fallback for anything the backend adds later. */
export function dimensionMeta(dimension: string): RlDimensionMeta {
  return (
    RL_DIMENSIONS[dimension] || {
      label: dimension,
      description: "",
      // Unknown to this build => don't claim it works.
      active: false,
    }
  );
}

/** True when the runtime actually consults this dimension. */
export function isDimensionActive(dimension: string): boolean {
  return dimensionMeta(dimension).active;
}

/**
 * Below this many pulls, the observed rate is noise and should be labelled as
 * such. Not a statistical threshold anyone should defend to three decimals -
 * just a line under which "60%" would flatter three lucky turns.
 */
export const LOW_CONFIDENCE_PULLS = 20;

export interface ObservedReward {
  /** Mean reward observed across this arm's pulls, 0..1 - null when never pulled. */
  rate: number | null;
  /** How many turns that rate is computed from. */
  pulls: number;
  /** True when `rate` exists but rests on too few pulls to mean much. */
  lowConfidence: boolean;
}

/**
 * Turns the raw Beta posterior into something an operator can read, WITHOUT
 * inventing a number.
 *
 * The bandit stores Beta(alpha, beta) with alpha and beta BOTH starting at 1.0
 * (uniform prior, V113__policy_arm.sql) and updates alpha += r, beta += (1 - r)
 * per observed reward r in [0,1]. So the naive posterior mean alpha/(alpha+beta)
 * reports 0.5 - a confident-looking "50%" - for an arm that has never been
 * tried once. That is precisely the kind of fabricated figure this screen must
 * not show.
 *
 * Instead: subtract the prior back out. Observed reward earned = (alpha - 1),
 * over `pulls` trials. Zero pulls returns rate: null, and the UI renders
 * "Not tried yet" rather than a percentage.
 */
export function observedRewardRate(arm: Partial<RlPolicyArm> | null | undefined): ObservedReward {
  const pulls = Math.max(0, Math.round(Number(arm?.pulls) || 0));
  if (pulls === 0) {
    return { rate: null, pulls: 0, lowConfidence: true };
  }
  const alpha = Number(arm?.alpha);
  if (!Number.isFinite(alpha)) {
    return { rate: null, pulls, lowConfidence: true };
  }
  // (alpha - 1) is the reward actually accumulated; clamp so a malformed row
  // can never render a negative or >100% "win rate".
  const earned = Math.min(Math.max(alpha - 1, 0), pulls);
  return { rate: earned / pulls, pulls, lowConfidence: pulls < LOW_CONFIDENCE_PULLS };
}

/** Formats an ObservedReward for display. Never fabricates a figure for an untried arm. */
export function formatObservedReward(observed: ObservedReward): string {
  return observed.rate === null ? "—" : `${(observed.rate * 100).toFixed(0)}%`;
}

/**
 * A MODEL_ROUTE arm's config is `{"model":"<model id>"}` - the only key
 * AgentChatService#modelFromArmConfig reads. Returns null for anything else
 * (including the other dimensions' configs) so callers can fall back to the
 * raw JSON rather than mislabelling it.
 */
export function modelFromArmConfig(armConfigJson: string | null | undefined): string | null {
  if (!armConfigJson) return null;
  try {
    const parsed = JSON.parse(armConfigJson);
    const model = parsed?.model;
    return typeof model === "string" && model.trim() ? model.trim() : null;
  } catch {
    return null;
  }
}

/** Builds the create-arm body for a MODEL_ROUTE arm naming `model`. */
export function modelRouteArmBody(model: string): { armKey: string; armConfigJson: string } {
  const trimmed = model.trim();
  return { armKey: trimmed, armConfigJson: JSON.stringify({ model: trimmed }) };
}
