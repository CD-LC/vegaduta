import { describe, it, expect, vi, afterEach } from "vitest";
import { verifyPlatformAdmin } from "./serverAuth";

function req(headers: Record<string, string> = {}) {
  return new Request("http://admin.test/api/x", { headers });
}
function upstream(status: number) {
  const f = vi.fn(async (..._a: any[]) => new Response("{}", { status }));
  vi.stubGlobal("fetch", f);
  return f;
}

describe("verifyPlatformAdmin", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("rejects a missing Authorization header with 401 and never calls core", async () => {
    const f = upstream(200);
    const r = await verifyPlatformAdmin(req());
    expect(r).toMatchObject({ ok: false, status: 401 });
    expect(f).not.toHaveBeenCalled();
  });

  it("rejects a non-Bearer or empty-token header", async () => {
    const f = upstream(200);
    expect((await verifyPlatformAdmin(req({ authorization: "Basic abc" }))).status).toBe(401);
    expect((await verifyPlatformAdmin(req({ authorization: "Bearer " }))).status).toBe(401);
    expect(f).not.toHaveBeenCalled();
  });

  it("accepts when core's platform health returns 200 and forwards the caller's header", async () => {
    const f = upstream(200);
    const r = await verifyPlatformAdmin(req({ authorization: "Bearer tok123" }));
    expect(r).toEqual({ ok: true, status: 200, error: "" });
    const [url, init] = f.mock.calls[0] as any[];
    expect(url).toMatch(/\/api\/platform\/health$/);
    expect(init.headers.Authorization).toBe("Bearer tok123");
  });

  it("matches the Bearer scheme case-insensitively", async () => {
    upstream(200);
    expect((await verifyPlatformAdmin(req({ authorization: "bearer tok" }))).ok).toBe(true);
  });

  it("passes 401 and 403 through", async () => {
    upstream(401);
    expect(await verifyPlatformAdmin(req({ authorization: "Bearer t" }))).toMatchObject({ ok: false, status: 401 });
    upstream(403);
    expect(await verifyPlatformAdmin(req({ authorization: "Bearer t" }))).toMatchObject({ ok: false, status: 403 });
  });

  it("fails closed with 503 on other upstream statuses", async () => {
    for (const s of [500, 502, 302, 204]) {
      upstream(s);
      expect(await verifyPlatformAdmin(req({ authorization: "Bearer t" }))).toMatchObject({ ok: false, status: 503 });
    }
  });

  it("fails closed with 503 when core is unreachable", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("down"); }));
    expect(await verifyPlatformAdmin(req({ authorization: "Bearer t" }))).toMatchObject({ ok: false, status: 503 });
  });
});
