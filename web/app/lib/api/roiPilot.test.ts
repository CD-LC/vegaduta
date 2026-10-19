import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
const downloadMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  toJson: async (res: Response) => res.json(),
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
  triggerBrowserDownload: (...args: unknown[]) => downloadMock(...args),
}));

import { reportUrl, roiPilotApi, type BaselineRequestDto } from "./roiPilot";

beforeEach(() => {
  apiFetchMock.mockReset();
  downloadMock.mockReset();
});

describe("reportUrl", () => {
  it("adds the format after the range and encodes the agent id", () => {
    expect(reportUrl("a/b", "pdf")).toBe("/api/tenant/roi/agents/a%2Fb/pilot-report?format=pdf");
    expect(reportUrl("x", "html", { from: "2026-09-01", to: "2026-09-30", includeAssumed: true })).toBe(
      "/api/tenant/roi/agents/x/pilot-report?from=2026-09-01&to=2026-09-30&includeAssumed=true&format=html",
    );
  });
});

describe("roiPilotApi", () => {
  it("saves a baseline with PUT and sends exactly what was typed", async () => {
    apiFetchMock.mockResolvedValue(new Response(JSON.stringify({ agentId: "x", source: "tenant-entered" }), { status: 200 }));
    const body: BaselineRequestDto = { jobType: "LEAD_FOLLOW_UP", baselinePeriod: null, monthlyVolume: 300, avgHandlingMinutes: null, missedRate: 0.2, avgValuePerConversion: null, valueCurrency: null };
    await roiPilotApi.setBaseline("x", body);
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/tenant/roi/agents/x/baseline");
    expect(apiFetchMock.mock.calls[0][1].method).toBe("PUT");
    expect(JSON.parse(apiFetchMock.mock.calls[0][1].body)).toEqual(body);
  });

  it("downloads the PDF under the server's file name and surfaces HTTP errors", async () => {
    apiFetchMock.mockResolvedValueOnce(new Response("%PDF-", { status: 200, headers: { "Content-Disposition": 'attachment; filename="pilot-report-a.pdf"' } }));
    await roiPilotApi.openReport("x", "pdf");
    expect(downloadMock.mock.calls[0][1]).toBe("pilot-report-a.pdf");
    apiFetchMock.mockResolvedValueOnce(new Response("no", { status: 404 }));
    await expect(roiPilotApi.openReport("x", "pdf")).rejects.toThrow("HTTP 404");
  });
});
