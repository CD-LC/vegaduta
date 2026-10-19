import { describe, it, expect, vi, beforeEach } from "vitest";

// Mock the server-only pino logger before importing the route, so this test
// never touches a real pino instance (no stdout noise, no need for pino to
// actually resolve in this sandbox's node_modules) and so we can assert on
// exactly what gets logged.
//
// warnSpy must be created inside vi.hoisted(), not as a plain top-level
// const - vi.mock()'s factory is hoisted above ALL top-level code (including
// a plain `const warnSpy = vi.fn()` below it), so referencing warnSpy from
// inside the factory without vi.hoisted() throws "Cannot access 'warnSpy'
// before initialization".
const { warnSpy } = vi.hoisted(() => ({ warnSpy: vi.fn() }));
vi.mock("../../lib/logger", () => ({
  logger: { warn: warnSpy },
}));

// Imported after the mock is registered (vi.mock is hoisted by Vitest, so
// this ordering is safe even though it reads top-to-bottom below).
import { POST } from "./route";

function jsonRequest(body: unknown): Request {
  return new Request("http://localhost/api/client-log", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

describe("POST /api/client-log", () => {
  beforeEach(() => {
    warnSpy.mockClear();
  });

  it("accepts an allow-listed type and forwards it to the logger", async () => {
    const res = await POST(jsonRequest({ type: "auth_failure", message: "boom", context: { a: 1 } }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect(warnSpy).toHaveBeenCalledTimes(1);
    const [meta, msg] = warnSpy.mock.calls[0];
    expect(meta).toMatchObject({ source: "client", type: "auth_failure", context: { a: 1 } });
    expect(msg).toBe("boom");
  });

  it("accepts api_backend_unreachable - the type api.ts's doFetch actually reports on a network failure", async () => {
    // Bug fix (2026-08-08): this type is in clientLogger.ts's ClientLogType
    // union and is exactly what doFetch's catch block reports, but was
    // missing from this route's allow-list - every real "backend
    // unreachable" incident was a silent 400 here, with zero server-side
    // trace of it ever happening.
    const res = await POST(jsonRequest({ type: "api_backend_unreachable", message: "Failed to fetch" }));
    expect(res.status).toBe(200);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toMatchObject({ source: "client", type: "api_backend_unreachable" });
  });

  it("rejects a type not on the allow-list with 400, without logging anything", async () => {
    const res = await POST(jsonRequest({ type: "literally_anything", message: "x" }));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "unknown_type" });
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("rejects a missing/non-string type with 400", async () => {
    const res = await POST(jsonRequest({ message: "no type field" }));
    expect(res.status).toBe(400);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("rejects an unparseable body with 400, without throwing", async () => {
    const badRequest = new Request("http://localhost/api/client-log", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{not valid json",
    });
    const res = await POST(badRequest);
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "invalid_json" });
  });

  it("falls back to a default log message when message isn't a string, but still logs", async () => {
    await POST(jsonRequest({ type: "client_error", message: 12345 }));
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][1]).toBe("client-reported error");
  });
});
