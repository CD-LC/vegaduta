import { afterEach, describe, expect, it, vi } from "vitest";
import { NAV_SECTIONS, filterEditionNav, isNavItemActive, sectionHasActive } from "./adminNav";

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__ENV;
});

const all = NAV_SECTIONS.flatMap((s) => s.items);
const find = (href: string) => all.find((i) => i.href === href)!;

describe("adminNav", () => {
  it("keeps every group at 7 items or fewer and has no duplicate hrefs", () => {
    for (const s of NAV_SECTIONS) expect(s.items.length).toBeLessThanOrEqual(7);
    expect(new Set(all.map((i) => i.href)).size).toBe(all.length);
  });

  it("merges the demo sandbox pair into one entry that covers both routes", () => {
    expect(all.find((i) => i.href === "/platform-admin/demo-sandboxes")).toBeUndefined();
    const item = find("/platform-admin/demo-sandbox");
    expect(isNavItemActive(item, "/platform-admin/demo-sandbox")).toBe(true);
    expect(isNavItemActive(item, "/platform-admin/demo-sandboxes")).toBe(true);
    expect(isNavItemActive(find("/platform-admin/ops"), "/platform-admin/ops/x")).toBe(true);
  });

  it("treats Overview as exact-match only and finds the active group", () => {
    const overview = find("/platform-admin");
    expect(isNavItemActive(overview, "/platform-admin/tenants")).toBe(false);
    const audit = NAV_SECTIONS.find((s) => s.label === "Health & Audit")!;
    expect(sectionHasActive(audit, "/platform-admin/audit")).toBe(true);
    expect(sectionHasActive(audit, "/platform-admin/jobs")).toBe(false);
  });
});

describe("adminNav on a personal install", () => {
  it("keeps only the LLM pools, tool credentials and settings", () => {
    const rail = filterEditionNav(NAV_SECTIONS, true).flatMap((s) => s.items.map((i) => i.href));
    expect(rail.sort()).toEqual(
      ["/platform-admin/llm-providers", "/platform-admin/settings", "/platform-admin/tool-credentials"].sort()
    );
  });

  it("drops the sections it empties, so no group is left with a bare header", () => {
    const sections = filterEditionNav(NAV_SECTIONS, true);
    expect(sections.map((s) => s.label)).toEqual(["Models & Tools", "Operations"]);
    for (const s of sections) expect(s.items.length).toBeLessThanOrEqual(7);
  });

  it("leaves the full rail for every other edition", () => {
    expect(filterEditionNav(NAV_SECTIONS, false)).toEqual(NAV_SECTIONS);
  });

  it("follows the declared edition when no flag is passed", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    expect(filterEditionNav(NAV_SECTIONS)).toHaveLength(2);
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    expect(filterEditionNav(NAV_SECTIONS)).toHaveLength(NAV_SECTIONS.length);
  });
});
