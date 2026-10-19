// Agent Lab HTTP seam: the call-count formula the UI shows before a run (it
// must match the server's), and the exact path / method / options each
// labApi method sends. apiFetch is mocked at the seam - no network.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
// Statuses each call asked toJsonQuietOn to keep quiet, in call order.
const quietCalls = vi.hoisted(() => [] as number[][]);
vi.mock("../../../lib/api/core", () => ({
  apiFetch: apiFetchMock,
  toJson: async (res: Response) => res.json(),
  toJsonQuietOn: (statuses: number[]) => {
    quietCalls.push(statuses);
    return async (res: Response) => {
      if (!res.ok) {
        const err = new Error(`HTTP ${res.status}`) as Error & { status?: number };
        err.status = res.status;
        throw err;
      }
      return res.json();
    };
  },
  jsonBody: (payload: unknown) => ({
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  }),
}));

import { REWIND_PROBE_KEEP, labApi, plannedCalls, type LabCapabilities, type LabCellRequest, type LabRunRequest } from "./labApi";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function cells(n: number): LabCellRequest[] {
  return Array.from({ length: n }, (_, i): LabCellRequest => ({ key: `k${i}`, promptIndex: null, message: "q" }));
}

beforeEach(() => {
  apiFetchMock.mockReset();
  quietCalls.length = 0;
});

describe("plannedCalls", () => {
  it("counts one call per cell with no judge", () => {
    expect(plannedCalls({ cells: cells(5), scoring: "NONE", similarity: "NONE" })).toBe(5);
  });

  it("doubles the cells when every answer is judged", () => {
    expect(plannedCalls({ cells: cells(30), scoring: "JUDGE", similarity: "NONE" })).toBe(60);
  });

  it("adds one judge comparison per pair for similarity", () => {
    // 5 samples + C(5,2) = 10 comparisons.
    expect(plannedCalls({ cells: cells(5), scoring: "NONE", similarity: "JUDGE" })).toBe(15);
    // 8 samples + 28 comparisons.
    expect(plannedCalls({ cells: cells(8), scoring: "NONE", similarity: "JUDGE" })).toBe(36);
    expect(plannedCalls({ cells: cells(4), scoring: "JUDGE", similarity: "JUDGE" })).toBe(14);
  });

  it("is zero for no cells and has no comparison for a single cell", () => {
    expect(plannedCalls({ cells: [], scoring: "JUDGE", similarity: "JUDGE" })).toBe(0);
    expect(plannedCalls({ cells: cells(1), scoring: "NONE", similarity: "JUDGE" })).toBe(1);
  });
});

describe("labApi.getLabCapabilities", () => {
  const capabilities: LabCapabilities = {
    allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60,
  };

  it("GETs the capabilities route, quiet on 403 and 404", async () => {
    apiFetchMock.mockResolvedValue(json(capabilities));
    await expect(labApi.getLabCapabilities("agent-1")).resolves.toEqual(capabilities);
    expect(apiFetchMock).toHaveBeenCalledTimes(1);
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/agents/agent-1/lab/capabilities");
    expect(apiFetchMock.mock.calls[0][1]?.method).toBeUndefined();
    expect(quietCalls).toEqual([[403, 404]]);
  });

  it("returns a plan-locked body as it is (allowed false is still an answer)", async () => {
    apiFetchMock.mockResolvedValue(json({ ...capabilities, allowed: false, reason: "PLAN" }));
    await expect(labApi.getLabCapabilities("agent-1")).resolves.toMatchObject({ allowed: false, reason: "PLAN" });
  });

  it("returns null when the fetch rejects", async () => {
    apiFetchMock.mockRejectedValue(new Error("network down"));
    await expect(labApi.getLabCapabilities("agent-1")).resolves.toBeNull();
  });

  it("returns null when the server answers an error status", async () => {
    apiFetchMock.mockResolvedValue(json({ error: "Not found" }, 404));
    await expect(labApi.getLabCapabilities("agent-1")).resolves.toBeNull();
  });

  it("returns null when the body has no boolean `allowed`", async () => {
    apiFetchMock.mockResolvedValue(json({}));
    await expect(labApi.getLabCapabilities("agent-1")).resolves.toBeNull();
    apiFetchMock.mockResolvedValue(json({ allowed: "yes" }));
    await expect(labApi.getLabCapabilities("agent-1")).resolves.toBeNull();
    apiFetchMock.mockResolvedValue(json(null));
    await expect(labApi.getLabCapabilities("agent-1")).resolves.toBeNull();
  });
});

describe("labApi run routes", () => {
  const request: LabRunRequest = {
    kind: "CONSISTENCY", systemPrompts: [], cells: cells(3), scoring: "NONE", similarity: "JUDGE",
  };

  it("startLabRun POSTs the request as JSON, quiet on 403/409/429/503", async () => {
    apiFetchMock.mockResolvedValue(json({ runId: "run-1", status: "QUEUED" }, 202));
    await expect(labApi.startLabRun("agent-1", request)).resolves.toMatchObject({ runId: "run-1" });
    const [path, options] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/agents/agent-1/lab/runs");
    expect(options.method).toBe("POST");
    expect(options.headers).toEqual({ "Content-Type": "application/json" });
    expect(JSON.parse(options.body)).toEqual(request);
    expect(quietCalls).toEqual([[403, 409, 429, 503]]);
  });

  it("startLabRun throws with the status when the server refuses", async () => {
    apiFetchMock.mockResolvedValue(json({ error: "You already have a lab run in progress." }, 409));
    await expect(labApi.startLabRun("agent-1", request)).rejects.toMatchObject({ status: 409 });
  });

  it("getLabRun GETs the run past the GET cache, quiet on 404", async () => {
    apiFetchMock.mockResolvedValue(json({ runId: "run-1", status: "RUNNING" }));
    await expect(labApi.getLabRun("agent-1", "run-1")).resolves.toMatchObject({ status: "RUNNING" });
    const [path, options] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/agents/agent-1/lab/runs/run-1");
    expect(options).toEqual({ skipGetCache: true });
    expect(quietCalls).toEqual([[404]]);
  });

  it("getLabRun throws a 404 rather than returning null", async () => {
    apiFetchMock.mockResolvedValue(json({ error: "Not found" }, 404));
    await expect(labApi.getLabRun("agent-1", "gone")).rejects.toMatchObject({ status: 404 });
  });

  it("cancelLabRun POSTs to the cancel route, quiet on 404", async () => {
    apiFetchMock.mockResolvedValue(json({ runId: "run-1", cancelRequested: true }, 202));
    await expect(labApi.cancelLabRun("agent-1", "run-1")).resolves.toEqual({ runId: "run-1", cancelRequested: true });
    const [path, options] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/agents/agent-1/lab/runs/run-1/cancel");
    expect(options.method).toBe("POST");
    expect(quietCalls).toEqual([[404]]);
  });
});

describe("labApi session rewind", () => {
  it("rewindChatSession POSTs { keepTurns } with the session id URL-encoded", async () => {
    apiFetchMock.mockResolvedValue(json({ sessionId: "s/1 x", keepTurns: 2, droppedTurns: 4 }));
    await expect(labApi.rewindChatSession("agent-1", "s/1 x", 2)).resolves.toEqual({
      sessionId: "s/1 x", keepTurns: 2, droppedTurns: 4,
    });
    const [path, options] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/agents/agent-1/chat/sessions/s%2F1%20x/rewind");
    expect(options.method).toBe("POST");
    expect(JSON.parse(options.body)).toEqual({ keepTurns: 2 });
    expect(quietCalls).toEqual([[404]]);
  });

  it("probeSessionEntryCount sends the probe value and returns the stored-entry count", async () => {
    apiFetchMock.mockResolvedValue(json({ sessionId: "sess-1", keepTurns: 6, droppedTurns: 0 }));
    await expect(labApi.probeSessionEntryCount("agent-1", "sess-1")).resolves.toBe(6);
    const [path, options] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/agents/agent-1/chat/sessions/sess-1/rewind");
    expect(JSON.parse(options.body)).toEqual({ keepTurns: 2147483647 });
    expect(REWIND_PROBE_KEEP).toBe(2147483647);
  });

  it("probeSessionEntryCount throws when the session is gone", async () => {
    apiFetchMock.mockResolvedValue(json({ error: "Not found" }, 404));
    await expect(labApi.probeSessionEntryCount("agent-1", "sess-1")).rejects.toMatchObject({ status: 404 });
  });
});
