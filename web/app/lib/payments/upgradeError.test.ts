import { describe, expect, it } from "vitest";
import { describeUpgradeError } from "./upgradeError";

describe("describeUpgradeError", () => {
  it("translates a browser network failure and says nothing was charged", () => {
    const v = describeUpgradeError(new Error("Load failed"));
    expect(v.title).toMatch(/could not reach/i);
    expect(v.detail).toMatch(/not been charged/i);
    expect(v.retryable).toBe(true);
  });
  it("sends a 401 to sign-in and a 403 to an owner", () => {
    expect(describeUpgradeError({ status: 401, message: "x" }).title).toMatch(/sign in/i);
    expect(describeUpgradeError({ status: 403, message: "x" }).title).toMatch(/owner or admin/i);
  });
  it("explains a missing-currency plan", () => {
    expect(describeUpgradeError({ status: 500, message: "No Razorpay Plan id is configured for PREMIUM USD/ANNUAL" }).title).toMatch(/currency/i);
  });
  it("replaces the bare generic server message", () => {
    const v = describeUpgradeError({ status: 500, message: "Something went wrong. Please try again." });
    expect(v.detail).toMatch(/not been charged/i);
  });
  it("keeps a specific server message", () => {
    expect(describeUpgradeError({ status: 400, message: "Unknown coupon code 'X'" }).detail).toContain("Unknown coupon");
  });
});
