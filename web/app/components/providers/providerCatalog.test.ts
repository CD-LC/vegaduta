import { describe, expect, it } from "vitest";
import {
  IMPLEMENTED_PROVIDERS,
  MULTI_FIELD_PROVIDERS,
  PROVIDERS,
  PROVIDER_INFO,
  encodeMultiFieldCredential,
  encodeVertexCredential,
  formatActivatedDate,
} from "./providerCatalog";

describe("providerCatalog", () => {
  it("has card info for every provider and marks them implemented", () => {
    for (const name of PROVIDERS) {
      expect(PROVIDER_INFO[name].labelKey).toBeTruthy();
      expect(IMPLEMENTED_PROVIDERS.has(name)).toBe(true);
    }
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
