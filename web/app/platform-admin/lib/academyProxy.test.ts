import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("./logger", () => ({ logger: { error: vi.fn(), warn: vi.fn(), info: vi.fn() } }));

// Env is read at module load, so each test re-imports under its own env.
async function load(env: { base?: string; key?: string } = {}) {
  vi.resetModules();
  vi.stubEnv("ACADEMY_ADMIN_API_BASE", env.base ?? "");
  vi.stubEnv("SCHOLARSHIP_ADMIN_KEY", env.key ?? "");
  return await import("./academyProxy");
}

function mockFetch(impl: (...a: any[]) => any) {
  const f = vi.fn(impl);
  vi.stubGlobal("fetch", f);
  return f;
}

describe("academyProxy", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  describe("adminHeaders", () => {
    it("adds X-Admin-Key when configured and keeps extra headers", async () => {
      const { adminHeaders } = await load({ key: "sekret" });
      expect(adminHeaders({ "Content-Type": "application/json" })).toEqual({
        "Content-Type": "application/json",
        "X-Admin-Key": "sekret",
      });
    });
    it("omits X-Admin-Key when no key is set", async () => {
      const { adminHeaders } = await load();
      expect(adminHeaders()).toEqual({});
    });
  });

  describe("forwardToAcademy", () => {
    it("relays upstream status and JSON body, using the configured base", async () => {
      const { forwardToAcademy } = await load({ base: "http://academy:4100" });
      const f = mockFetch(async () => new Response(JSON.stringify({ claims: [1] }), { status: 200 }));
      const res = await forwardToAcademy("/api/admin/x", { method: "GET" }, { route: "t" });
      expect(f).toHaveBeenCalledWith("http://academy:4100/api/admin/x", { method: "GET", cache: "no-store" });
      expect(res.status).toBe(200);
      expect(await res.json()).toEqual({ claims: [1] });
    });

    it("defaults the base URL to localhost:4100", async () => {
      const { forwardToAcademy } = await load();
      const f = mockFetch(async () => new Response("{}", { status: 200 }));
      await forwardToAcademy("/p", {}, {});
      expect(f.mock.calls[0][0]).toBe("http://localhost:4100/p");
    });

    it("relays non-2xx statuses faithfully", async () => {
      const { forwardToAcademy } = await load();
      mockFetch(async () => new Response(JSON.stringify({ error: "already fulfilled" }), { status: 409 }));
      const res = await forwardToAcademy("/p", {}, {});
      expect(res.status).toBe(409);
      expect(await res.json()).toEqual({ error: "already fulfilled" });
    });

    it("wraps a non-JSON body as an error object, truncated to 2000 chars", async () => {
      const { forwardToAcademy } = await load();
      mockFetch(async () => new Response("x".repeat(5000), { status: 502 }));
      const res = await forwardToAcademy("/p", {}, {});
      const body = await res.json();
      expect(res.status).toBe(502);
      expect(body.error).toHaveLength(2000);
    });

    it("returns an empty object for an empty body", async () => {
      const { forwardToAcademy } = await load();
      mockFetch(async () => new Response("", { status: 200 }));
      const res = await forwardToAcademy("/p", {}, {});
      expect(await res.json()).toEqual({});
    });

    it("turns a connection failure into a clean 502", async () => {
      const { forwardToAcademy } = await load();
      mockFetch(async () => {
        throw new Error("ECONNREFUSED");
      });
      const res = await forwardToAcademy("/p", {}, { route: "t" });
      expect(res.status).toBe(502);
      expect((await res.json()).error).toMatch(/unreachable/i);
    });
  });
});
