import { afterEach, describe, expect, it, vi } from "vitest";
import {
  billingOffRedirect,
  filterBillingItems,
  filterBillingNav,
  isBillingEnabled,
  isBillingNavHref,
  isBillingRoute,
  llmSpendHref,
  showUpgradeLink,
} from "./billing-flag";

// Billing switch (lib/billing-flag.ts): OFF unless NEXT_PUBLIC_BILLING_ENABLED
// is exactly "1", read at runtime (window.__ENV > build-time value).
afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__ENV;
  delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
});

describe("isBillingEnabled", () => {
  it("defaults to OFF when unset", () => {
    expect(isBillingEnabled()).toBe(false);
  });

  it("is ON only for exactly \"1\"", () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    expect(isBillingEnabled()).toBe(true);
    for (const v of ["0", "true", "yes", " 1"]) {
      process.env.NEXT_PUBLIC_BILLING_ENABLED = v;
      expect(isBillingEnabled()).toBe(false);
    }
  });

  it("prefers the runtime window.__ENV value over the build-time one", () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    window.__ENV = { NEXT_PUBLIC_BILLING_ENABLED: "0" };
    expect(isBillingEnabled()).toBe(false);
    window.__ENV = { NEXT_PUBLIC_BILLING_ENABLED: "1" };
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    expect(isBillingEnabled()).toBe(true);
  });
});

describe("a personal install has no billing whatever NEXT_PUBLIC_BILLING_ENABLED says", () => {
  it("is OFF for personal even with the variable set to 1, and drops every plans/billing row and link", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    vi.stubEnv("NEXT_PUBLIC_BILLING_ENABLED", "1");
    expect(isBillingEnabled()).toBe(false);
    expect(filterBillingItems([{ href: "/features" }, { href: "/admin/billing" }, { href: "/usage" }])).toEqual([
      { href: "/usage" },
    ]);
    expect(showUpgradeLink("/admin/billing")).toBe(false);
    expect(llmSpendHref()).toBe("/usage#llm-usage");
  });

  it("is also OFF for personal by local auth alone", () => {
    vi.stubEnv("NEXT_PUBLIC_AUTH_MODE", "local");
    vi.stubEnv("NEXT_PUBLIC_BILLING_ENABLED", "1");
    expect(isBillingEnabled()).toBe(false);
  });

  it("still follows the variable on lite, full and an undeclared install", () => {
    for (const e of ["lite", "full", ""]) {
      vi.stubEnv("NEXT_PUBLIC_EDITION", e);
      vi.stubEnv("NEXT_PUBLIC_BILLING_ENABLED", "1");
      expect(isBillingEnabled(), e).toBe(true);
      vi.stubEnv("NEXT_PUBLIC_BILLING_ENABLED", "");
      expect(isBillingEnabled(), e).toBe(false);
    }
  });
});

describe("route helpers", () => {
  it("recognises billing routes (locale prefix already stripped)", () => {
    for (const p of ["/billing", "/billing/checkout", "/admin/billing", "/features"]) expect(isBillingRoute(p)).toBe(true);
    for (const p of ["/", "/usage", "/billingx", "/admin", "/featuresque"]) expect(isBillingRoute(p)).toBe(false);
    expect(isBillingNavHref("/admin/billing#llm-usage")).toBe(true);
    expect(isBillingNavHref("/billing?plan=PREMIUM")).toBe(true);
  });

  it("sends /admin/billing to /usage and everything else home", () => {
    expect(billingOffRedirect("/admin/billing")).toBe("/usage");
    expect(billingOffRedirect("/billing")).toBe("/");
    expect(billingOffRedirect("/billing/checkout")).toBe("/");
    expect(billingOffRedirect("/features")).toBe("/");
  });

  it("points the LLM spend view at the page that has it", () => {
    expect(llmSpendHref(true)).toBe("/admin/billing#llm-usage");
    expect(llmSpendHref(false)).toBe("/usage#llm-usage");
  });

  it("keeps non-billing upgrade-lock links (e.g. Connect -> /providers) when off", () => {
    expect(showUpgradeLink("/admin/billing", false)).toBe(false);
    expect(showUpgradeLink("/providers", false)).toBe(true);
    expect(showUpgradeLink("/admin/billing", true)).toBe(true);
  });
});

describe("nav filtering", () => {
  const sections = [
    { label: "Build", items: [{ href: "/agents" }, { href: "/workflows" }] },
    { label: "Plans", items: [{ href: "/features" }, { href: "/usage" }, { href: "/admin/billing" }] },
    { label: "Money", items: [{ href: "/billing" }] },
  ];

  it("drops plans/billing rows and emptied sections when off", () => {
    const out = filterBillingNav(sections, false);
    expect(out.map((s) => s.label)).toEqual(["Build", "Plans"]);
    expect(out[1].items.map((i) => i.href)).toEqual(["/usage"]);
  });

  it("returns the blueprint unchanged when on", () => {
    expect(filterBillingNav(sections, true)).toEqual(sections);
  });

  it("uses the live flag by default", () => {
    expect(filterBillingNav(sections).flatMap((s) => s.items.map((i) => i.href))).not.toContain("/admin/billing");
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    expect(filterBillingNav(sections).flatMap((s) => s.items.map((i) => i.href))).toContain("/admin/billing");
  });

  it("filters flat link lists, keeping href-less items", () => {
    const links = [{ label: "Docs", href: "/docs" }, { label: "Pricing", href: "/billing" }, { label: "Contact" }];
    expect(filterBillingItems(links, false).map((l) => l.label)).toEqual(["Docs", "Contact"]);
    expect(filterBillingItems(links, true)).toHaveLength(3);
  });
});
