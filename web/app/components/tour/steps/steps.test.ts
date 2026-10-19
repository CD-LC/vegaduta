import { describe, expect, it } from "vitest";
import { TOUR_STEPS } from "./index";
import { ONBOARDING_STEPS } from "./onboarding";
import { WALK_STEPS } from "./walk";
import { CONNECT_STEPS } from "./connect";
import { CHROME_STEPS } from "./chrome";

describe("tour step data modules", () => {
  it("concatenate, in order, to the full catalogue", () => {
    expect(TOUR_STEPS).toEqual([...ONBOARDING_STEPS, ...WALK_STEPS, ...CONNECT_STEPS, ...CHROME_STEPS]);
  });

  it("keep the catalogue order and unique ids", () => {
    const ids = TOUR_STEPS.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids[0]).toBe("start");
    expect(ids[ids.length - 1]).toBe("help");
    expect(ids.indexOf("platform-console")).toBe(3);
  });
});
