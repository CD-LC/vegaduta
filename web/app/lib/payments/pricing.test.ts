import { describe, expect, it } from "vitest";
import { formatMoney, planPrice, PRICE_TABLE, resolveCurrency } from "./pricing";

describe("planPrice", () => {
  it("formats each currency with its own symbol and grouping", () => {
    expect(planPrice("PREMIUM", "INR", "MONTHLY")).toBe("₹399/mo");
    expect(planPrice("PREMIUM", "USD", "MONTHLY")).toBe("$19/mo");
    expect(planPrice("UNLIMITED", "INR", "ANNUAL")).toBe("₹15,999/yr");
    expect(planPrice("UNLIMITED", "USD", "ANNUAL")).toBe("$790/yr");
  });
  it("keeps annual at ten times monthly (2 months free) in USD", () => {
    for (const tier of ["PREMIUM", "UNLIMITED"] as const) {
      expect(PRICE_TABLE[tier].USD.ANNUAL).toBe(PRICE_TABLE[tier].USD.MONTHLY * 10);
    }
  });
  it("formats rupees with Indian digit grouping", () => {
    expect(formatMoney(150000, "INR")).toBe("₹1,50,000");
  });
});

describe("resolveCurrency", () => {
  it("uses the detected currency when there is no override", () => {
    expect(resolveCurrency("INR", null, null)).toBe("INR");
    expect(resolveCurrency("USD", null, null)).toBe("USD");
  });

  it("lets the customer's explicit choice win over a wrong detection", () => {
    // Detected India (e.g. a VPN exit) but the customer pays in dollars.
    expect(resolveCurrency("INR", "USD", ["INR", "USD"])).toBe("USD");
  });

  it("never selects a currency that cannot be billed", () => {
    // International customer, but only INR plans are wired: show rupees rather than a checkout that cannot start.
    expect(resolveCurrency("USD", null, ["INR"])).toBe("INR");
    // A stale override for a currency that is no longer available is ignored.
    expect(resolveCurrency("INR", "USD", ["INR"])).toBe("INR");
  });

  it("trusts the first choice while availability is unknown or empty", () => {
    expect(resolveCurrency("USD", null, null)).toBe("USD");
    expect(resolveCurrency("INR", "USD", [])).toBe("USD");
  });
});
