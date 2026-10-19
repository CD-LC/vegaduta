import { afterEach, describe, expect, it, vi } from "vitest";
import {
  COOKIE_PATH,
  REFRESH_COOKIE,
  coreBaseUrl,
  handleLocalAuth,
  isSameOrigin,
  readRefreshCookie,
  refreshCookie,
} from "./localAuthProxy";

afterEach(() => {
  vi.unstubAllEnvs();
});

function post(action: string, body: unknown, headers: Record<string, string> = {}): Request {
  return new Request(`http://console.test/api/local-auth/${action}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", host: "console.test", ...headers },
    body: JSON.stringify(body),
  });
}

function core(status: number, body: unknown, headers: Record<string, string> = {}) {
  return vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
    new Response(status === 204 ? null : JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json", ...headers },
    })
  );
}

const SESSION = {
  accessToken: "a.b.c",
  tokenType: "Bearer",
  expiresIn: 900,
  refreshToken: "vdlr_SECRET",
  refreshExpiresIn: 2592000,
  passwordChangeRequired: false,
  user: { id: "1", email: "o@x.test", roles: ["tenant-admin"] },
};

describe("refresh cookie helpers", () => {
  it("builds an HttpOnly, SameSite=Strict cookie scoped to the proxy path", () => {
    const c = refreshCookie("vdlr_x", 60, false);
    expect(c).toContain(`${REFRESH_COOKIE}=vdlr_x`);
    expect(c).toContain(`Path=${COOKIE_PATH}`);
    expect(c).toContain("HttpOnly");
    expect(c).toContain("SameSite=Strict");
    expect(c).toContain("Max-Age=60");
    expect(c).not.toContain("Secure");
  });

  it("adds Secure over https and clears with Max-Age=0", () => {
    expect(refreshCookie("v", 60, true)).toContain("Secure");
    const cleared = refreshCookie("", 0, false);
    expect(cleared).toContain("Max-Age=0");
    expect(cleared).toContain(`${REFRESH_COOKIE}=;`);
  });

  it("reads only its own cookie from a Cookie header", () => {
    expect(readRefreshCookie(`a=1; ${REFRESH_COOKIE}=vdlr_abc; b=2`)).toBe("vdlr_abc");
    expect(readRefreshCookie("x_vd_local_rt=nope")).toBeNull();
    expect(readRefreshCookie(null)).toBeNull();
    expect(readRefreshCookie(`${REFRESH_COOKIE}=`)).toBeNull();
  });
});

describe("isSameOrigin", () => {
  const req = (h: Record<string, string>) => new Request("http://console.test/x", { method: "POST", headers: h });
  it("accepts a matching Origin and requests with no Origin (non-browser clients)", () => {
    expect(isSameOrigin(req({ origin: "http://console.test", host: "console.test" }))).toBe(true);
    expect(isSameOrigin(req({ host: "console.test" }))).toBe(true);
  });
  it("refuses a foreign Origin and a cross-site Fetch-Metadata", () => {
    expect(isSameOrigin(req({ origin: "http://evil.test", host: "console.test" }))).toBe(false);
    expect(isSameOrigin(req({ "sec-fetch-site": "cross-site", host: "console.test" }))).toBe(false);
    expect(isSameOrigin(req({ origin: "not a url", host: "console.test" }))).toBe(false);
  });
});

describe("core base url", () => {
  it("prefers the internal address when one is configured", () => {
    vi.stubEnv("CORE_INTERNAL_URL", "http://core:8080/");
    expect(coreBaseUrl()).toBe("http://core:8080");
  });
});

describe("handleLocalAuth", () => {
  it("login: moves the refresh token into an HttpOnly cookie and never returns it to the page", async () => {
    const fetchImpl = core(200, SESSION);

    const res = await handleLocalAuth(post("login", { identifier: "o@x.test", password: "pw" }), "login", fetchImpl);

    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.accessToken).toBe("a.b.c");
    expect(JSON.stringify(body)).not.toContain("vdlr_SECRET");
    expect(body).not.toHaveProperty("refreshToken");
    const cookie = res.headers.get("set-cookie") ?? "";
    expect(cookie).toContain("vd_local_rt=vdlr_SECRET");
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("Max-Age=2592000");
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(String(fetchImpl.mock.calls[0][0])).toMatch(/\/api\/auth\/local\/login$/);
  });

  it("refresh: takes the token from the cookie and ignores one supplied by the page", async () => {
    const fetchImpl = core(200, { ...SESSION, refreshToken: "vdlr_NEXT" });

    const res = await handleLocalAuth(
      post("refresh", { refreshToken: "vdlr_FORGED_BY_PAGE" }, { cookie: `${REFRESH_COOKIE}=vdlr_REAL` }),
      "refresh",
      fetchImpl
    );

    const sent = JSON.parse(String(fetchImpl.mock.calls[0][1]?.body));
    expect(sent.refreshToken).toBe("vdlr_REAL");
    expect(res.headers.get("set-cookie")).toContain("vd_local_rt=vdlr_NEXT");
  });

  it("refresh without a cookie is a 401 that never reaches core", async () => {
    const fetchImpl = core(200, SESSION);
    const res = await handleLocalAuth(post("refresh", {}), "refresh", fetchImpl);
    expect(res.status).toBe(401);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("a refresh core refuses keeps the cookie: a concurrent tab may already hold the rotated one", async () => {
    const res = await handleLocalAuth(
      post("refresh", {}, { cookie: `${REFRESH_COOKIE}=vdlr_OLD` }),
      "refresh",
      core(401, { error: "invalid_session", message: "ended" })
    );
    expect(res.status).toBe(401);
    expect(res.headers.get("set-cookie")).toBeNull();
    expect((await res.json()).error).toBe("invalid_session");
  });

  it("logout revokes with the cookie's token and clears it, even when core is down", async () => {
    const ok = core(204, null);
    const res = await handleLocalAuth(post("logout", {}, { cookie: `${REFRESH_COOKIE}=vdlr_REAL` }), "logout", ok);
    expect(res.status).toBe(200);
    expect(res.headers.get("set-cookie")).toContain("Max-Age=0");
    expect(JSON.parse(String(ok.mock.calls[0][1]?.body)).refreshToken).toBe("vdlr_REAL");

    const down = vi.fn(async () => {
      throw new Error("connect refused");
    });
    const res2 = await handleLocalAuth(post("logout", {}, { cookie: `${REFRESH_COOKIE}=vdlr_REAL` }), "logout", down as never);
    // Core unreachable: the cookie is still dropped, so the browser forgets the token.
    expect(res2.status).toBe(200);
    expect(res2.headers.get("set-cookie")).toContain("Max-Age=0");
  });

  it("refuses a cross-origin POST before doing anything", async () => {
    const fetchImpl = core(200, SESSION);
    const res = await handleLocalAuth(
      post("login", { identifier: "a", password: "b" }, { origin: "http://evil.test" }),
      "login",
      fetchImpl
    );
    expect(res.status).toBe(403);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("rejects unknown actions, wrong methods, oversized and malformed bodies", async () => {
    const fetchImpl = core(200, SESSION);
    expect((await handleLocalAuth(post("nope", {}), "nope", fetchImpl)).status).toBe(404);
    expect((await handleLocalAuth(new Request("http://console.test/x"), "login", fetchImpl)).status).toBe(405);
    expect((await handleLocalAuth(post("login", { p: "x".repeat(9000) }), "login", fetchImpl)).status).toBe(413);
    const bad = new Request("http://console.test/api/local-auth/login", { method: "POST", body: "{not json" });
    expect((await handleLocalAuth(bad, "login", fetchImpl)).status).toBe(400);
    expect((await handleLocalAuth(post("login", [1, 2]), "login", fetchImpl)).status).toBe(400);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("change-password forwards the bearer and requires one", async () => {
    const fetchImpl = core(200, { ...SESSION, refreshToken: "vdlr_AFTER" });
    const none = await handleLocalAuth(post("change-password", { currentPassword: "a", newPassword: "b" }), "change-password", fetchImpl);
    expect(none.status).toBe(401);

    const res = await handleLocalAuth(
      post("change-password", { currentPassword: "a", newPassword: "b" }, { authorization: "Bearer tok" }),
      "change-password",
      fetchImpl
    );
    expect(res.status).toBe(200);
    expect((fetchImpl.mock.calls[0][1]?.headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(res.headers.get("set-cookie")).toContain("vdlr_AFTER");
  });

  it("passes core's error code, message and Retry-After through for a throttled login", async () => {
    const res = await handleLocalAuth(
      post("login", { identifier: "a", password: "b" }),
      "login",
      core(429, { error: "too_many_attempts", message: "slow down", retryAfterSeconds: 30 }, { "Retry-After": "30" })
    );
    expect(res.status).toBe(429);
    expect(res.headers.get("retry-after")).toBe("30");
    expect(res.headers.get("set-cookie")).toBeNull();
    expect(await res.json()).toMatchObject({ error: "too_many_attempts", retryAfterSeconds: 30 });
  });

  it("marks the cookie Secure behind a TLS-terminating proxy", async () => {
    const res = await handleLocalAuth(
      post("login", { identifier: "a", password: "b" }, { "x-forwarded-proto": "https" }),
      "login",
      core(200, SESSION)
    );
    expect(res.headers.get("set-cookie")).toContain("Secure");
  });

  it("config is a plain GET passthrough with no cookie", async () => {
    const fetchImpl = core(200, { mode: "local", setupRequired: true });
    const res = await handleLocalAuth(new Request("http://console.test/api/local-auth/config"), "config", fetchImpl);
    expect(await res.json()).toEqual({ mode: "local", setupRequired: true });
    expect(res.headers.get("set-cookie")).toBeNull();
  });

  it("answers 502 when core cannot be reached", async () => {
    const down = vi.fn(async () => {
      throw new Error("down");
    });
    const res = await handleLocalAuth(post("login", { identifier: "a", password: "b" }), "login", down as never);
    expect(res.status).toBe(502);
  });
});

describe("handleLocalAuth request hygiene", () => {
  it("passes the client address on so core can throttle per client", async () => {
    const fetchImpl = core(401, { error: "invalid_credentials", message: "no" });

    await handleLocalAuth(post("login", { identifier: "a@b.test", password: "x" }, { "x-forwarded-for": "203.0.113.9" }), "login", fetchImpl);

    expect((fetchImpl.mock.calls[0][1]?.headers as Record<string, string>)["X-Forwarded-For"]).toBe("203.0.113.9");
  });

  it("sends no forwarding header when the request had none", async () => {
    const fetchImpl = core(401, { error: "invalid_credentials", message: "no" });

    await handleLocalAuth(post("login", { identifier: "a@b.test", password: "x" }), "login", fetchImpl);

    expect(fetchImpl.mock.calls[0][1]?.headers as Record<string, string>).not.toHaveProperty("X-Forwarded-For");
  });

  it("treats inherited object members as unknown actions", async () => {
    const fetchImpl = core(200, {});

    for (const action of ["constructor", "__proto__", "toString", "hasOwnProperty"]) {
      const res = await handleLocalAuth(post(action, {}), action, fetchImpl);
      expect(res.status).toBe(404);
    }
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
