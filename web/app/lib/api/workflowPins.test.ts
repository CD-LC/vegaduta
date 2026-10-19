import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
}));

import { classifyPinFailure, deletePin, listPins, optimisticPin, putPin } from "./workflowPins";

const json = (status: number, body: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => apiFetchMock.mockReset());

describe("classifyPinFailure", () => {
  it("tells the three documented 400s apart by message", () => {
    expect(classifyPinFailure(400, "Pin data exceeds 256000 characters")).toBe("too-large");
    expect(classifyPinFailure(400, "A workflow can have at most 30 pins")).toBe("too-many");
    expect(classifyPinFailure(400, "Total pinned data would exceed 2000000 characters")).toBe("total-size");
    expect(classifyPinFailure(400, "something else")).toBe("invalid");
    expect(classifyPinFailure(400, "")).toBe("invalid");
  });
  it("maps the other statuses", () => {
    expect(classifyPinFailure(403, "")).toBe("forbidden");
    expect(classifyPinFailure(401, "")).toBe("forbidden");
    expect(classifyPinFailure(404, "")).toBe("not-found");
    expect(classifyPinFailure(501, "")).toBe("unsupported");
    expect(classifyPinFailure(500, "boom")).toBe("error");
  });
});

describe("listPins", () => {
  it("GETs the pins route uncached and normalises rows", async () => {
    apiFetchMock.mockResolvedValue(
      json(200, [
        { nodeId: "n1", source: "test", sourceRunId: null, pinnedBy: "u", pinnedAt: "2026-09-30T00:00:00Z", sizeChars: 5, configHash: "h", data: "{}" },
        { nodeId: "n2", source: "weird", pinnedBy: "u", pinnedAt: "t", data: null },
        { nope: true },
      ]),
    );
    const r = await listPins("wf 1");
    expect(apiFetchMock).toHaveBeenCalledWith("/api/workflows/wf%201/pins", { skipGetCache: true });
    expect(r.ok).toBe(true);
    expect(r.data).toHaveLength(2);
    expect(r.data[0]).toMatchObject({ nodeId: "n1", source: "test", configHash: "h", data: "{}" });
    expect(r.data[1]).toMatchObject({ nodeId: "n2", source: "manual", data: null, sizeChars: 0 });
  });
  it("reports a missing route as unsupported and a network error as network", async () => {
    apiFetchMock.mockResolvedValueOnce(json(404, { error: "x" }));
    expect(await listPins("wf")).toMatchObject({ ok: false, kind: "unsupported" });
    apiFetchMock.mockResolvedValueOnce(new Response("<html>", { status: 200 }));
    expect(await listPins("wf")).toMatchObject({ ok: false, kind: "unsupported" });
    apiFetchMock.mockResolvedValueOnce(json(200, { not: "an array" }));
    expect(await listPins("wf")).toMatchObject({ ok: false, kind: "unsupported" });
    apiFetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    expect(await listPins("wf")).toMatchObject({ ok: false, kind: "network" });
  });
});

describe("putPin", () => {
  it("PUTs the contract body and returns the server's pin", async () => {
    apiFetchMock.mockResolvedValue(
      json(200, { nodeId: "n1", source: "run", sourceRunId: "r1", pinnedBy: "u", pinnedAt: "t", sizeChars: 2, configHash: null, data: "{}" }),
    );
    const r = await putPin("wf", "n1", { data: "{}", source: "run", sourceRunId: "r1" });
    const [path, init] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/workflows/wf/nodes/n1/pin");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body)).toEqual({ data: "{}", source: "run", sourceRunId: "r1" });
    expect(r.ok).toBe(true);
    expect(r.data).toMatchObject({ nodeId: "n1", sourceRunId: "r1" });
  });
  it("surfaces the server message and classifies a 400", async () => {
    apiFetchMock.mockResolvedValue(json(400, { message: "Workflow already has 30 pins" }));
    const r = await putPin("wf", "n1", { data: "{}", source: "manual" });
    expect(r).toMatchObject({ ok: false, kind: "too-many", message: "Workflow already has 30 pins", status: 400 });
  });
  it("falls back to the sent text when the server replies 2xx with no body", async () => {
    apiFetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const r = await putPin("wf", "n1", { data: '{"a":1}', source: "manual" });
    expect(r.ok).toBe(true);
    expect(r.data).toMatchObject({ nodeId: "n1", data: '{"a":1}', sizeChars: 7 });
  });
});

describe("deletePin", () => {
  it("treats 204 and 404 as success, other failures as errors", async () => {
    apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    expect((await deletePin("wf", "n1")).ok).toBe(true);
    expect(apiFetchMock.mock.calls[0][1].method).toBe("DELETE");
    apiFetchMock.mockResolvedValueOnce(json(404, {}));
    expect((await deletePin("wf", "n1")).ok).toBe(true);
    apiFetchMock.mockResolvedValueOnce(json(403, { error: "no" }));
    expect(await deletePin("wf", "n1")).toMatchObject({ ok: false, kind: "forbidden" });
  });
});

describe("optimisticPin", () => {
  it("mirrors the request", () => {
    expect(optimisticPin("n", { data: "abc", source: "test" }, "me")).toMatchObject({
      nodeId: "n",
      source: "test",
      sizeChars: 3,
      data: "abc",
      pinnedBy: "me",
      sourceRunId: null,
    });
  });
});

describe("older core degradation (pins)", () => {
  it("maps a bare 400 or generic 404 on PUT to unsupported, but keeps core's own 404 reason", async () => {
    apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 400 }));
    expect(await putPin("wf", "n1", { data: "{}", source: "manual" })).toMatchObject({ ok: false, kind: "unsupported" });
    apiFetchMock.mockResolvedValueOnce(json(404, { error: "Not Found" }));
    expect(await putPin("wf", "n1", { data: "{}", source: "manual" })).toMatchObject({ ok: false, kind: "unsupported" });
    apiFetchMock.mockResolvedValueOnce(json(404, { error: "Node 'n1' is not in this workflow's saved graph - save the canvas first." }));
    expect(await putPin("wf", "n1", { data: "{}", source: "manual" })).toMatchObject({ ok: false, kind: "not-found" });
  });
});
