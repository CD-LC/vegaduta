import { describe, expect, it } from "vitest";
import { showEnvironmentPill } from "./deployEnv";

describe("showEnvironmentPill", () => {
  it("is off when nothing is configured", () => {
    for (const v of [undefined, null, "", "  "]) expect(showEnvironmentPill(v), String(v)).toBe(false);
  });

  it("is off for the install tiers (personal, lite, full), whatever the case or padding", () => {
    for (const v of ["personal", "lite", "full", "LITE", " Full "]) expect(showEnvironmentPill(v), v).toBe(false);
  });

  it("is on for a named environment", () => {
    for (const v of ["production", "staging", "qa"]) expect(showEnvironmentPill(v), v).toBe(true);
  });
});
