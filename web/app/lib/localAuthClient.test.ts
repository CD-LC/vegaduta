import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Mock } from "vitest";
import { LocalAuthClient, LocalAuthError, decodeJwtPayload, localAuthPost, safeNext } from "./localAuthClient";

function jwt(claims: Record<string, unknown>): string {
  const enc = (o: unknown) => {
    const bytes = new TextEncoder().encode(JSON.stringify(o));
    return btoa(String.fromCharCode(...bytes)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
  };
  return `${enc({ alg: "RS256" })}.${enc(claims)}.sig`;
}

function session(overrides: Record<string, unknown> = {}) {
  const exp = Math.floor(Date.now() / 1000) + 900;
  return {
    accessToken: jwt({ sub: "u1", exp, tenant_id: "t_x", realm_access: { roles: ["tenant-admin"] }, email: "o@x.test" }),
    expiresIn: 900,
    refreshExpiresIn: 3600,
    ...overrides,
  };
}

function reply(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

let fetchMock: Mock<typeof fetch>;

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("decodeJwtPayload", () => {
  it("decodes base64url payloads including non-ASCII names, and returns null for junk", () => {
    expect(decodeJwtPayload(jwt({ name: "Zoë Ångström" }))?.name).toBe("Zoë Ångström");
    expect(decodeJwtPayload("not-a-jwt")).toBeNull();
    expect(decodeJwtPayload("a.%%%.c")).toBeNull();
  });
});

describe("safeNext", () => {
  it("keeps same-origin paths and refuses anything that could leave the origin", () => {
    expect(safeNext("/agents?x=1#h")).toBe("/agents?x=1#h");
    expect(safeNext(null)).toBe("/");
    for (const bad of ["//evil.test", "/\\evil.test", "https://evil.test/x", "javascript:alert(1)", "evil"]) {
      expect(safeNext(bad)).toBe("/");
    }
    expect(safeNext(`${window.location.origin}/inbox`)).toBe("/inbox");
  });
});

describe("localAuthPost", () => {
  it("throws a typed error carrying core's code, message and retry hint", async () => {
    fetchMock.mockImplementation(async () => reply(429, { error: "too_many_attempts", message: "slow", retryAfterSeconds: 12 }));
    await expect(localAuthPost("login", {})).rejects.toMatchObject({ status: 429, code: "too_many_attempts", retryAfterSeconds: 12 });
  });

  it("maps a network failure to status 0 (transient)", async () => {
    fetchMock.mockRejectedValue(new TypeError("failed"));
    await expect(localAuthPost("login", {})).rejects.toMatchObject({ status: 0, code: "network" });
  });
});

describe("LocalAuthClient", () => {
  it("init() restores a session from the refresh cookie and exposes the keycloak-js surface", async () => {
    fetchMock.mockImplementation(async () => reply(200, session()));
    const kc = new LocalAuthClient();
    const onSuccess = vi.fn();
    kc.onAuthSuccess = onSuccess;

    expect(await kc.init()).toBe(true);

    expect(kc.authenticated).toBe(true);
    expect(kc.realmAccess?.roles).toEqual(["tenant-admin"]);
    expect(kc.tokenParsed?.tenant_id).toBe("t_x");
    expect(kc.refreshTokenParsed?.exp).toBeGreaterThan(Date.now() / 1000);
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/local-auth/refresh");
  });

  it("init() resolves false (not an error) when there is no session", async () => {
    fetchMock.mockImplementation(async () => reply(401, { error: "invalid_session", message: "x" }));
    const kc = new LocalAuthClient();
    const p = kc.init();
    await vi.advanceTimersByTimeAsync(500);
    expect(await p).toBe(false);
    expect(kc.authenticated).toBe(false);
  });

  it("init() rejects on a server or network failure so the retry policy can see it", async () => {
    fetchMock.mockImplementation(async () => reply(502, { error: "unavailable", message: "x" }));
    await expect(new LocalAuthClient().init()).rejects.toBeInstanceOf(LocalAuthError);
  });

  it("updateToken() does nothing while the token is still valid, refreshes when it is not", async () => {
    fetchMock.mockImplementation(async () => reply(200, session()));
    const kc = new LocalAuthClient();
    await kc.init();
    fetchMock.mockClear();

    expect(await kc.updateToken(30)).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();

    fetchMock.mockImplementation(async () => reply(200, session()));
    expect(await kc.updateToken(-1)).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("concurrent updateToken() calls share one refresh (refresh tokens rotate)", async () => {
    fetchMock.mockImplementation(async () => reply(200, session()));
    const kc = new LocalAuthClient();
    await kc.init();
    fetchMock.mockClear();

    await Promise.all([kc.updateToken(-1), kc.updateToken(-1), kc.updateToken(-1)]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("a session core has ended clears refreshTokenParsed, the signal authReauth reads as 'gone'", async () => {
    fetchMock.mockImplementation(async () => reply(200, session()));
    const kc = new LocalAuthClient();
    await kc.init();
    const onError = vi.fn();
    kc.onAuthRefreshError = onError;
    fetchMock.mockImplementation(async () => reply(401, { error: "invalid_session", message: "ended" }));

    const p = kc.updateToken(-1);
    const settled = expect(p).rejects.toMatchObject({ status: 401 });
    await vi.advanceTimersByTimeAsync(500);
    await settled;

    expect(kc.refreshTokenParsed).toBeUndefined();
    expect(kc.authenticated).toBe(false);
    expect(onError).toHaveBeenCalled();
  });

  it("a network blip keeps the session state so one dropped packet stays silent", async () => {
    fetchMock.mockImplementation(async () => reply(200, session()));
    const kc = new LocalAuthClient();
    await kc.init();
    fetchMock.mockRejectedValue(new TypeError("offline"));

    await expect(kc.updateToken(-1)).rejects.toMatchObject({ status: 0 });

    expect(kc.refreshTokenParsed).toBeDefined();
    expect(kc.authenticated).toBe(true);
  });

  it("retries a refused refresh once, to pick up the cookie another tab just rotated", async () => {
    fetchMock
      .mockImplementationOnce(async () => reply(401, { error: "invalid_session", message: "stale" }))
      .mockImplementationOnce(async () => reply(200, session()));
    const kc = new LocalAuthClient();

    const p = kc.init();
    await vi.advanceTimersByTimeAsync(500);

    expect(await p).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("serialises refreshes through the Web Locks API when the browser has it", async () => {
    const order: string[] = [];
    const request = vi.fn(async (_name: string, cb: () => Promise<void>) => {
      order.push("lock");
      await cb();
      order.push("unlock");
    });
    Object.defineProperty(navigator, "locks", { value: { request }, configurable: true });
    try {
      fetchMock.mockImplementationOnce(async () => {
        order.push("fetch");
        return reply(200, session());
      });
      const kc = new LocalAuthClient();
      expect(await kc.init()).toBe(true);
      expect(request).toHaveBeenCalledWith("vegaduta-local-auth-refresh", expect.any(Function));
      expect(order).toEqual(["lock", "fetch", "unlock"]);
    } finally {
      Object.defineProperty(navigator, "locks", { value: undefined, configurable: true });
    }
  });

  it("adopt() takes a sign-in session and fires onAuthSuccess once", () => {
    const kc = new LocalAuthClient();
    const onSuccess = vi.fn();
    kc.onAuthSuccess = onSuccess;
    kc.adopt(session({ passwordChangeRequired: true }));
    kc.adopt(session());
    expect(kc.authenticated).toBe(true);
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it("clear() forgets everything and cancels the expiry timer", () => {
    const kc = new LocalAuthClient();
    kc.onTokenExpired = vi.fn();
    kc.adopt(session({ expiresIn: 60 }));
    kc.clear();
    vi.advanceTimersByTime(120_000);
    expect(kc.token).toBeUndefined();
    expect(kc.onTokenExpired).not.toHaveBeenCalled();
  });

  it("arms onTokenExpired shortly before the access token lapses", () => {
    const kc = new LocalAuthClient();
    const expired = vi.fn();
    kc.onTokenExpired = expired;
    kc.adopt(session({ expiresIn: 100 }));
    vi.advanceTimersByTime(85_000);
    expect(expired).not.toHaveBeenCalled();
    vi.advanceTimersByTime(10_000);
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it("has no hosted account console", () => {
    expect(new LocalAuthClient().createAccountUrl()).toBeNull();
  });
});
