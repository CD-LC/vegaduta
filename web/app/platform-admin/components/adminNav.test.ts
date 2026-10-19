import { describe, expect, it } from "vitest";
import { NAV_SECTIONS, isNavItemActive, sectionHasActive } from "./adminNav";

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
