import { describe, it, expect } from "vitest";
import { resolveProviderGate, type ProviderGateInput } from "./providerGate";

// The "No active LLM provider yet" banner and the agent create-gate. Each case
// is a real tenant shape; the first one is the bug report.

const base: ProviderGateInput = {
  tenantPlan: "PREMIUM",
  tenantSettingsError: false,
  usePlatformLlmToggle: false,
  platformPoolAvailable: false,
  activeProviderCount: 0,
  routingTier: null,
};

describe("resolveProviderGate", () => {
  it("never warns a new signup on the complimentary grant (the reported bug)", () => {
    // Not FREE, never flipped the toggle, no BYOK key - but the server routes
    // them to the hosted pool, so their agents work.
    const g = resolveProviderGate({ ...base, platformPoolAvailable: true });
    expect(g.needsProvider).toBe(false);
    expect(g.onPlatformLlm).toBe(true);
  });

  it("never warns a FREE tenant, with or without an availability answer", () => {
    expect(resolveProviderGate({ ...base, tenantPlan: "FREE" }).needsProvider).toBe(false);
    expect(resolveProviderGate({ ...base, tenantPlan: "FREE", platformPoolAvailable: true }).needsProvider).toBe(false);
  });

  it("never warns a paid tenant who opted into the platform LLM", () => {
    expect(resolveProviderGate({ ...base, usePlatformLlmToggle: true }).needsProvider).toBe(false);
  });

  it("never warns while either answer is still loading - no flash at login", () => {
    expect(resolveProviderGate({ ...base, tenantPlan: null }).needsProvider).toBe(false);
    expect(resolveProviderGate({ ...base, platformPoolAvailable: null }).needsProvider).toBe(false);
    expect(resolveProviderGate({ ...base, tenantPlan: null, platformPoolAvailable: null }).settled).toBe(false);
  });

  it("does not warn when the next turn runs on-device", () => {
    expect(resolveProviderGate({ ...base, routingTier: 0 }).needsProvider).toBe(false);
    expect(resolveProviderGate({ ...base, routingTier: 1 }).needsProvider).toBe(false);
    // tier 3 is hosted/BYOK - it does not rescue a tenant with neither.
    expect(resolveProviderGate({ ...base, routingTier: 3 }).needsProvider).toBe(true);
  });

  it("does not warn a tenant with an active BYOK key", () => {
    expect(resolveProviderGate({ ...base, activeProviderCount: 1 }).needsProvider).toBe(false);
  });

  it("DOES warn the one tenant it is for: paid, no grant, no toggle, no key, no local model", () => {
    const g = resolveProviderGate(base);
    expect(g.settled).toBe(true);
    expect(g.needsProvider).toBe(true);
  });

  it("fails closed to FREE when tenant settings could not be loaded", () => {
    const g = resolveProviderGate({ ...base, tenantPlan: null, tenantSettingsError: true });
    expect(g.isFreePlan).toBe(true);
    expect(g.needsProvider).toBe(false);
  });
});
