// The funnel vocabulary for the /start onboarding flow.
//
// These ride the EXISTING landing beacon (components/demo-landing/landingBeacon.ts
// -> POST /api/public/landing-event -> core's LandingEventController), which
// accepts ONLY a fixed event name: the name becomes part of a Redis counter key,
// so an open vocabulary would hand callers an unbounded keyspace, and the
// controller deliberately stores nothing else (no IP, no body fields, no user
// id). That is why a "parameter" such as the goal, the skipped step or the
// time bucket is part of the NAME below rather than a payload field: a closed,
// countable set, anonymous by construction, with no free text anywhere.
//
// Every string in ONBOARDING_EVENTS must also be in core's
// LandingEventController.ALLOWED_EVENTS and ALLOWED_EVENTS_ORDERED, or the
// endpoint answers 204 and drops it silently (a flat zero that looks exactly
// like "nobody did this"). LandingEventControllerTest pins the Java side to the
// same 18 names; events.test.ts pins this side.
//
// Measurement note: the headline number is "time to first successful agent".
// onboarding_first_run_completed is the denominator and the five
// onboarding_first_run_<bucket> counters split it by elapsed time since the
// first /start visit, so "share under five minutes" = (lt1m + lt2m + lt5m) /
// completed. It is only observable for AGENT goals - a route goal (the tutor,
// workflows, the app builder) hands the person to another page, after which
// this flow can no longer see what happens.

import type { GoalId } from "./goals";

export type OnboardingStep = "goal" | "create" | "try";

export const ONBOARDING_STEPS: readonly OnboardingStep[] = ["goal", "create", "try"];

/** Elapsed time since the first /start visit, coarse on purpose. */
export type ElapsedBucket = "lt1m" | "lt2m" | "lt5m" | "lt10m" | "gte10m";

export const ELAPSED_BUCKETS: readonly ElapsedBucket[] = ["lt1m", "lt2m", "lt5m", "lt10m", "gte10m"];

export type OnboardingEvent =
  | "onboarding_start"
  | `onboarding_goal_selected_${GoalId}`
  | "onboarding_agent_created"
  | "onboarding_prompt_prefilled"
  | "onboarding_first_run_completed"
  | `onboarding_first_run_${ElapsedBucket}`
  | `onboarding_skipped_${OnboardingStep}`;

export const ONBOARDING_EVENTS: readonly OnboardingEvent[] = [
  "onboarding_start",
  "onboarding_goal_selected_learn",
  "onboarding_goal_selected_research",
  "onboarding_goal_selected_analyze",
  "onboarding_goal_selected_automate",
  "onboarding_goal_selected_draft",
  "onboarding_goal_selected_build",
  "onboarding_agent_created",
  "onboarding_prompt_prefilled",
  "onboarding_first_run_completed",
  "onboarding_first_run_lt1m",
  "onboarding_first_run_lt2m",
  "onboarding_first_run_lt5m",
  "onboarding_first_run_lt10m",
  "onboarding_first_run_gte10m",
  "onboarding_skipped_goal",
  "onboarding_skipped_create",
  "onboarding_skipped_try",
];

export function goalSelectedEvent(goal: GoalId): OnboardingEvent {
  return `onboarding_goal_selected_${goal}`;
}

export function skippedEvent(step: OnboardingStep): OnboardingEvent {
  return `onboarding_skipped_${step}`;
}

export function firstRunBucketEvent(bucket: ElapsedBucket): OnboardingEvent {
  return `onboarding_first_run_${bucket}`;
}

export function isOnboardingEvent(value: unknown): value is OnboardingEvent {
  return typeof value === "string" && (ONBOARDING_EVENTS as readonly string[]).includes(value);
}

/**
 * Whole seconds from `startedAt` to `now` (epoch ms), or null when either is
 * not a usable time. A clock that moved backwards clamps to 0 rather than
 * producing a negative bucket.
 */
export function elapsedSeconds(startedAt: number | null | undefined, now: number): number | null {
  if (typeof startedAt !== "number" || !Number.isFinite(startedAt) || !Number.isFinite(now)) return null;
  return Math.max(0, Math.floor((now - startedAt) / 1000));
}

/** Buckets whole seconds. The 300 s edge is the "under five minutes" line the lane is measured against. */
export function elapsedBucket(seconds: number | null | undefined): ElapsedBucket | null {
  if (typeof seconds !== "number" || !Number.isFinite(seconds) || seconds < 0) return null;
  if (seconds < 60) return "lt1m";
  if (seconds < 120) return "lt2m";
  if (seconds < 300) return "lt5m";
  if (seconds < 600) return "lt10m";
  return "gte10m";
}

/**
 * Sends each event in `fired` that is a known onboarding event and has not been
 * sent yet this page life, then records it in `sent`. Anything outside the
 * closed vocabulary is dropped HERE as well - the beacon must never be handed a
 * name core would not recognise.
 */
export function flushOnboardingEvents(
  fired: readonly string[],
  sent: Set<string>,
  send: (event: OnboardingEvent) => void
): void {
  for (const name of fired) {
    if (sent.has(name)) continue;
    sent.add(name);
    if (isOnboardingEvent(name)) send(name);
  }
}

/** Whole minutes + remaining seconds, for "about 2 min 10 s" copy; null when the time is unknown. */
export function elapsedParts(seconds: number | null | undefined): { minutes: number; seconds: number } | null {
  if (typeof seconds !== "number" || !Number.isFinite(seconds) || seconds < 0) return null;
  const whole = Math.floor(seconds);
  return { minutes: Math.floor(whole / 60), seconds: whole % 60 };
}
