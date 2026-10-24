import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { NAV_SECTIONS } from "./navSections";

// Authenticated console routes that must stay reachable from the rail. Add a route
// here when a page ships without a nav row would otherwise orphan it.
const REQUIRED_NAV_ROUTES = ["/assistant", "/roi", "/admin/payments", "/agents/quality", "/agents/turns", "/insights", "/usage", "/admin/runtime-settings"];

describe("navSections", () => {
  // The blueprint is split into two halves (navSections.ts assembles them).
  const source = ["navSectionsAuthor.ts", "navSectionsOperate.ts"]
    .map((file) => readFileSync(`app/components/nav/${file}`, "utf8"))
    .join("\n");
  const hrefs = NAV_SECTIONS.flatMap((section) => section.items.map((item) => item.href));

  it.each(REQUIRED_NAV_ROUTES)("lists %s as a nav href", (route) => {
    expect(source).toContain(`href: "${route}"`);
    expect(hrefs).toContain(route);
  });

  it("has no duplicate hrefs", () => {
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });

  it("gives the cost and payments rows their scopes", () => {
    const find = (href: string) => NAV_SECTIONS.flatMap((s) => s.items).find((i) => i.href === href);
    expect(find("/roi")?.perm).toBe("VIEW_USAGE");
    expect(find("/admin/payments")?.perm).toBe("MANAGE_AGENTS");
    expect(find("/agents/turns")?.perm).toBe("VIEW_AUDIT_LOG");
    expect(find("/admin/runtime-settings")?.perm).toBe("MANAGE_TENANT_SETTINGS");
  });
});
