import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

const verify = vi.fn();
vi.mock("../../lib/serverAuth", () => ({ verifyPlatformAdmin: (...a: any[]) => verify(...a) }));

import { GET } from "./route";
import { observabilityLinks } from "../../lib/observabilityLinks";

describe("GET /api/observability-links", () => {
  beforeEach(() => verify.mockReset());
  afterEach(() => vi.unstubAllEnvs());

  it("refuses unauthenticated / non-admin callers without leaking URLs", async () => {
    vi.stubEnv("GRAFANA_URL", "https://grafana.internal.example");
    verify.mockResolvedValue({ ok: false, status: 401, error: "Authentication required." });
    const res = await GET(new Request("http://admin.test/api/observability-links"));
    expect(res.status).toBe(401);
    const text = await res.text();
    expect(text).not.toContain("grafana.internal.example");
  });

  it("returns the configured links to a verified platform admin", async () => {
    vi.stubEnv("GRAFANA_URL", "https://grafana.internal.example");
    verify.mockResolvedValue({ ok: true, status: 200, error: "" });
    const res = await GET(
      new Request("http://admin.test/api/observability-links", { headers: { Authorization: "Bearer t" } }),
    );
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.grafana).toBe("https://grafana.internal.example");
    expect(Object.keys(body).sort()).toEqual(["grafana", "langfuse", "prometheus", "temporal"]);
  });
});

describe("observabilityLinks", () => {
  it("uses env values and falls back to dev defaults for unset/empty keys", () => {
    const links = observabilityLinks({ LANGFUSE_URL: "https://lf.example", PROMETHEUS_URL: "" });
    expect(links.langfuse).toBe("https://lf.example");
    expect(links.prometheus).toBe("http://localhost:9090");
  });
});