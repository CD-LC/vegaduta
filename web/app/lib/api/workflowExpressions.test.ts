import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
const apiFetchOnceMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  apiFetchOnce: (...args: unknown[]) => apiFetchOnceMock(...args),
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
}));

import { normalizeDiagnostics, workflowExpressionsApi } from "./workflowExpressions";

const json = (status: number, body: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  apiFetchMock.mockReset();
  apiFetchOnceMock.mockReset();
});

describe("function registry route", () => {
  it("GETs /api/workflow-expression-functions (not under /api/workflows/)", async () => {
    apiFetchMock.mockResolvedValue(json(200, [{ name: "upper" }]));
    const r = await workflowExpressionsApi.getExpressionFunctions();
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflow-expression-functions");
    expect(r).toMatchObject({ ok: true });
  });
});

describe("older core degradation", () => {
  it("treats 404 and a bare 400 as unsupported", async () => {
    apiFetchOnceMock.mockResolvedValueOnce(json(404, { error: "Not Found" }));
    expect(await workflowExpressionsApi.evaluateExpression("wf", { template: "{{1}}", dataSource: "pinned" })).toEqual({
      ok: false,
      unsupported: true,
    });
    apiFetchOnceMock.mockResolvedValueOnce(new Response(null, { status: 400 }));
    expect(await workflowExpressionsApi.lintExpressions("wf", [])).toEqual({ ok: false, unsupported: true });
  });
  it("does not treat a 404 that explains itself as a missing route", async () => {
    apiFetchMock.mockResolvedValueOnce(
      json(404, { error: "Node 'n7' is not in this workflow's saved graph - save the canvas first." }),
    );
    expect(await workflowExpressionsApi.getUpstreamSchema("wf", "n7")).toEqual({ ok: false, unsupported: false });
    apiFetchMock.mockResolvedValueOnce(json(404, { error: "No endpoint matches this request." }));
    expect(await workflowExpressionsApi.getUpstreamSchema("wf", "n7")).toEqual({ ok: false, unsupported: true });
    apiFetchMock.mockResolvedValueOnce(new Response(null, { status: 404 }));
    expect(await workflowExpressionsApi.getUpstreamSchema("wf", "n7")).toEqual({ ok: false, unsupported: true });
  });
  it("keeps a 400 that carries a reason as a plain failure", async () => {
    apiFetchOnceMock.mockResolvedValueOnce(json(400, { error: "template too long" }));
    expect(await workflowExpressionsApi.lintExpressions("wf", [])).toEqual({ ok: false, unsupported: false });
  });
});

describe("normalizeDiagnostics", () => {
  it("lower-cases severity, folds AMBIGUOUS_NODE and drops malformed rows", () => {
    const out = normalizeDiagnostics([
      { nodeId: "a", field: "url", severity: "ERROR", code: "AMBIGUOUS_NODE", message: "m", start: 1, end: 4 },
      { nodeId: "a", field: "url", severity: "warning", code: "STALE_PIN", message: "p", start: -2, end: 3 },
      { field: "x" },
      null,
    ]);
    expect(out).toHaveLength(2);
    expect(out[0]).toMatchObject({ severity: "error", code: "AMBIGUOUS_LABEL" });
    expect(out[1]).toMatchObject({ severity: "warning", code: "STALE_PIN", start: 0, end: 3 });
    expect(normalizeDiagnostics(undefined)).toEqual([]);
  });
});
