import { describe, expect, it } from "vitest";
import { railClasses } from "./railClasses";
import { initialsOf } from "./initialsOf";
import { NAV_GROUP_TOUR_TARGETS, NAV_TOUR_TARGETS } from "./navTourTargets";

describe("railClasses", () => {
  it("returns empty strings when the rail is not collapsible", () => {
    const c = railClasses(false, false);
    expect(Object.values(c).every((v) => v === "")).toBe(true);
  });

  it("never restores labels on hover in hub mode", () => {
    const hub = railClasses(true, true);
    expect(hub.railLabelCls).not.toContain("group-hover/rail");
    expect(hub.railRowCollapse).not.toContain("group-hover/rail");
    expect(hub.footerSignoutCollapse).toBe("lg:hidden");
  });

  it("restores labels on hover in the expanding collapsible rail", () => {
    const c = railClasses(true, false);
    expect(c.railLabelCls).toContain("lg:group-hover/rail:opacity-100");
    expect(c.railRowCollapse).toContain("lg:group-hover/rail:px-3");
    expect(c.footerSignoutCollapse).toContain("lg:group-hover/rail:flex");
  });
});

describe("initialsOf", () => {
  it("uses first and last word", () => {
    expect(initialsOf("ada king lovelace")).toBe("AL");
    expect(initialsOf("Ada")).toBe("A");
  });
  it("falls back to ?", () => {
    expect(initialsOf(null)).toBe("?");
    expect(initialsOf("")).toBe("?");
  });
});

describe("tour anchors", () => {
  it("keeps the pre-existing marker spellings", () => {
    expect(NAV_TOUR_TARGETS["/help"]).toBe("help-link");
    expect(NAV_GROUP_TOUR_TARGETS.navGroupMonitor).toBe("nav-monitor");
  });
});
