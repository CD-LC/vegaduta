import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  DEVICE_COOKIE,
  DEVICE_LABEL_COOKIE,
  clearDeviceCookies,
  deviceCookies,
  handleDeviceStatus,
  handleUnlock,
  readDeviceCookie,
} from "./localAuthUnlockProxy";

beforeEach(() => {
  vi.stubEnv("CORE_INTERNAL_URL", "http://core.test:8080");
});

afterEach(() => {
  vi.unstubAllEnvs();
});

const BASE = "http://console.test/api/local-auth";

function call(
  method: string,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {}
): Request {
  return new Request(`${BASE}/${path}`, {
    method,
    headers: { "Content-Type": "application/json", host: "console.test", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
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
};

const segs = (path: string) => path.split("/");
const setCookies = (res: Response) => res.headers.getSetCookie();
const sent = (fetchMock: ReturnType<typeof core>) => {
  const [url, init] = fetchMock.mock.calls[0];
  return {
    url: String(url),
    init: init as RequestInit,
    headers: init?.headers as Record<string, string>,
    body: init?.body ? JSON.parse(String(init.body)) : undefined,
  };
};

describe("device cookie helpers", () => {
  it("is HttpOnly, SameSite=Strict, scoped to the proxy path, 400 days, Secure only over https", () => {
    const [device] = deviceCookies("dev-1", "sEcReT_x", "", false);
    expect(device).toContain(`${DEVICE_COOKIE}=dev-1.sEcReT_x`);
    expect(device).toContain("Path=/api/local-auth");
    expect(device).toContain("HttpOnly");
    expect(device).toContain("SameSite=Strict");
    expect(device).toContain(`Max-Age=${400 * 24 * 3600}`);
    expect(device).not.toContain("Secure");
    expect(deviceCookies("dev-1", "s", "", true)[0]).toContain("Secure");
  });

  it("stores the label in a companion cookie and clears it when there is none", () => {
    const withLabel = deviceCookies("d", "s", "Mac Studio", false);
    expect(withLabel[1]).toContain(`${DEVICE_LABEL_COOKIE}=Mac%20Studio`);
    expect(deviceCookies("d", "s", "", false)[1]).toContain("Max-Age=0");
  });

  it("clears both cookies", () => {
    const cleared = clearDeviceCookies(true);
    expect(cleared).toHaveLength(2);
    for (const c of cleared) {
      expect(c).toContain("Max-Age=0");
      expect(c).toContain("Secure");
    }
  });

  it("reads a well-formed device cookie and rejects anything else", () => {
    expect(readDeviceCookie(`a=1; ${DEVICE_COOKIE}=dev-1.sec_ret; ${DEVICE_LABEL_COOKIE}=Mac%20Studio`)).toEqual({
      id: "dev-1",
      secret: "sec_ret",
      label: "Mac Studio",
    });
    expect(readDeviceCookie(`${DEVICE_COOKIE}=nodot`)).toBeNull();
    expect(readDeviceCookie(`${DEVICE_COOKIE}=a.b c`)).toBeNull();
    expect(readDeviceCookie(`x_${DEVICE_COOKIE}=a.b`)).toBeNull();
    expect(readDeviceCookie(null)).toBeNull();
  });
});

describe("GET device", () => {
  it("reports unknown without a cookie and never exposes the secret", async () => {
    const none = handleDeviceStatus(call("GET", "device"));
    expect(await none.json()).toEqual({ known: false });
    expect(none.headers.get("cache-control")).toBe("no-store");

    const known = handleDeviceStatus(
      call("GET", "device", undefined, { cookie: `${DEVICE_COOKIE}=dev-1.TOPSECRET; ${DEVICE_LABEL_COOKIE}=Laptop` })
    );
    const text = await known.text();
    expect(JSON.parse(text)).toEqual({ known: true, id: "dev-1", label: "Laptop" });
    expect(text).not.toContain("TOPSECRET");
  });

  it("drops a malformed cookie", async () => {
    const res = handleDeviceStatus(call("GET", "device", undefined, { cookie: `${DEVICE_COOKIE}=garbage` }));
    expect(await res.json()).toEqual({ known: false });
    expect(setCookies(res).some((c) => c.startsWith(`${DEVICE_COOKIE}=;`))).toBe(true);
  });
});

describe("routing and guards", () => {
  it("404s unknown paths, odd segments and traversal", async () => {
    const fetchMock = core(200, {});
    expect((await handleUnlock(call("GET", "unlock/nope"), ["nope"], fetchMock)).status).toBe(404);
    expect((await handleUnlock(call("POST", "unlock/x"), ["..", "login"], fetchMock)).status).toBe(404);
    expect((await handleUnlock(call("POST", "unlock/x"), ["passkey/login-options"], fetchMock)).status).toBe(404);
    expect((await handleUnlock(call("POST", "unlock/"), [], fetchMock)).status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("405s the wrong method and lists the right ones", async () => {
    const fetchMock = core(200, {});
    const res = await handleUnlock(call("GET", "unlock/login"), ["login"], fetchMock);
    expect(res.status).toBe(405);
    expect(res.headers.get("allow")).toBe("POST");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("refuses a cross-origin state change but lets a GET through", async () => {
    const fetchMock = core(200, { hasPassword: true, passkeys: [], devices: [], recoveryCodesRemaining: 0 });
    const cross = await handleUnlock(
      call("POST", "unlock/recovery-codes", {}, { origin: "http://evil.test", authorization: "Bearer t" }),
      segs("recovery-codes"),
      fetchMock
    );
    expect(cross.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
    const get = await handleUnlock(
      call("GET", "unlock/methods", undefined, { authorization: "Bearer t" }),
      ["methods"],
      fetchMock
    );
    expect(get.status).toBe(200);
  });

  it("requires a bearer token on the account endpoints without calling core", async () => {
    const fetchMock = core(200, {});
    for (const [method, path] of [
      ["GET", "methods"],
      ["POST", "devices"],
      ["DELETE", "devices/d1"],
      ["DELETE", "passkeys/p1"],
      ["POST", "recovery-codes"],
    ] as const) {
      const res = await handleUnlock(call(method, `unlock/${path}`, method === "POST" ? {} : undefined), segs(path), fetchMock);
      expect(res.status, path).toBe(401);
      expect((await res.json()).error).toBe("invalid_session");
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("does not forward a bearer to the public endpoints", async () => {
    const fetchMock = core(200, { challengeId: "c1", publicKey: {} });
    await handleUnlock(
      call("POST", "unlock/passkey/login-options", {}, { authorization: "Bearer stale" }),
      segs("passkey/login-options"),
      fetchMock
    );
    expect(sent(fetchMock).headers.Authorization).toBeUndefined();
  });

  it("rejects a malformed body", async () => {
    const fetchMock = core(200, {});
    const bad = new Request(`${BASE}/unlock/login`, {
      method: "POST",
      headers: { host: "console.test" },
      body: "{nope",
    });
    expect((await handleUnlock(bad, ["login"], fetchMock)).status).toBe(400);
    const big = call("POST", "unlock/login", { type: "recovery", credentials: { code: "x".repeat(70_000) } });
    expect((await handleUnlock(big, ["login"], fetchMock)).status).toBe(413);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("maps an unreachable core to 502", async () => {
    const fetchMock = vi.fn(async (): Promise<Response> => {
      throw new TypeError("down");
    });
    const res = await handleUnlock(call("POST", "unlock/passkey/login-options", {}), segs("passkey/login-options"), fetchMock);
    expect(res.status).toBe(502);
  });
});

describe("origin forwarding", () => {
  it("forwards the browser's Origin to core", async () => {
    const fetchMock = core(200, { challengeId: "c1", publicKey: {} });
    await handleUnlock(
      call("POST", "unlock/passkey/login-options", {}, { origin: "http://console.test" }),
      segs("passkey/login-options"),
      fetchMock
    );
    const s = sent(fetchMock);
    expect(s.url).toBe("http://core.test:8080/api/auth/local/unlock/passkey/login-options");
    expect(s.headers["X-Vegaduta-Origin"]).toBe("http://console.test");
  });

  it("falls back to the origin the browser reached us on, honouring a TLS-terminating proxy", async () => {
    const fetchMock = core(200, { hasPassword: true, passkeys: [], devices: [], recoveryCodesRemaining: 8 });
    await handleUnlock(
      call("GET", "unlock/methods", undefined, {
        authorization: "Bearer t",
        "x-forwarded-proto": "https",
        "x-forwarded-host": "vega.example.com",
      }),
      ["methods"],
      fetchMock
    );
    expect(sent(fetchMock).headers["X-Vegaduta-Origin"]).toBe("https://vega.example.com");
  });

  it("hands over the client address the edge proxy recorded", async () => {
    const fetchMock = core(200, { challengeId: "c", publicKey: {} });
    await handleUnlock(
      call("POST", "unlock/passkey/login-options", {}, { "x-forwarded-for": "203.0.113.9" }),
      segs("passkey/login-options"),
      fetchMock
    );
    expect(sent(fetchMock).headers["X-Forwarded-For"]).toBe("203.0.113.9");
  });
});

describe("PIN sign-in", () => {
  const cookie = `${DEVICE_COOKIE}=dev-1.sEcReT; ${DEVICE_LABEL_COOKIE}=Laptop`;

  it("injects the device id and secret from the cookie and ignores anything the page claims", async () => {
    const fetchMock = core(200, SESSION);
    const res = await handleUnlock(
      call("POST", "unlock/login", { type: "pin", pin: "493827", identifier: "someone-else", deviceSecret: "forged", credentials: { deviceSecret: "forged2" } }, { cookie }),
      ["login"],
      fetchMock
    );
    expect(res.status).toBe(200);
    expect(sent(fetchMock).body).toEqual({
      type: "pin",
      identifier: "dev-1",
      credentials: { pin: "493827", deviceSecret: "sEcReT" },
    });
  });

  it("moves the refresh token into its cookie and never leaks it or the secret to the body", async () => {
    const res = await handleUnlock(call("POST", "unlock/login", { type: "pin", pin: "493827" }, { cookie }), ["login"], core(200, SESSION));
    const text = await res.text();
    expect(text).not.toContain("vdlr_SECRET");
    expect(text).not.toContain("sEcReT");
    expect(text).not.toContain("refreshToken");
    expect(JSON.parse(text).accessToken).toBe("a.b.c");
    expect(setCookies(res).some((c) => c.startsWith("vd_local_rt=vdlr_SECRET") && c.includes("HttpOnly"))).toBe(true);
    expect(res.headers.get("cache-control")).toBe("no-store");
  });

  it("answers 401 device_unknown without calling core when the browser has no device", async () => {
    const fetchMock = core(200, SESSION);
    const res = await handleUnlock(call("POST", "unlock/login", { type: "pin", pin: "493827" }), ["login"], fetchMock);
    expect(res.status).toBe(401);
    expect((await res.json()).error).toBe("device_unknown");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("passes a wrong PIN and the throttle through, and keeps the device cookie", async () => {
    const wrong = await handleUnlock(
      call("POST", "unlock/login", { type: "pin", pin: "000000" }, { cookie }),
      ["login"],
      core(401, { error: "invalid_credentials", message: "nope" })
    );
    expect(wrong.status).toBe(401);
    expect((await wrong.json()).error).toBe("invalid_credentials");
    expect(setCookies(wrong)).toEqual([]);

    const throttled = await handleUnlock(
      call("POST", "unlock/login", { type: "pin", pin: "000000" }, { cookie }),
      ["login"],
      core(429, { error: "too_many_attempts", message: "slow", retryAfterSeconds: 45 }, { "Retry-After": "45" })
    );
    expect(throttled.status).toBe(429);
    expect(throttled.headers.get("retry-after")).toBe("45");
    expect(await throttled.json()).toMatchObject({ error: "too_many_attempts", retryAfterSeconds: 45 });
  });

  it("clears the device cookies when core says device_revoked", async () => {
    const res = await handleUnlock(
      call("POST", "unlock/login", { type: "pin", pin: "493827" }, { cookie }),
      ["login"],
      core(401, { error: "device_revoked", message: "revoked" })
    );
    expect(res.status).toBe(401);
    expect((await res.json()).error).toBe("device_revoked");
    const cleared = setCookies(res);
    expect(cleared.some((c) => c.startsWith(`${DEVICE_COOKIE}=;`) && c.includes("Max-Age=0"))).toBe(true);
    expect(cleared.some((c) => c.startsWith(`${DEVICE_LABEL_COOKIE}=;`))).toBe(true);
  });

  it("sets Secure on the cookies when the browser used https", async () => {
    const res = await handleUnlock(
      call("POST", "unlock/login", { type: "pin", pin: "493827" }, { cookie, "x-forwarded-proto": "https" }),
      ["login"],
      core(200, SESSION)
    );
    expect(setCookies(res).every((c) => c.includes("Secure"))).toBe(true);
  });
});

describe("passkey and recovery sign-in", () => {
  it("passes the challenge id and assertion string through", async () => {
    const fetchMock = core(200, SESSION);
    const assertion = JSON.stringify({ id: "cred", response: { signature: "sig" } });
    const res = await handleUnlock(
      call("POST", "unlock/login", { type: "passkey", identifier: "chal-1", credentials: { assertion, junk: 5 } }),
      ["login"],
      fetchMock
    );
    expect(res.status).toBe(200);
    expect(sent(fetchMock).body).toEqual({ type: "passkey", identifier: "chal-1", credentials: { assertion } });
    expect(setCookies(res).some((c) => c.startsWith("vd_local_rt="))).toBe(true);
  });

  it("recovery uses the literal identifier by default", async () => {
    const fetchMock = core(200, SESSION);
    await handleUnlock(call("POST", "unlock/login", { type: "recovery", credentials: { code: "ABCD" } }), ["login"], fetchMock);
    expect(sent(fetchMock).body).toEqual({ type: "recovery", identifier: "recovery", credentials: { code: "ABCD" } });
  });

  it("refuses an unknown sign-in type", async () => {
    const fetchMock = core(200, SESSION);
    const res = await handleUnlock(call("POST", "unlock/login", { type: "password", credentials: {} }), ["login"], fetchMock);
    expect(res.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("passes login options through untouched", async () => {
    const options = { challengeId: "c1", publicKey: { challenge: "abc", rpId: "console.test" } };
    const res = await handleUnlock(call("POST", "unlock/passkey/login-options", {}), segs("passkey/login-options"), core(200, options));
    expect(await res.json()).toEqual(options);
  });
});

describe("setup and enrolment", () => {
  const setup = { session: SESSION, deviceId: "dev-9", deviceSecret: "NEWsecret_1", recoveryCodes: ["A".repeat(16)] };

  it("setup with a PIN sets both cookies and returns the session and codes without secrets", async () => {
    const fetchMock = core(200, setup);
    const res = await handleUnlock(
      call("POST", "unlock/setup/pin", { token: "vdls_T", pin: "493827", label: "  Mac Studio  " }),
      segs("setup/pin"),
      fetchMock
    );
    expect(sent(fetchMock).body).toEqual({ token: "vdls_T", pin: "493827", label: "Mac Studio" });
    const text = await res.text();
    expect(text).not.toContain("NEWsecret_1");
    expect(text).not.toContain("vdlr_SECRET");
    const body = JSON.parse(text);
    expect(body.session.accessToken).toBe("a.b.c");
    expect(body.recoveryCodes).toEqual(["A".repeat(16)]);
    const cookies = setCookies(res);
    expect(cookies.find((c) => c.startsWith(`${DEVICE_COOKIE}=`))).toContain("dev-9.NEWsecret_1");
    expect(cookies.find((c) => c.startsWith(`${DEVICE_LABEL_COOKIE}=`))).toContain("Mac%20Studio");
    expect(cookies.some((c) => c.startsWith("vd_local_rt=vdlr_SECRET"))).toBe(true);
  });

  it("setup does not forward a bearer and surfaces a weak PIN", async () => {
    const fetchMock = core(400, { error: "weak_pin", message: "too simple" });
    const res = await handleUnlock(
      call("POST", "unlock/setup/pin", { token: "t", pin: "111111" }, { authorization: "Bearer x" }),
      segs("setup/pin"),
      fetchMock
    );
    expect(res.status).toBe(400);
    expect((await res.json()).error).toBe("weak_pin");
    expect(setCookies(res)).toEqual([]);
    expect(sent(fetchMock).headers.Authorization).toBeUndefined();
  });

  it("enrolling a PIN device forwards the bearer, sets the device cookie and hides the secret", async () => {
    const fetchMock = core(200, { deviceId: "dev-7", deviceSecret: "SECRET7_x" });
    const res = await handleUnlock(
      call("POST", "unlock/devices", { pin: "493827", label: "Kitchen iPad" }, { authorization: "Bearer t.o.k", "x-forwarded-proto": "https" }),
      ["devices"],
      fetchMock
    );
    expect(sent(fetchMock).headers.Authorization).toBe("Bearer t.o.k");
    expect(sent(fetchMock).body).toEqual({ pin: "493827", label: "Kitchen iPad" });
    const text = await res.text();
    expect(text).not.toContain("SECRET7_x");
    expect(JSON.parse(text)).toEqual({ deviceId: "dev-7" });
    const cookies = setCookies(res);
    expect(cookies[0]).toContain(`${DEVICE_COOKIE}=dev-7.SECRET7_x`);
    expect(cookies[0]).toContain("Secure");
  });

  it("a stale freshness window is passed through so the page can ask for a new sign-in", async () => {
    const res = await handleUnlock(
      call("POST", "unlock/devices", { pin: "493827", label: "x" }, { authorization: "Bearer t" }),
      ["devices"],
      core(403, { error: "reauth_required", message: "Sign in again." })
    );
    expect(res.status).toBe(403);
    expect((await res.json()).error).toBe("reauth_required");
    expect(setCookies(res)).toEqual([]);
  });

  it("passkey registration on the setup path stores the session cookie and strips the token", async () => {
    const fetchMock = core(200, { passkey: { id: "p1", label: "Mac" }, session: SESSION, recoveryCodes: ["B".repeat(16)] });
    const res = await handleUnlock(
      call("POST", "unlock/passkey/register", { challengeId: "c1", credential: "{}", setupToken: "vdls_T" }),
      segs("passkey/register"),
      fetchMock
    );
    const text = await res.text();
    expect(text).not.toContain("vdlr_SECRET");
    expect(JSON.parse(text).passkey.id).toBe("p1");
    expect(setCookies(res).some((c) => c.startsWith("vd_local_rt=vdlr_SECRET"))).toBe(true);
  });

  it("passkey registration with a bearer forwards it and sets no session cookie", async () => {
    const fetchMock = core(200, { passkey: { id: "p2", label: "Phone" } });
    const res = await handleUnlock(
      call("POST", "unlock/passkey/register", { challengeId: "c1", credential: "{}" }, { authorization: "Bearer t" }),
      segs("passkey/register"),
      fetchMock
    );
    expect(sent(fetchMock).headers.Authorization).toBe("Bearer t");
    expect(setCookies(res)).toEqual([]);
  });
});

describe("removals", () => {
  const mine = `${DEVICE_COOKIE}=dev-1.sEcReT; ${DEVICE_LABEL_COOKIE}=Laptop`;

  it("deleting the device this browser holds also clears its cookies", async () => {
    const fetchMock = core(204, null);
    const res = await handleUnlock(
      call("DELETE", "unlock/devices/dev-1", undefined, { authorization: "Bearer t", cookie: mine }),
      segs("devices/dev-1"),
      fetchMock
    );
    expect(res.status).toBe(204);
    expect(sent(fetchMock).url).toBe("http://core.test:8080/api/auth/local/unlock/devices/dev-1");
    expect(sent(fetchMock).init.method).toBe("DELETE");
    expect(setCookies(res).some((c) => c.startsWith(`${DEVICE_COOKIE}=;`))).toBe(true);
  });

  it("deleting another device leaves this browser's cookie alone", async () => {
    const res = await handleUnlock(
      call("DELETE", "unlock/devices/dev-2", undefined, { authorization: "Bearer t", cookie: mine }),
      segs("devices/dev-2"),
      core(204, null)
    );
    expect(res.status).toBe(204);
    expect(setCookies(res)).toEqual([]);
  });

  it("a refused deletion does not touch the cookie and keeps core's error", async () => {
    const res = await handleUnlock(
      call("DELETE", "unlock/passkeys/p1", undefined, { authorization: "Bearer t", cookie: mine }),
      segs("passkeys/p1"),
      core(409, { error: "last_credential", message: "Add another first." })
    );
    expect(res.status).toBe(409);
    expect((await res.json()).error).toBe("last_credential");
    expect(setCookies(res)).toEqual([]);
  });

  it("regenerating recovery codes forwards the bearer and returns the codes", async () => {
    const fetchMock = core(200, { codes: ["C".repeat(16)] });
    const res = await handleUnlock(
      call("POST", "unlock/recovery-codes", {}, { authorization: "Bearer t" }),
      ["recovery-codes"],
      fetchMock
    );
    expect(await res.json()).toEqual({ codes: ["C".repeat(16)] });
    expect(res.headers.get("cache-control")).toBe("no-store");
  });
});
