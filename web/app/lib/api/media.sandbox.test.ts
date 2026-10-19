// downloadSandboxArtifact: the authenticated-blob fetch behind MediaCard's sandbox-artifact kind.
// apiFetch is mocked at the seam - no network.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
vi.mock("./core", () => ({
  apiFetch: apiFetchMock,
  friendlyStatusFallback: (status: number) => `status ${status}`,
  toJson: async (res: any) => res.json(),
  jsonBody: (body: any) => ({ body: JSON.stringify(body) }),
}));

import { mediaApi } from "./media";

const ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

function response(init: { ok: boolean; status?: number; disposition?: string; blob?: Blob }) {
  return Promise.resolve({
    ok: init.ok,
    status: init.status ?? (init.ok ? 200 : 500),
    headers: { get: (name: string) => (name === "Content-Disposition" ? init.disposition ?? null : null) },
    blob: async () => init.blob ?? new Blob(["x"], { type: "image/png" }),
  } as any);
}

describe("mediaApi.downloadSandboxArtifact", () => {
  beforeEach(() => apiFetchMock.mockReset());

  it("requests the artifact path and returns the blob with the server's real filename", async () => {
    apiFetchMock.mockReturnValue(response({ ok: true, disposition: 'attachment; filename="chart.png"' }));
    const out = await mediaApi.downloadSandboxArtifact(ID);
    expect(apiFetchMock).toHaveBeenCalledWith(`/api/sandbox/artifacts/${ID}`);
    expect(out.filename).toBe("chart.png");
    expect(out.blob.type).toBe("image/png");
  });

  it("falls back to a generic name when there is no Content-Disposition", async () => {
    apiFetchMock.mockReturnValue(response({ ok: true }));
    expect((await mediaApi.downloadSandboxArtifact(ID)).filename).toBe(`analysis-${ID}`);
  });

  it("explains an expired/inaccessible artifact (404) instead of a raw status", async () => {
    apiFetchMock.mockReturnValue(response({ ok: false, status: 404 }));
    await expect(mediaApi.downloadSandboxArtifact(ID)).rejects.toThrow("This file is no longer available.");
  });

  it("uses the friendly fallback for other failures", async () => {
    apiFetchMock.mockReturnValue(response({ ok: false, status: 503 }));
    await expect(mediaApi.downloadSandboxArtifact(ID)).rejects.toThrow("status 503");
  });
});
