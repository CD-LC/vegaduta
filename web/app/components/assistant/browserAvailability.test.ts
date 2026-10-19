import { describe, expect, it } from "vitest";
import type { BrowserCapabilities, BrowserProfile } from "../../lib/api/browserAutomation";
import { browserAvailability, profileFor } from "./browserAvailability";

const takeover = { enabled: true, fps: 2, idleTimeoutSeconds: 120 };
const browser: BrowserCapabilities = { profilesEnabled: true, recipesEnabled: false, totpEnabled: false, browserAvailable: true };

describe("browserAvailability", () => {
  it("hides while either probe is loading", () => {
    expect(browserAvailability(undefined, browser, true)).toBe("hidden");
    expect(browserAvailability(takeover, undefined, true)).toBe("hidden");
  });
  it("is ready when core has takeover on", () => {
    expect(browserAvailability(takeover, browser, true)).toBe("ready");
    expect(browserAvailability(takeover, browser, false)).toBe("ready");
  });
  it("says unavailable when the install has no headless browser", () => {
    expect(browserAvailability(takeover, { ...browser, browserAvailable: false }, false)).toBe("unavailable");
  });
  it("says unavailable on the personal install when core answered but takeover is off", () => {
    expect(browserAvailability(null, browser, true)).toBe("unavailable");
  });
  it("stays hidden elsewhere, or when core could not be reached at all", () => {
    expect(browserAvailability(null, browser, false)).toBe("hidden");
    expect(browserAvailability(null, null, true)).toBe("hidden");
  });
});

describe("profileFor", () => {
  const p = (id: string, agents: string[]) => ({ id, attachedAgentIds: agents }) as BrowserProfile;
  it("finds the profile attached to the agent", () => {
    expect(profileFor([p("a", ["x"]), p("b", ["y"])], "y")?.id).toBe("b");
    expect(profileFor([p("a", ["x"])], "z")).toBeNull();
  });
});
