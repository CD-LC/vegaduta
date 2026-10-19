import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Mock } from "vitest";

const getPasskey = vi.fn(async (_options: unknown) => '{"assertion":true}');
const createPasskey = vi.fn(async (_options: unknown) => '{"registration":true}');
vi.mock("./localAuthWebAuthn", async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  getPasskey: (o: unknown) => getPasskey(o),
  createPasskey: (o: unknown) => createPasskey(o),
}));

import { LocalAuthError } from "./localAuthClient";
import {
  addPasskey,
  enrolDevice,
  fetchDeviceStatus,
  fetchLocalAuthConfig,
  listMethods,
  passkeyLogin,
  pinLogin,
  recoveryLogin,
  regenerateRecoveryCodes,
  removeDevice,
  removePasskey,
  setupWithPasskey,
  setupWithPin,
} from "./localAuthUnlock";

let fetchMock: Mock<typeof fetch>;
const reply = (status: number, body: unknown) =>
  new Response(body === null ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const sent = (i = 0) => {
  const [url, init] = fetchMock.mock.calls[i];
  return { url: String(url), method: init?.method, headers: (init?.headers ?? {}) as Record<string, string>, body: init?.body ? JSON.parse(String(init.body)) : undefined, credentials: init?.credentials };
};

beforeEach(() => {
  fetchMock = vi.fn<typeof fetch>(async () => reply(200, {}));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("config and device status", () => {
  it("reads credentialTypes and setupRequired from /config", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { setupRequired: true, credentialTypes: ["password", "passkey", 7] }));
    expect(await fetchLocalAuthConfig()).toEqual({ setupRequired: true, credentialTypes: ["password", "passkey"] });
    expect(sent().url).toBe("/api/local-auth/config");
    expect(sent().method).toBe("GET");
  });

  it("an older core, or a failure, means password only", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { setupRequired: false }));
    expect((await fetchLocalAuthConfig()).credentialTypes).toEqual(["password"]);
    fetchMock.mockRejectedValueOnce(new TypeError("down"));
    expect(await fetchLocalAuthConfig()).toEqual({ setupRequired: false, credentialTypes: ["password"] });
  });

  it("device status is unknown unless the proxy says known, and never throws", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { known: true, id: "d1", label: "Studio" }));
    expect(await fetchDeviceStatus()).toEqual({ known: true, id: "d1", label: "Studio" });
    expect(sent().url).toBe("/api/local-auth/device");
    fetchMock.mockResolvedValueOnce(reply(200, { known: false }));
    expect(await fetchDeviceStatus()).toEqual({ known: false });
    fetchMock.mockResolvedValueOnce(reply(500, { error: "x" }));
    expect(await fetchDeviceStatus()).toEqual({ known: false });
  });
});

describe("sign-in requests", () => {
  it("PIN login sends only the PIN; the device is the proxy's cookie", async () => {
    await pinLogin("493827");
    expect(sent()).toMatchObject({ url: "/api/local-auth/unlock/login", method: "POST", credentials: "same-origin", body: { type: "pin", pin: "493827" } });
  });

  it("recovery login sends the normalised code under the literal identifier", async () => {
    await recoveryLogin(" abcd-efgh jklm-npqr ");
    expect(sent().body).toEqual({ type: "recovery", identifier: "recovery", credentials: { code: "ABCDEFGHJKLMNPQR" } });
  });

  it("passkey login: options, then the browser prompt with the server's publicKey, then the assertion string", async () => {
    const publicKey = { challenge: "abc", rpId: "console.test" };
    fetchMock.mockResolvedValueOnce(reply(200, { challengeId: "chal-1", publicKey })).mockResolvedValueOnce(reply(200, { accessToken: "a" }));
    await passkeyLogin();
    expect(sent(0)).toMatchObject({ url: "/api/local-auth/unlock/passkey/login-options", body: {} });
    expect(getPasskey).toHaveBeenCalledWith(publicKey);
    expect(sent(1).body).toEqual({ type: "passkey", identifier: "chal-1", credentials: { assertion: '{"assertion":true}' } });
  });

  it("passkey login does not call core with an assertion when the prompt was dismissed", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { challengeId: "c", publicKey: {} }));
    getPasskey.mockRejectedValueOnce(new Error("cancelled"));
    await expect(passkeyLogin()).rejects.toThrow("cancelled");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("surfaces core's stable error code and retryAfterSeconds", async () => {
    fetchMock.mockResolvedValueOnce(reply(429, { error: "too_many_attempts", message: "slow", retryAfterSeconds: 30 }));
    await expect(pinLogin("000000")).rejects.toMatchObject({ status: 429, code: "too_many_attempts", retryAfterSeconds: 30 });
  });
});

describe("setup requests", () => {
  it("PIN setup sends token, pin and the device label", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { session: { accessToken: "a" }, recoveryCodes: ["x"] }));
    const res = await setupWithPin("vdls_T", "493827", "Mac");
    expect(sent()).toMatchObject({ url: "/api/local-auth/unlock/setup/pin", body: { token: "vdls_T", pin: "493827", label: "Mac" } });
    expect(res.recoveryCodes).toEqual(["x"]);
  });

  it("passkey setup carries the setup token on both calls and no bearer", async () => {
    const publicKey = { challenge: "abc" };
    fetchMock
      .mockResolvedValueOnce(reply(200, { challengeId: "chal-2", publicKey }))
      .mockResolvedValueOnce(reply(200, { passkey: { id: "p" }, session: { accessToken: "a" }, recoveryCodes: ["c1"] }));
    const res = await setupWithPasskey("vdls_T", "Mac");
    expect(sent(0).body).toEqual({ setupToken: "vdls_T" });
    expect(createPasskey).toHaveBeenCalledWith(publicKey);
    expect(sent(1).body).toEqual({ challengeId: "chal-2", credential: '{"registration":true}', label: "Mac", setupToken: "vdls_T" });
    expect(sent(0).headers.Authorization).toBeUndefined();
    expect(res).toEqual({ session: { accessToken: "a" }, recoveryCodes: ["c1"] });
  });
});

describe("account requests", () => {
  it("send the bearer and the right method and path", async () => {
    fetchMock.mockImplementation(async () => reply(200, { passkeys: [], devices: [], hasPassword: true, recoveryCodesRemaining: 0, codes: ["c"], deviceId: "d", passkey: { id: "p" }, challengeId: "x", publicKey: {} }));
    await listMethods("tok");
    expect(sent(0)).toMatchObject({ url: "/api/local-auth/unlock/methods", method: "GET", headers: { Authorization: "Bearer tok" } });
    expect(sent(0).body).toBeUndefined();

    await enrolDevice("tok", "493827", "Kitchen");
    expect(sent(1)).toMatchObject({ url: "/api/local-auth/unlock/devices", method: "POST", body: { pin: "493827", label: "Kitchen" }, headers: { Authorization: "Bearer tok" } });

    await removeDevice("tok", "dev/1");
    expect(sent(2)).toMatchObject({ url: "/api/local-auth/unlock/devices/dev%2F1", method: "DELETE", headers: { Authorization: "Bearer tok" } });

    await removePasskey("tok", "p1");
    expect(sent(3)).toMatchObject({ url: "/api/local-auth/unlock/passkeys/p1", method: "DELETE" });

    expect(await regenerateRecoveryCodes("tok")).toEqual(["c"]);
    expect(sent(4)).toMatchObject({ url: "/api/local-auth/unlock/recovery-codes", method: "POST", headers: { Authorization: "Bearer tok" } });

    await addPasskey("tok", "Mac");
    expect(sent(5)).toMatchObject({ url: "/api/local-auth/unlock/passkey/register-options", headers: { Authorization: "Bearer tok" } });
    expect(sent(6)).toMatchObject({ url: "/api/local-auth/unlock/passkey/register", headers: { Authorization: "Bearer tok" }, body: { credential: '{"registration":true}', label: "Mac" } });
  });

  it("a stale freshness window is a LocalAuthError with its code", async () => {
    fetchMock.mockResolvedValueOnce(reply(403, { error: "reauth_required", message: "again" }));
    const err = await enrolDevice("tok", "493827", "x").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(LocalAuthError);
    expect(err).toMatchObject({ status: 403, code: "reauth_required" });
  });

  it("a 204 resolves", async () => {
    fetchMock.mockResolvedValueOnce(reply(204, null));
    await expect(removePasskey("tok", "p1")).resolves.toBeUndefined();
  });
});
