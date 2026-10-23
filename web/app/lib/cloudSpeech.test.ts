import { afterEach, describe, expect, it, vi } from "vitest";
import { cloudSpeechOffered } from "./cloudSpeech";
import { refreshPlatformStatus, resetPlatformStatusForTests } from "./platformStatus";

const statusWith = (tier: string, features: Record<string, boolean>) =>
  (async () => ({
    ok: true,
    json: async () => ({ edition: { tier, billingEnabled: false, authMode: "local", features } }),
  })) as unknown as typeof fetch;

afterEach(() => {
  vi.unstubAllEnvs();
  resetPlatformStatusForTests();
});

describe("cloudSpeechOffered", () => {
  it("follows the edition seed until core has reported anything", () => {
    expect(cloudSpeechOffered()).toBe(true); // undeclared
    for (const e of ["lite", "full"]) {
      vi.stubEnv("NEXT_PUBLIC_EDITION", e);
      expect(cloudSpeechOffered(), e).toBe(true);
    }
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    expect(cloudSpeechOffered()).toBe(false);
  });

  it("uses the feature flag core reported over the personal seed (the cloud-speech escape hatch)", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    await refreshPlatformStatus(statusWith("personal", { "cloud-speech": true }), { force: true });
    expect(cloudSpeechOffered()).toBe(true);
  });

  it("keeps it off when core reports the flag off, even on a seed that would offer it", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    await refreshPlatformStatus(statusWith("full", { "cloud-speech": false }), { force: true });
    expect(cloudSpeechOffered()).toBe(false);
  });

  it("falls back to the seed when the reported features do not include the flag", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    await refreshPlatformStatus(statusWith("personal", { "billing-payments": false }), { force: true });
    expect(cloudSpeechOffered()).toBe(false);
  });
});
