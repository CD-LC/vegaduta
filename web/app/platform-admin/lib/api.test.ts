import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

const h = vi.hoisted(() => ({
  getToken: vi.fn(),
  toastError: vi.fn(),
  report: vi.fn(),
}));
vi.mock("./auth", () => ({ auth: { getToken: h.getToken } }));
vi.mock("./toastBus", () => ({ toast: { error: h.toastError, success: vi.fn() } }));
vi.mock("./clientLogger", () => ({ reportClientError: h.report }));

import { api, API_BASE, clearApiGetCache, prefetchApiGets } from "./api";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
function mockFetch(impl: (...a: any[]) => any) {
  const f = vi.fn(impl);
  vi.stubGlobal("fetch", f);
  return f;
}

beforeEach(() => {
  vi.clearAllMocks();
  h.getToken.mockResolvedValue("tok");
});
afterEach(() => {
  vi.unstubAllGlobals();
  // The module-level GET cache survives across tests - a case that cached a
  // success would otherwise feed every later case hitting the same path.
  clearApiGetCache();
});

describe("api wrapper - requests", () => {
  it("prefixes API_BASE and sends the bearer token", async () => {
    const f = mockFetch(async () => json({ ok: 1 }));
    expect(await api.getPlatformSettings()).toEqual({ ok: 1 });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe(`${API_BASE}/api/platform/settings`);
    expect(init.headers.Authorization).toBe("Bearer tok");
  });

  it("omits Authorization when there is no token", async () => {
    h.getToken.mockResolvedValue(null);
    const f = mockFetch(async () => json({}));
    await api.getSystemStatus();
    expect(f.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it("PUTs a JSON body with a content type", async () => {
    const f = mockFetch(async () => json({}));
    await api.updatePlatformLlmTask("summarise", { model: "m" });
    const [url, init] = f.mock.calls[0];
    expect(url).toMatch(/\/api\/platform\/llm-tasks\/summarise$/);
    expect(init.method).toBe("PUT");
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(JSON.parse(init.body)).toEqual({ model: "m" });
  });

  it("url-encodes setting keys", async () => {
    const f = mockFetch(async () => json({}));
    await api.updatePlatformSetting("a b/c", "v");
    expect(f.mock.calls[0][0]).toMatch(/\/settings\/a%20b%2Fc$/);
    expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ value: "v" });
  });

  it("savePluginCatalog targets the plugins.catalog setting", async () => {
    const f = mockFetch(async () => json({}));
    await api.savePluginCatalog("{}");
    expect(f.mock.calls[0][0]).toMatch(/settings\/plugins\.catalog$/);
  });

  it("uploadPluginArtifact sends multipart without a manual Content-Type", async () => {
    const f = mockFetch(async () => json({ pluginId: "p" }));
    const file = new File(["x"], "a.vsix");
    await api.uploadPluginArtifact("p", "1.0.0", file);
    const init = f.mock.calls[0][1];
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    expect(init.body.get("pluginId")).toBe("p");
    expect(init.body.get("version")).toBe("1.0.0");
    expect(init.headers["Content-Type"]).toBeUndefined();
  });

  it("searchMcpRegistry only includes provided query params", async () => {
    const f = mockFetch(async () => json({}));
    await api.searchMcpRegistry("git", undefined);
    expect(f.mock.calls[0][0]).toMatch(/search\?q=git$/);
    await api.searchMcpRegistry(undefined, "c1");
    expect(f.mock.calls[1][0]).toMatch(/search\?cursor=c1$/);
  });
});

describe("api wrapper - responses", () => {
  it("returns null for 204 and for an empty 200 body", async () => {
    mockFetch(async () => new Response(null, { status: 204 }));
    expect(await api.getPlatformSettings()).toBeNull();
    mockFetch(async () => new Response("", { status: 200 }));
    expect(await api.getPlatformSettings()).toBeNull();
  });

  it("throws the server's error message with status, toasts and logs it", async () => {
    mockFetch(async () => json({ error: "bad thing", upstreamStatus: 502, errors: ["a", "b"] }, 400));
    await expect(api.getPlatformSettings()).rejects.toMatchObject({
      message: "bad thing",
      status: 400,
      upstreamStatus: 502,
      errors: ["a", "b"],
    });
    expect(h.toastError).toHaveBeenCalledWith("bad thing (2 issues)");
    expect(h.report).toHaveBeenCalledWith("api_call_failed", "bad thing", expect.objectContaining({ status: 400 }));
  });

  it("uses singular wording for a single issue", async () => {
    mockFetch(async () => json({ error: "bad", errors: ["a"] }, 422));
    await expect(api.getPlatformSettings()).rejects.toThrow();
    expect(h.toastError).toHaveBeenCalledWith("bad (1 issue)");
  });

  it.each([
    [403, /permission/],
    [404, /couldn't be found/],
    [409, /conflicts/],
    [429, /Too many requests/],
    [503, /temporarily unavailable/],
    [418, /Something went wrong/],
  ])("falls back to a friendly message for a bodyless %i", async (status, re) => {
    mockFetch(async () => new Response("<html>", { status }));
    await expect(api.getPlatformSettings()).rejects.toThrow(re);
  });

  it("401 throws a friendly error but does not toast or log", async () => {
    mockFetch(async () => new Response("", { status: 401 }));
    await expect(api.getPlatformSettings()).rejects.toMatchObject({
      status: 401,
      message: expect.stringMatching(/session has expired/),
    });
    expect(h.toastError).not.toHaveBeenCalled();
    expect(h.report).not.toHaveBeenCalled();
  });

  it("maps a network failure to the backend-unreachable error", async () => {
    mockFetch(async () => {
      throw new TypeError("Failed to fetch");
    });
    await expect(api.getPlatformSettings()).rejects.toMatchObject({
      isBackendUnreachable: true,
      message: expect.stringMatching(/Couldn't complete GET \/.*Failed to fetch/),
    });
    expect(h.toastError).toHaveBeenCalledTimes(1);
    expect(h.report).toHaveBeenCalledWith("api_backend_unreachable", "Failed to fetch", expect.any(Object));
  });

  it("retries a GET once after a network blip and raises no outage popup when it recovers", async () => {
    let calls = 0;
    const f = mockFetch(async () => {
      calls += 1;
      if (calls === 1) throw new TypeError("Failed to fetch");
      return json({ ok: 1 });
    });
    expect(await api.getPlatformSettings()).toEqual({ ok: 1 });
    expect(f).toHaveBeenCalledTimes(2);
    expect(h.toastError).not.toHaveBeenCalled();
    expect(h.report).not.toHaveBeenCalled();
  });

  it("never retries a write - it would risk applying it twice", async () => {
    const f = mockFetch(async () => {
      throw new TypeError("Failed to fetch");
    });
    await expect(api.resumePlatform()).rejects.toMatchObject({ isBackendUnreachable: true });
    expect(f).toHaveBeenCalledTimes(1);
  });

  it("session prefetch fails silently: no outage popup for a speculative read", async () => {
    const f = mockFetch(async () => {
      throw new TypeError("Failed to fetch");
    });
    prefetchApiGets(["/api/platform/health"]);
    await vi.waitFor(() => expect(f).toHaveBeenCalledTimes(2));
    await new Promise((r) => setTimeout(r, 50));
    expect(h.toastError).not.toHaveBeenCalled();
  });
});
