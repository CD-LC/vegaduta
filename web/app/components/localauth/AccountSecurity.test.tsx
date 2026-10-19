import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Mock } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.entries(vars).map(([k, v]) => `${k}=${v}`).join(",")}` : key,
}));

const client = {
  authenticated: true,
  token: "held.token",
  init: vi.fn(async () => true),
  updateToken: vi.fn(async () => false),
};
vi.mock("../../lib/authClient", () => ({ getKeycloak: () => client }));

const support = vi.fn(() => ({ supported: true }) as { supported: boolean; reason?: string });
const createPasskey = vi.fn(async (_options: unknown) => '{"id":"new-cred"}');
vi.mock("../../lib/localAuthWebAuthn", async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  passkeySupport: () => support(),
  createPasskey: (options: unknown) => createPasskey(options),
}));

import AccountSecurity from "./AccountSecurity";

const assign = vi.fn();
let fetchMock: Mock<typeof fetch>;

const reply = (status: number, body: unknown) =>
  new Response(body === null ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const METHODS = {
  hasPassword: true,
  passkeys: [{ id: "p1", label: "Chrome on macOS", createdAt: "2026-10-01T10:00:00Z", lastUsedAt: "2026-10-18T08:00:00Z", backedUp: true }],
  devices: [
    { id: "dev-1", label: "Studio", createdAt: "2026-10-02T10:00:00Z", lastUsedAt: null as string | null },
    { id: "dev-2", label: "Kitchen iPad", createdAt: "2026-10-03T10:00:00Z", lastUsedAt: null as string | null },
  ],
  recoveryCodesRemaining: 6,
};

type Handler = (body: Record<string, unknown>, init?: RequestInit) => Response | Promise<Response>;

function api(handlers: Record<string, Handler> = {}) {
  const table: Record<string, Handler> = {
    "GET /unlock/methods": () => reply(200, METHODS),
    "GET /device": () => reply(200, { known: true, id: "dev-1", label: "Studio" }),
    ...handlers,
  };
  fetchMock.mockImplementation(async (url, init) => {
    const key = `${init?.method ?? "GET"} ${String(url).replace("/api/local-auth", "")}`;
    const handler = table[key];
    return handler ? handler(init?.body ? JSON.parse(String(init.body)) : {}, init) : reply(404, { error: "not_found", message: key });
  });
}

const calls = (key: string) =>
  fetchMock.mock.calls
    .filter((c) => `${c[1]?.method ?? "GET"} ${String(c[0]).replace("/api/local-auth", "")}` === key)
    .map((c) => ({ body: c[1]?.body ? JSON.parse(String(c[1].body)) : undefined, headers: (c[1]?.headers ?? {}) as Record<string, string> }));

beforeEach(() => {
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal("fetch", fetchMock);
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { ...window.location, assign, hostname: "console.test", pathname: "/login", search: "?security=1" },
  });
  client.authenticated = true;
  client.token = "held.token";
  support.mockReturnValue({ supported: true });
  api();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("AccountSecurity", () => {
  it("lists passkeys, PIN devices (marking this browser) and the recovery codes left", async () => {
    render(<AccountSecurity />);
    expect(await screen.findByTestId("local-passkey-p1")).toBeTruthy();
    expect(within(screen.getByTestId("local-passkey-p1")).getByText("local.security.synced")).toBeTruthy();
    expect(within(screen.getByTestId("local-device-dev-1")).getByText("local.security.thisBrowser")).toBeTruthy();
    expect(within(screen.getByTestId("local-device-dev-2")).queryByText("local.security.thisBrowser")).toBeNull();
    expect(screen.getByTestId("local-codes-remaining").textContent).toBe("local.security.codesLeft:count=6");
    expect(calls("GET /unlock/methods")[0].headers.Authorization).toBe("Bearer held.token");
    // the page links to the existing password change only when there is a password
    expect(screen.getByText("local.security.changePassword")).toBeTruthy();
  });

  it("restores the session on a direct visit, and says so when there is none", async () => {
    client.authenticated = false;
    client.token = undefined;
    client.init.mockImplementationOnce(async () => false);
    render(<AccountSecurity />);
    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.invalid_session");
    expect(client.init).toHaveBeenCalled();
    expect(calls("GET /unlock/methods")).toHaveLength(0);
  });

  it("adds a passkey: options with the bearer, the browser prompt, then register, then a refreshed list", async () => {
    const publicKey = { challenge: "abc", rp: { id: "console.test", name: "VegaDuta" }, user: { id: "u", name: "o", displayName: "o" } };
    api({
      "POST /unlock/passkey/register-options": () => reply(200, { challengeId: "chal-1", publicKey }),
      "POST /unlock/passkey/register": () => reply(200, { passkey: { id: "p2", label: "x" } }),
    });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-add-passkey"));

    await waitFor(() => expect(calls("POST /unlock/passkey/register")).toHaveLength(1));
    expect(calls("POST /unlock/passkey/register-options")[0].headers.Authorization).toBe("Bearer held.token");
    expect(calls("POST /unlock/passkey/register-options")[0].body).toEqual({});
    expect(createPasskey).toHaveBeenCalledWith(publicKey);
    expect(calls("POST /unlock/passkey/register")[0].body).toMatchObject({ challengeId: "chal-1", credential: '{"id":"new-cred"}' });
    expect(calls("POST /unlock/passkey/register")[0].body.setupToken).toBeUndefined();
    await waitFor(() => expect(calls("GET /unlock/methods").length).toBeGreaterThanOrEqual(2));
  });

  it("a stale sign-in becomes a confirm-it's-you prompt that returns here after signing in again", async () => {
    api({ "POST /unlock/passkey/register-options": () => reply(403, { error: "reauth_required", message: "x" }) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-add-passkey"));

    const prompt = await screen.findByTestId("local-reauth-prompt");
    expect(prompt.textContent).toContain("local.reauth.prompt");
    expect(screen.queryByTestId("local-login-error")).toBeNull();
    expect(createPasskey).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("local-reauth-button"));
    expect(assign).toHaveBeenCalledWith("/login?reauth=1&next=%2Flogin%3Fsecurity%3D1");
  });

  it("explains why a passkey cannot be added here", async () => {
    support.mockReturnValue({ supported: false, reason: "ip_origin" });
    render(<AccountSecurity />);
    expect(((await screen.findByTestId("local-add-passkey")) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("local.passkey.unavailable.ip_origin")).toBeTruthy();
  });

  it("removing needs a second click and then calls DELETE with the bearer", async () => {
    api({ "DELETE /unlock/passkeys/p1": () => reply(204, null) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-passkey-remove-p1"));
    expect(calls("DELETE /unlock/passkeys/p1")).toHaveLength(0);
    fireEvent.click(screen.getByTestId("local-passkey-confirm-p1"));
    await waitFor(() => expect(calls("DELETE /unlock/passkeys/p1")).toHaveLength(1));
    expect(calls("DELETE /unlock/passkeys/p1")[0].headers.Authorization).toBe("Bearer held.token");
  });

  it("refusing to remove the last way in shows core's reason", async () => {
    api({ "DELETE /unlock/passkeys/p1": () => reply(409, { error: "last_credential", message: "x" }) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-passkey-remove-p1"));
    fireEvent.click(screen.getByTestId("local-passkey-confirm-p1"));
    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.last_credential");
  });

  it("removes a PIN device", async () => {
    api({ "DELETE /unlock/devices/dev-2": () => reply(204, null) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-device-remove-dev-2"));
    fireEvent.click(screen.getByTestId("local-device-confirm-dev-2"));
    await waitFor(() => expect(calls("DELETE /unlock/devices/dev-2")).toHaveLength(1));
  });

  it("adds a PIN for this browser: local checks first, then POST {pin, label} and no secret in the page", async () => {
    api({ "POST /unlock/devices": () => reply(200, { deviceId: "dev-3" }) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-add-pin"));

    fireEvent.change(screen.getByTestId("local-pin-new"), { target: { value: "123456" } });
    fireEvent.change(screen.getByTestId("local-pin-confirm"), { target: { value: "123456" } });
    fireEvent.click(screen.getByTestId("local-pin-submit"));
    expect((await screen.findByTestId("local-login-error")).textContent).toContain("local.errors.weak_pin");

    fireEvent.change(screen.getByTestId("local-pin-new"), { target: { value: "493827" } });
    fireEvent.change(screen.getByTestId("local-pin-confirm"), { target: { value: "111222" } });
    fireEvent.click(screen.getByTestId("local-pin-submit"));
    await waitFor(() => expect(screen.getByTestId("local-login-error").textContent).toBe("local.pin.mismatch"));
    expect(calls("POST /unlock/devices")).toHaveLength(0);

    fireEvent.change(screen.getByTestId("local-pin-confirm"), { target: { value: "493827" } });
    fireEvent.click(screen.getByTestId("local-pin-submit"));
    await waitFor(() => expect(calls("POST /unlock/devices")).toHaveLength(1));
    expect(calls("POST /unlock/devices")[0].body).toEqual({ pin: "493827", label: expect.any(String) });
    expect(calls("POST /unlock/devices")[0].headers.Authorization).toBe("Bearer held.token");
    await waitFor(() => expect(screen.queryByTestId("local-add-pin-form")).toBeNull());
    // the device list and "this browser" are re-read from the proxy
    expect(calls("GET /device").length).toBeGreaterThanOrEqual(2);
  });

  it("a weak PIN that only core catches is reported", async () => {
    api({ "POST /unlock/devices": () => reply(400, { error: "weak_pin", message: "x" }) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-add-pin"));
    fireEvent.change(screen.getByTestId("local-pin-new"), { target: { value: "135790" } });
    fireEvent.change(screen.getByTestId("local-pin-confirm"), { target: { value: "135790" } });
    fireEvent.click(screen.getByTestId("local-pin-submit"));
    expect((await screen.findByTestId("local-login-error")).textContent).toContain("local.errors.weak_pin");
    expect(screen.getByTestId("local-add-pin-form")).toBeTruthy();
  });

  it("regenerating recovery codes needs a confirmation, then shows the new set behind the saved gate", async () => {
    api({ "POST /unlock/recovery-codes": () => reply(200, { codes: ["ABCDEFGHJKLMNPQR", "STUVWXYZ23456789"] }) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-codes-regenerate"));
    expect(calls("POST /unlock/recovery-codes")).toHaveLength(0);
    expect(screen.getByText("local.security.regenerateWarning")).toBeTruthy();
    fireEvent.click(screen.getByTestId("local-codes-regenerate-confirm"));

    const list = await screen.findByTestId("local-codes-list");
    expect(calls("POST /unlock/recovery-codes")[0].headers.Authorization).toBe("Bearer held.token");
    expect(within(list).getAllByRole("listitem")[0].textContent).toBe("ABCD-EFGH-JKLM-NPQR");
    const done = screen.getByTestId("local-codes-continue") as HTMLButtonElement;
    expect(done.disabled).toBe(true);
    fireEvent.click(screen.getByTestId("local-codes-saved"));
    fireEvent.click(done);
    expect(await screen.findByTestId("local-codes-remaining")).toBeTruthy();
    expect(screen.queryByTestId("local-codes-list")).toBeNull();
  });

  it("regenerating after the sign-in went stale asks to confirm identity", async () => {
    api({ "POST /unlock/recovery-codes": () => reply(403, { error: "reauth_required", message: "x" }) });
    render(<AccountSecurity />);
    fireEvent.click(await screen.findByTestId("local-codes-regenerate"));
    fireEvent.click(screen.getByTestId("local-codes-regenerate-confirm"));
    expect(await screen.findByTestId("local-reauth-prompt")).toBeTruthy();
    expect(screen.queryByTestId("local-codes-list")).toBeNull();
  });
});
