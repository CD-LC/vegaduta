import { afterEach, describe, expect, it, vi } from "vitest";
import {
  IMPLEMENTED_PROVIDERS,
  MULTI_FIELD_PROVIDERS,
  PROVIDERS,
  PROVIDER_INFO,
  encodeMultiFieldCredential,
  encodeVertexCredential,
  formatActivatedDate,
  offeredImplementedProviders,
  offeredProviders,
} from "./providerCatalog";

describe("providerCatalog", () => {
  it("has card info for every provider and marks them implemented", () => {
    for (const name of PROVIDERS) {
      expect(PROVIDER_INFO[name].labelKey).toBeTruthy();
      expect(IMPLEMENTED_PROVIDERS.has(name)).toBe(true);
    }
  });

  describe("offered cards by edition (CLOUD_SPEECH)", () => {
    afterEach(() => {
      vi.unstubAllEnvs();
      delete window.__ENV;
    });

    it("drops the Sarvam and ElevenLabs cards on a personal install and nothing else", () => {
      vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
      const cards = offeredProviders();
      expect(cards).not.toContain("SARVAM");
      expect(cards).not.toContain("ELEVENLABS");
      expect(cards).toEqual(PROVIDERS.filter((n) => n !== "SARVAM" && n !== "ELEVENLABS"));
      expect(cards).toContain("E2B");
      const implemented = offeredImplementedProviders();
      expect(implemented.has("SARVAM")).toBe(false);
      expect(implemented.has("ELEVENLABS")).toBe(false);
      for (const n of cards) expect(implemented.has(n)).toBe(true);
    });

    it.each(["lite", "full"])("keeps every card on the %s edition", (tier) => {
      vi.stubEnv("NEXT_PUBLIC_EDITION", tier);
      expect(offeredProviders()).toEqual(PROVIDERS);
      expect(offeredImplementedProviders()).toEqual(IMPLEMENTED_PROVIDERS);
    });

    it("keeps every card on an undeclared build (every feature on)", () => {
      expect(offeredProviders()).toEqual(PROVIDERS);
    });
  });

  it("omits blank optional multi-field values", () => {
    const fields = MULTI_FIELD_PROVIDERS.AZURE_OPENAI!;
    const json = encodeMultiFieldCredential(fields, {
      endpoint: " https://x ",
      apiKey: "k",
      deploymentName: "d",
      apiVersion: " ",
    });
    expect(JSON.parse(json)).toEqual({ endpoint: "https://x", apiKey: "k", deploymentName: "d" });
  });

  it("encodes the Vertex credential with a base64 service-account key", () => {
    const parsed = JSON.parse(encodeVertexCredential("p", "us-central1", '{"a":1}'));
    expect(parsed.projectId).toBe("p");
    expect(atob(parsed.serviceAccountKeyBase64)).toBe('{"a":1}');
  });

  it("never prints Invalid Date", () => {
    expect(formatActivatedDate(undefined)).toBeNull();
    expect(formatActivatedDate("nope")).toBeNull();
    expect(formatActivatedDate("2026-01-02T00:00:00Z")).toBeTruthy();
  });
});
