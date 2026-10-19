// Generated documents "unable to open, corrupted" (prod, 2026-10-09): the GET
// cache read every response with res.text(), so a PDF/DOCX/PPTX was UTF-8
// decoded (invalid bytes -> U+FFFD) before MediaCard turned it into a Blob.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../auth", () => ({ auth: { getToken: async () => "t", getUserId: () => "u1" } }));
vi.mock("../toastBus", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));
vi.mock("../clientLogger", () => ({ reportClientError: vi.fn() }));
vi.mock("./persistentCache", () => ({
  PERSIST_MAX_AGE_MS: 0,
  clearPersisted: async () => {},
  isPersistablePath: () => true,
  readPersisted: async (): Promise<unknown[]> => [],
  writePersisted: vi.fn(async () => {}),
}));

// A ZIP local-file header plus bytes that are not valid UTF-8.
const BYTES = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0xff, 0xfe, 0x80, 0xc3, 0x28, 0x00, 0x9f]);
const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

const fetchMock = vi.fn();

describe("apiFetch GET of a binary body", () => {
  beforeEach(() => {
    vi.resetModules();
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("returns the exact bytes and never caches them", async () => {
    fetchMock.mockImplementation(async () => new Response(BYTES, { status: 200, headers: { "content-type": DOCX } }));
    const { apiFetch } = await import("./core");

    const first = new Uint8Array(await (await apiFetch("/api/documentgen/documents/1")).arrayBuffer());
    expect(Array.from(first)).toEqual(Array.from(BYTES));

    const second = new Uint8Array(await (await apiFetch("/api/documentgen/documents/1")).arrayBuffer());
    expect(Array.from(second)).toEqual(Array.from(BYTES));
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("gives concurrent callers of one binary path each a readable body", async () => {
    fetchMock.mockImplementation(async () => new Response(BYTES, { status: 200, headers: { "content-type": "application/pdf" } }));
    const { apiFetch } = await import("./core");
    const [a, b] = await Promise.all([apiFetch("/api/x/1"), apiFetch("/api/x/1")]);
    expect((await a.arrayBuffer()).byteLength).toBe(BYTES.length);
    expect((await b.arrayBuffer()).byteLength).toBe(BYTES.length);
  });

  it("still serves JSON from the cache", async () => {
    fetchMock.mockImplementation(async () => new Response('{"a":1}', { status: 200, headers: { "content-type": "application/json" } }));
    const { apiFetch } = await import("./core");
    expect(await (await apiFetch("/api/j")).json()).toEqual({ a: 1 });
    expect(await (await apiFetch("/api/j")).json()).toEqual({ a: 1 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("classifies content types", async () => {
    const { isTextResponse } = await import("./core");
    const r = (t: string | null) => ({ headers: { get: () => t } });
    for (const t of [null, "application/json", "application/json;charset=UTF-8", "text/plain", "text/csv",
      "application/problem+json", "application/xml"]) {
      expect(isTextResponse(r(t)), String(t)).toBe(true);
    }
    for (const t of [DOCX, "application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "image/png", "image/svg+xml", "audio/mpeg", "video/mp4", "application/octet-stream", "application/zip"]) {
      expect(isTextResponse(r(t)), t).toBe(false);
    }
  });
});
