// First-run routing decision. The redirect must never guess (unloaded / failed
// fetch), never repeat, and never race the tour's one-shot arming.

import { describe, expect, it } from "vitest";
import { TOUR_SETTLE_MS, firstRunDelay } from "./firstRun";
import type { FirstRunInput } from "./firstRun";

const base: FirstRunInput = {
  agentCount: 0,
  envoyCount: 0,
  alreadyRouted: false,
  isPlatformAdmin: false,
  tourStatus: "idle",
  tourHandled: false,
};

describe("firstRunDelay", () => {
  it("waits for the tour to settle when it has not been offered yet", () => {
    expect(firstRunDelay(base)).toBe(TOUR_SETTLE_MS);
  });

  it("goes at once when the welcome is already up or the tour was handled before", () => {
    expect(firstRunDelay({ ...base, tourStatus: "welcome" })).toBe(0);
    expect(firstRunDelay({ ...base, tourHandled: true })).toBe(0);
  });

  it("never fights a running or just-finished tour", () => {
    expect(firstRunDelay({ ...base, tourStatus: "active", tourHandled: true })).toBeNull();
    expect(firstRunDelay({ ...base, tourStatus: "completed", tourHandled: true })).toBeNull();
  });

  it("needs zero agents AND zero envoys, both actually known", () => {
    expect(firstRunDelay({ ...base, agentCount: 1 })).toBeNull();
    expect(firstRunDelay({ ...base, envoyCount: 2 })).toBeNull();
    expect(firstRunDelay({ ...base, agentCount: null })).toBeNull();
    expect(firstRunDelay({ ...base, envoyCount: null })).toBeNull();
    expect(firstRunDelay({ ...base, agentCount: undefined })).toBeNull();
  });

  it("happens once, and never for a platform admin", () => {
    expect(firstRunDelay({ ...base, alreadyRouted: true })).toBeNull();
    expect(firstRunDelay({ ...base, isPlatformAdmin: true })).toBeNull();
  });
});
