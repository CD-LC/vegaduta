import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it, vi } from "vitest";
import {
  ELAPSED_BUCKETS,
  ONBOARDING_EVENTS,
  ONBOARDING_STEPS,
  elapsedBucket,
  elapsedParts,
  elapsedSeconds,
  firstRunBucketEvent,
  flushOnboardingEvents,
  goalSelectedEvent,
  isOnboardingEvent,
  skippedEvent,
} from "./events";
import { GOAL_IDS } from "./goals";

describe("onboarding event vocabulary", () => {
  it("is exactly the closed set of 18 names core must whitelist", () => {
    expect(ONBOARDING_EVENTS).toHaveLength(18);
    expect(new Set(ONBOARDING_EVENTS).size).toBe(18);
    for (const name of ONBOARDING_EVENTS) expect(name).toMatch(/^onboarding_[a-z0-9_]+$/);
  });

  it("is generated consistently from the goal, step and bucket catalogues", () => {
    const generated = [
      "onboarding_start",
      ...GOAL_IDS.map(goalSelectedEvent),
      "onboarding_agent_created",
      "onboarding_prompt_prefilled",
      "onboarding_first_run_completed",
      ...ELAPSED_BUCKETS.map(firstRunBucketEvent),
      ...ONBOARDING_STEPS.map(skippedEvent),
    ];
    expect([...generated].sort()).toEqual([...ONBOARDING_EVENTS].sort());
  });

  it("carries the parameter in the name, never a payload (goal id only, no free text)", () => {
    expect(goalSelectedEvent("research")).toBe("onboarding_goal_selected_research");
    expect(skippedEvent("create")).toBe("onboarding_skipped_create");
    expect(firstRunBucketEvent("lt5m")).toBe("onboarding_first_run_lt5m");
  });

  it("recognises only its own names", () => {
    expect(isOnboardingEvent("onboarding_start")).toBe(true);
    expect(isOnboardingEvent("landing_view")).toBe(false);
    expect(isOnboardingEvent("onboarding_goal_selected_<script>")).toBe(false);
    expect(isOnboardingEvent(undefined)).toBe(false);
  });

  it("is whitelisted, twice, in core's LandingEventController (when the repo checkout includes core)", () => {
    const file = path.resolve(
      __dirname,
      "../../../core/platform/src/main/java/com/agenticai/core/platform/LandingEventController.java"
    );
    // The web image build context has no core/; this guard only runs where the monorepo does.
    if (!existsSync(file)) return;
    const java = readFileSync(file, "utf8");
    for (const name of ONBOARDING_EVENTS) {
      const hits = java.split(`"${name}"`).length - 1;
      expect(hits, `${name} must appear in ALLOWED_EVENTS and ALLOWED_EVENTS_ORDERED`).toBeGreaterThanOrEqual(2);
    }
  });
});

describe("elapsed time", () => {
  it("measures whole seconds and clamps a backwards clock to zero", () => {
    expect(elapsedSeconds(1_000, 62_500)).toBe(61);
    expect(elapsedSeconds(10_000, 5_000)).toBe(0);
    expect(elapsedSeconds(null, 5_000)).toBeNull();
    expect(elapsedSeconds(Number.NaN, 5_000)).toBeNull();
  });

  it("buckets at 60 / 120 / 300 / 600 seconds, the 300 s edge being the under-five-minutes line", () => {
    expect(elapsedBucket(0)).toBe("lt1m");
    expect(elapsedBucket(59)).toBe("lt1m");
    expect(elapsedBucket(60)).toBe("lt2m");
    expect(elapsedBucket(119)).toBe("lt2m");
    expect(elapsedBucket(120)).toBe("lt5m");
    expect(elapsedBucket(299)).toBe("lt5m");
    expect(elapsedBucket(300)).toBe("lt10m");
    expect(elapsedBucket(599)).toBe("lt10m");
    expect(elapsedBucket(600)).toBe("gte10m");
    expect(elapsedBucket(86_400)).toBe("gte10m");
  });

  it("refuses to bucket an unknown or negative time", () => {
    expect(elapsedBucket(null)).toBeNull();
    expect(elapsedBucket(undefined)).toBeNull();
    expect(elapsedBucket(-1)).toBeNull();
    expect(elapsedBucket(Number.POSITIVE_INFINITY)).toBeNull();
  });

  it("splits seconds into minutes and seconds for display", () => {
    expect(elapsedParts(130)).toEqual({ minutes: 2, seconds: 10 });
    expect(elapsedParts(45)).toEqual({ minutes: 0, seconds: 45 });
    expect(elapsedParts(null)).toBeNull();
    expect(elapsedParts(-3)).toBeNull();
  });
});

describe("flushOnboardingEvents", () => {
  it("sends each new known event once and remembers it", () => {
    const send = vi.fn();
    const sent = new Set<string>();
    flushOnboardingEvents(["onboarding_start", "onboarding_agent_created"], sent, send);
    flushOnboardingEvents(["onboarding_start", "onboarding_agent_created", "onboarding_prompt_prefilled"], sent, send);
    expect(send.mock.calls.map((c) => c[0])).toEqual([
      "onboarding_start",
      "onboarding_agent_created",
      "onboarding_prompt_prefilled",
    ]);
  });

  it("never hands the beacon a name outside the vocabulary", () => {
    const send = vi.fn();
    flushOnboardingEvents(["landing_view", "onboarding_nope", "onboarding_start"], new Set(), send);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith("onboarding_start");
  });

  it("does not re-send what an earlier visit already counted", () => {
    const send = vi.fn();
    flushOnboardingEvents(["onboarding_start"], new Set(["onboarding_start"]), send);
    expect(send).not.toHaveBeenCalled();
  });
});
