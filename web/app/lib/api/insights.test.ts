import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
const quietOn = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  toJson: async (res: Response) => res.json(),
  toJsonQuietOn: (statuses: number[]) => {
    quietOn(statuses);
    return async (res: Response) => res.json();
  },
}));

import { insightsApi, isForbidden } from "./insights";

const ok = (body: unknown = {}) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  apiFetchMock.mockReset();
  apiFetchMock.mockImplementation(async () => ok());
  quietOn.mockClear();
});

describe("insightsApi spend reports", () => {
  it("reads each breakdown from /api/admin/spend-reports with the from/to range", async () => {
    const range = { from: "2026-09-29", to: "2026-10-05" };
    const query = "?from=2026-09-29&to=2026-10-05";
    await insightsApi.getSpendSummary(range);
    expect(apiFetchMock).toHaveBeenLastCalledWith(`/api/admin/spend-reports/summary${query}`);
    await insightsApi.getSpendByDay(range);
    expect(apiFetchMock).toHaveBeenLastCalledWith(`/api/admin/spend-reports/by-day${query}`);
    await insightsApi.getSpendByModel(range);
    expect(apiFetchMock).toHaveBeenLastCalledWith(`/api/admin/spend-reports/by-model${query}`);
    await insightsApi.getSpendByProvider(range);
    expect(apiFetchMock).toHaveBeenLastCalledWith(`/api/admin/spend-reports/by-provider${query}`);
    await insightsApi.getSpendByAgent(range);
    expect(apiFetchMock).toHaveBeenLastCalledWith(`/api/admin/spend-reports/by-agent${query}`);
    await insightsApi.getSpendByModality(range);
    expect(apiFetchMock).toHaveBeenLastCalledWith(`/api/admin/spend-reports/by-modality${query}`);
  });

  it("omits the query entirely without a range, so the server default applies", async () => {
    await insightsApi.getSpendSummary();
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/admin/spend-reports/summary");
  });

  it("treats 403 as a quiet, expected state (no global toast)", async () => {
    await insightsApi.getSpendSummary();
    expect(quietOn).toHaveBeenCalledWith([403]);
  });
});

describe("insightsApi job counts", () => {
  it("calls /api/agent-jobs/counts bare by default", async () => {
    await insightsApi.getAgentJobCounts();
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/agent-jobs/counts");
  });

  it("passes the window and the agent filter as query parameters", async () => {
    await insightsApi.getAgentJobCounts({ window: "P7D" });
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/agent-jobs/counts?window=P7D");
    await insightsApi.getAgentJobCounts({ agentId: "a 1", window: "PT24H" });
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/agent-jobs/counts?agentId=a+1&window=PT24H");
  });
});

describe("isForbidden", () => {
  it("is true only for an error carrying status 403", () => {
    expect(isForbidden(Object.assign(new Error("no"), { status: 403 }))).toBe(true);
    expect(isForbidden(Object.assign(new Error("boom"), { status: 500 }))).toBe(false);
    expect(isForbidden(new Error("network"))).toBe(false);
    expect(isForbidden(null)).toBe(false);
    expect(isForbidden("403")).toBe(false);
  });
});
