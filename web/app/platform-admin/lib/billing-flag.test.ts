import { afterEach, describe, expect, it } from "vitest";
import { filterBillingNav, isBillingEnabled, isBillingRoute } from "./billing-flag";

afterEach(() => {
  delete window.__ENV;
  delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
});

describe("web-admin billing flag", () => {
  it("defaults to OFF and is ON only for \"1\" (runtime value wins)", () => {
    expect(isBillingEnabled()).toBe(false);
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    expect(isBillingEnabled()).toBe(true);
    window.__ENV = { NEXT_PUBLIC_BILLING_ENABLED: "0" };
    expect(isBillingEnabled()).toBe(false);
  });

  it("matches only the billing admin route", () => {
    expect(isBillingRoute("/platform-admin/billing")).toBe(true);
    expect(isBillingRoute("/platform-admin/billing?x=1")).toBe(true);
    expect(isBillingRoute("/platform-admin/spend")).toBe(false);
    expect(isBillingRoute("/platform-admin/budgets")).toBe(false);
  });

  const sections = [
    { label: null, items: [{ href: "/" }] },
    { label: "Money", items: [{ href: "/platform-admin/budgets" }, { href: "/platform-admin/spend" }, { href: "/platform-admin/billing" }] },
    { label: "Only billing", items: [{ href: "/platform-admin/billing" }] },
  ];

  it("hides Billing & Subscriptions but keeps spend/budgets when off", () => {
    const out = filterBillingNav(sections, false);
    expect(out.map((s) => s.label)).toEqual([null, "Money"]);
    expect(out[1].items.map((i) => i.href)).toEqual(["/platform-admin/budgets", "/platform-admin/spend"]);
  });

  it("is a no-op when on", () => {
    expect(filterBillingNav(sections, true)).toEqual(sections);
  });
});
