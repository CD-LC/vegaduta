import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  friendlyStatusFallback: (status: number) => `status ${status}`,
  toJsonQuietOn: () => (r: unknown) => r,
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
}));

import { filenameFromDisposition, workflowPortabilityApi } from "./workflowPortability";
import { forgetMissing } from "./negativeCache";

beforeEach(() => apiFetchMock.mockReset());

describe("filenameFromDisposition", () => {
  it("reads quoted, bare and RFC 5987 forms", () => {
    expect(filenameFromDisposition('attachment; filename="flow.vegaduta-workflow.json"', "x")).toBe("flow.vegaduta-workflow.json");
    expect(filenameFromDisposition("attachment; filename=flow.json", "x")).toBe("flow.json");
    expect(filenameFromDisposition("attachment; filename*=UTF-8''my%20flow.json", "x")).toBe("my flow.json");
  });

  it("falls back when the header is missing or has no name", () => {
    expect(filenameFromDisposition(null, "fallback.json")).toBe("fallback.json");
    expect(filenameFromDisposition("attachment", "fallback.json")).toBe("fallback.json");
  });

  it("keeps the raw value when the encoding is broken", () => {
    expect(filenameFromDisposition("attachment; filename*=UTF-8''100%.json", "x")).toBe("100%.json");
  });
});

describe("workflowPortabilityApi", () => {
  it("previews with the envelope as the request body and never a query string", async () => {
    apiFetchMock.mockResolvedValue({});
    const envelope = { source: "n8n" as const, payload: '{"nodes":[]}', mappings: { agent_1: "id-1" } };
    await workflowPortabilityApi.previewWorkflowImport(envelope);
    const [path, init] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/workflows/import/preview");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual(envelope);
  });

  it("creates through the import route", async () => {
    apiFetchMock.mockResolvedValue({});
    await workflowPortabilityApi.importWorkflowFile({ source: "auto", payload: "kind: app" });
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/import");
  });

  it("probes the feature switch with an empty envelope: 400 means on, 404 means off", async () => {
    apiFetchMock.mockResolvedValue({ status: 400 });
    await expect(workflowPortabilityApi.portabilityEnabled()).resolves.toBe(true);
    const [path, init] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/workflows/import/preview");
    expect(JSON.parse(init.body)).toEqual({});
    apiFetchMock.mockResolvedValue({ status: 404 });
    await expect(workflowPortabilityApi.portabilityEnabled()).resolves.toBe(false);
    forgetMissing("workflow-portability");
    apiFetchMock.mockImplementationOnce(() => Promise.reject(new Error("offline")));
    await expect(workflowPortabilityApi.portabilityEnabled()).resolves.toBe(false);
  });

  it("remembers a 404 for the session instead of POSTing again on the next load", async () => {
    forgetMissing("workflow-portability");
    apiFetchMock.mockResolvedValue({ status: 404 });
    await expect(workflowPortabilityApi.portabilityEnabled()).resolves.toBe(false);
    expect(apiFetchMock).toHaveBeenCalledTimes(1);
    await expect(workflowPortabilityApi.portabilityEnabled()).resolves.toBe(false);
    expect(apiFetchMock).toHaveBeenCalledTimes(1);
    forgetMissing("workflow-portability");
  });

  it("exports to a blob and the server's file name", async () => {
    const blob = new Blob(["{}"]);
    apiFetchMock.mockResolvedValue({
      ok: true,
      headers: { get: () => 'attachment; filename="a.vegaduta-workflow.json"' },
      blob: () => Promise.resolve(blob),
    });
    const file = await workflowPortabilityApi.exportWorkflowDsl("wf 1");
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/wf%201/export");
    expect(file.filename).toBe("a.vegaduta-workflow.json");
    expect(file.blob).toBe(blob);
  });

  it("throws an error with the status when the export is refused", async () => {
    apiFetchMock.mockResolvedValue({ ok: false, status: 404, headers: { get: (): string | null => null } });
    await expect(workflowPortabilityApi.exportWorkflowDsl("x")).rejects.toMatchObject({ status: 404 });
  });
});
