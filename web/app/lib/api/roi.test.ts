import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  toJson: async (res: Response) => res.json(),
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
  triggerBrowserDownload: vi.fn(),
}));

import { roiApi, roiQuery } from "./roi";

const ok = (body: unknown = {}) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  apiFetchMock.mockReset();
  apiFetchMock.mockImplementation(async () => ok());
});

describe("roiQuery", () => {
  it("omits everything for the server default and only sends includeAssumed when true", () => {
    expect(roiQuery()).toBe("");
    expect(roiQuery({ from: "2026-10-01", to: "2026-10-14" })).toBe("?from=2026-10-01&to=2026-10-14");
    expect(roiQuery({ includeAssumed: false })).toBe("");
    expect(roiQuery({ includeAssumed: true })).toBe("?includeAssumed=true");
  });
});

describe("roiApi", () => {
  it("reads the per-agent table, budget and prices from /api/tenant/roi", async () => {
    await roiApi.getAgents({ from: "2026-10-01", to: "2026-10-14", includeAssumed: true });
    await roiApi.getBudget();
    await roiApi.getPrices({ from: "2026-10-01", to: "2026-10-14", includeAssumed: true });
    expect(apiFetchMock.mock.calls.map((c) => c[0])).toEqual([
      "/api/tenant/roi/agents?from=2026-10-01&to=2026-10-14&includeAssumed=true",
      "/api/tenant/roi/budget",
      "/api/tenant/roi/prices?from=2026-10-01&to=2026-10-14",
    ]);
  });

  it("writes a price and an assumption as JSON, with the agent id encoded", async () => {
    await roiApi.setPrice({ providerName: "ANTHROPIC", modelPrefix: "claude", inputPerMillion: 3, outputPerMillion: 15 });
    await roiApi.setAssumption("a/b", { valuePerResolution: null, valueCurrency: null, minutesSavedPerResolution: 5 });
    const [price, assumption] = apiFetchMock.mock.calls;
    expect(price[0]).toBe("/api/tenant/roi/prices");
    expect(price[1].method).toBe("POST");
    expect(JSON.parse(price[1].body)).toMatchObject({ modelPrefix: "claude", inputPerMillion: 3 });
    expect(assumption[0]).toBe("/api/tenant/roi/agents/a%2Fb/assumptions");
    expect(assumption[1].method).toBe("PUT");
    // A blank value is sent as null, not defaulted to 0.
    expect(JSON.parse(assumption[1].body)).toEqual({ valuePerResolution: null, valueCurrency: null, minutesSavedPerResolution: 5 });
  });

  it("downloads the CSV under the server's file name and fails on a non-2xx", async () => {
    const core = await import("./core");
    apiFetchMock.mockImplementationOnce(async () => new Response("a,b", { status: 200, headers: { "Content-Disposition": 'attachment; filename="agent-roi-x.csv"' } }));
    await roiApi.downloadAgentsCsv({ from: "2026-10-01", to: "2026-10-14" });
    expect(core.triggerBrowserDownload).toHaveBeenCalledWith(expect.any(Blob), "agent-roi-x.csv");
    apiFetchMock.mockImplementationOnce(async () => new Response("no", { status: 500 }));
    await expect(roiApi.downloadAgentsCsv()).rejects.toThrow("HTTP 500");
  });
});
