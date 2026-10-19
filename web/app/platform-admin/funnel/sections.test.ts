import { describe, it, expect } from "vitest";
import { SECTIONS, UNATTRIBUTED_CLICKS, RETIRED_EVENTS, OTHER_PAGE_EVENTS } from "./sections";

describe("funnel sections inventory", () => {
  it("has unique section keys and non-empty labels/jobs", () => {
    const keys = SECTIONS.map((s) => s.key);
    expect(new Set(keys).size).toBe(keys.length);
    for (const s of SECTIONS) {
      expect(s.label).toBeTruthy();
      expect(s.job).toBeTruthy();
    }
  });

  it("starts at hero and ends at the closing CTA (render order)", () => {
    expect(SECTIONS[0].key).toBe("hero");
    expect(SECTIONS[SECTIONS.length - 1].key).toBe("closing");
  });

  it("attributes each click event to at most one band", () => {
    const all = SECTIONS.flatMap((s) => s.clicks);
    expect(new Set(all).size).toBe(all.length);
  });

  it("keeps attributed, unattributed, retired and other-page events disjoint", () => {
    const attributed = new Set(SECTIONS.flatMap((s) => s.clicks));
    const groups = [
      UNATTRIBUTED_CLICKS.map((c) => c.event),
      RETIRED_EVENTS.map((c) => c.event),
      OTHER_PAGE_EVENTS,
    ];
    for (const g of groups) {
      for (const ev of g) expect(attributed.has(ev)).toBe(false);
    }
    const flat = groups.flat();
    expect(new Set(flat).size).toBe(flat.length);
  });

  it("documents the misnamed automate_* counters on the proof band", () => {
    const proof = SECTIONS.find((s) => s.key === "proof")!;
    expect(proof.clicks).toContain("automate_try_click");
    expect(proof.caveat).toBeTruthy();
  });
});
