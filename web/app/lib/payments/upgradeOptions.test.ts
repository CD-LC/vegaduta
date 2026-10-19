import { beforeEach, describe, expect, it, vi } from "vitest";

const state = vi.hoisted(() => ({ authenticated: false, calls: 0 }));

vi.mock("../auth", () => ({ auth: { isAuthenticated: () => state.authenticated } }));
vi.mock("../api", () => ({
  api: {
    getUpgradeOptions: () => {
      state.calls += 1;
      return Promise.resolve({ billingEnabled: true, gateway: "razorpay", currencies: ["INR", "USD"], annualCurrencies: ["INR"] });
    },
  },
}));

describe("loadUpgradeOptions", () => {
  beforeEach(() => {
    vi.resetModules();
    state.authenticated = false;
    state.calls = 0;
  });

  it("does not call the authenticated endpoint for a signed-out visitor", async () => {
    const { loadUpgradeOptions } = await import("./upgradeOptions");
    expect(await loadUpgradeOptions()).toBeNull();
    expect(state.calls).toBe(0);
  });

  it("does not cache the signed-out skip: a later signed-in caller still asks core", async () => {
    const { loadUpgradeOptions } = await import("./upgradeOptions");
    await loadUpgradeOptions();
    state.authenticated = true;
    const options = await loadUpgradeOptions();
    expect(state.calls).toBe(1);
    expect(options?.currencies).toEqual(["INR", "USD"]);
  });

  it("asks core once per page load when signed in", async () => {
    state.authenticated = true;
    const { loadUpgradeOptions } = await import("./upgradeOptions");
    await loadUpgradeOptions();
    await loadUpgradeOptions();
    expect(state.calls).toBe(1);
  });
});
