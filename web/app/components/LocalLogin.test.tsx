import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Mock } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.entries(vars).map(([k, v]) => `${k}=${v}`).join(",")}` : key,
}));
vi.mock("./AuthBrandPanel", () => ({
  default: ({ title, subtitle }: { title: string; subtitle: string }) => (
    <header>
      <h1>{title}</h1>
      <p>{subtitle}</p>
    </header>
  ),
}));
vi.mock("./LanguageSwitcher", () => ({ default: (): null => null }));
vi.mock("./ThemePicker", () => ({ default: (): null => null }));

const adopt = vi.fn();
const client = {
  adopt,
  authenticated: false,
  token: undefined as string | undefined,
  init: vi.fn(),
  updateToken: vi.fn(async () => false),
};
vi.mock("../lib/authClient", () => ({ getKeycloak: () => client }));

// The browser ceremonies are the one part jsdom cannot run; everything around them is real.
const support = vi.fn(() => ({ supported: true }) as { supported: boolean; reason?: string });
const getPasskey = vi.fn(async () => '{"id":"cred"}');
const createPasskey = vi.fn(async () => '{"id":"new-cred"}');
vi.mock("../lib/localAuthWebAuthn", async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  passkeySupport: () => support(),
  getPasskey: (...args: unknown[]) => (getPasskey as (...a: unknown[]) => Promise<string>)(...args),
  createPasskey: (...args: unknown[]) => (createPasskey as (...a: unknown[]) => Promise<string>)(...args),
}));

import LocalLogin from "./LocalLogin";

const assign = vi.fn();
let fetchMock: Mock<typeof fetch>;

function reply(status: number, body: unknown) {
  return new Response(body === null ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const SESSION = { accessToken: "a.b.c", expiresIn: 900, refreshExpiresIn: 100 };

type Handler = (body: Record<string, unknown>, init?: RequestInit) => Response | Promise<Response>;

/** Routes proxy calls by "METHOD /path" (path relative to /api/local-auth). Unlisted calls get safe defaults. */
function api(handlers: Record<string, Handler>, config: Record<string, unknown> = {}) {
  const table: Record<string, Handler> = {
    "GET /config": () => reply(200, { mode: "local", setupRequired: false, ...config }),
    "GET /device": () => reply(200, { known: false }),
    ...handlers,
  };
  fetchMock.mockImplementation(async (url, init) => {
    const key = `${init?.method ?? "GET"} ${String(url).replace("/api/local-auth", "")}`;
    const handler = table[key];
    if (!handler) return reply(404, { error: "not_found", message: key });
    return handler(init?.body ? JSON.parse(String(init.body)) : {}, init);
  });
}

function calls(key: string) {
  return fetchMock.mock.calls
    .filter((c) => `${c[1]?.method ?? "GET"} ${String(c[0]).replace("/api/local-auth", "")}` === key)
    .map((c) => ({ body: c[1]?.body ? JSON.parse(String(c[1].body)) : undefined, init: c[1] as RequestInit }));
}

function setUrl(search: string) {
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { ...window.location, search, pathname: "/login", assign, origin: "http://console.test", hostname: "console.test" },
  });
}

beforeEach(() => {
  setUrl("");
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal("fetch", fetchMock);
  api({});
  client.authenticated = false;
  client.token = undefined;
  support.mockReturnValue({ supported: true });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

async function type(testId: string, value: string) {
  fireEvent.change(await screen.findByTestId(testId), { target: { value } });
}

const ALL_METHODS = { credentialTypes: ["password", "pin", "passkey", "recovery"] };

describe("password-only install (core without passkeys)", () => {
  it("shows the e-mail and password form straight away, signs in through the proxy and goes to the console", async () => {
    api({ "POST /login": () => reply(200, SESSION) });
    render(<LocalLogin />);

    await type("local-login-email", " owner@x.test ");
    await type("local-login-password", "correct horse");
    fireEvent.click(screen.getByTestId("local-login-submit"));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("/"));
    expect(calls("POST /login")[0].body).toEqual({ identifier: "owner@x.test", password: "correct horse" });
    expect(adopt).toHaveBeenCalledWith(expect.objectContaining({ accessToken: "a.b.c" }));
    expect(screen.queryByTestId("local-use-password")).toBeNull();
  });

  it("remembers the address that signed in and prefills it next time", async () => {
    window.localStorage.removeItem("vd_local_last_email");
    api({ "POST /login": () => reply(200, SESSION) });
    const first = render(<LocalLogin />);
    await type("local-login-email", "owner@x.test");
    await type("local-login-password", "correct horse");
    fireEvent.click(screen.getByTestId("local-login-submit"));
    await waitFor(() => expect(assign).toHaveBeenCalled());
    first.unmount();

    render(<LocalLogin />);
    await waitFor(() =>
      expect((screen.getByTestId("local-login-email") as HTMLInputElement).value).toBe("owner@x.test")
    );
    window.localStorage.removeItem("vd_local_last_email");
  });

  it("honours ?next= only for same-origin paths", async () => {
    setUrl("?next=%2Fagents");
    api({ "POST /login": () => reply(200, SESSION) });
    render(<LocalLogin />);
    await type("local-login-email", "o@x.test");
    await type("local-login-password", "pw");
    fireEvent.click(screen.getByTestId("local-login-submit"));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/agents"));
  });

  it("shows the translated error for a wrong password and stays on the page", async () => {
    api({ "POST /login": () => reply(401, { error: "invalid_credentials", message: "x" }) });
    render(<LocalLogin />);
    await type("local-login-email", "o@x.test");
    await type("local-login-password", "wrong");
    fireEvent.click(screen.getByTestId("local-login-submit"));

    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.invalid_credentials");
    expect(assign).not.toHaveBeenCalled();
    expect(adopt).not.toHaveBeenCalled();
  });

  it("points a brand-new install at its setup link", async () => {
    api({}, { setupRequired: true });
    render(<LocalLogin />);
    expect(await screen.findByText("local.setupNeeded")).toBeTruthy();
  });

  it("a temporary-password sign-in is sent straight to the change step", async () => {
    api({ "POST /login": () => reply(200, { ...SESSION, passwordChangeRequired: true }) });
    render(<LocalLogin />);
    await type("local-login-email", "o@x.test");
    await type("local-login-password", "temporary-pass-1");
    fireEvent.click(screen.getByTestId("local-login-submit"));

    expect(await screen.findByTestId("local-change-form")).toBeTruthy();
    expect(assign).not.toHaveBeenCalled();
    expect((document.getElementById("local-current") as HTMLInputElement).value).toBe("temporary-pass-1");
  });

  it("change: sends the bearer from the held session and adopts the new one", async () => {
    setUrl("?change=1");
    client.authenticated = true;
    client.token = "held.access.token";
    api({ "POST /change-password": () => reply(200, { ...SESSION, accessToken: "n.e.w" }) });
    render(<LocalLogin />);
    expect(await screen.findByTestId("local-change-form")).toBeTruthy();

    fireEvent.change(document.getElementById("local-current")!, { target: { value: "old password 123" } });
    await type("local-new-password", "a brand new long passphrase");
    await type("local-confirm-password", "a brand new long passphrase");
    fireEvent.click(screen.getByTestId("local-password-submit"));

    await waitFor(() => expect(assign).toHaveBeenCalled());
    const call = calls("POST /change-password")[0];
    expect((call.init.headers as Record<string, string>).Authorization).toBe("Bearer held.access.token");
    expect(call.body).toEqual({ currentPassword: "old password 123", newPassword: "a brand new long passphrase" });
    expect(adopt).toHaveBeenCalledWith(expect.objectContaining({ accessToken: "n.e.w" }));
  });
});

describe("first-run setup", () => {
  it("password-only core: reads the one-time token, removes it from the address bar, checks the confirmation, then signs in", async () => {
    setUrl("?setup=vdls_TOKEN");
    const replace = vi.spyOn(window.history, "replaceState");
    api({ "POST /setup": () => reply(200, SESSION) });
    render(<LocalLogin />);

    expect(await screen.findByTestId("local-setup-form")).toBeTruthy();
    expect(screen.queryByTestId("local-setup-choose")).toBeNull();
    expect(replace).toHaveBeenCalledWith(null, "", "/login");

    await type("local-new-password", "a long enough passphrase");
    await type("local-confirm-password", "something else entirely");
    fireEvent.click(screen.getByTestId("local-password-submit"));
    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.mismatch");
    expect(calls("POST /setup")).toHaveLength(0);

    await type("local-confirm-password", "a long enough passphrase");
    fireEvent.click(screen.getByTestId("local-password-submit"));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/"));
    expect(calls("POST /setup")[0].body).toEqual({ token: "vdls_TOKEN", password: "a long enough passphrase" });
  });

  it("offers passkey (recommended), PIN and password, in that order", async () => {
    setUrl("?setup=vdls_TOKEN");
    api({}, ALL_METHODS);
    render(<LocalLogin />);

    const choose = await screen.findByTestId("local-setup-choose");
    const buttons = within(choose).getAllByRole("button");
    expect(buttons.map((b) => b.getAttribute("data-testid"))).toEqual([
      "local-setup-passkey",
      "local-setup-pin",
      "local-setup-password",
    ]);
    expect(within(buttons[0]).getByText("local.setup.recommended")).toBeTruthy();
    expect((buttons[0] as HTMLButtonElement).disabled).toBe(false);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("local.setup.chooseTitle");
  });

  it("explains why a passkey is unavailable here and disables only that option", async () => {
    setUrl("?setup=vdls_TOKEN");
    support.mockReturnValue({ supported: false, reason: "ip_origin" });
    api({}, ALL_METHODS);
    render(<LocalLogin />);

    const passkey = (await screen.findByTestId("local-setup-passkey")) as HTMLButtonElement;
    expect(passkey.disabled).toBe(true);
    expect(passkey.textContent).toContain("local.passkey.unavailable.ip_origin");
    expect((screen.getByTestId("local-setup-pin") as HTMLButtonElement).disabled).toBe(false);
  });

  it("PIN setup: checks the PIN locally, posts it with the setup token and ends on the recovery codes", async () => {
    setUrl("?setup=vdls_TOKEN&next=%2Fagents");
    api(
      {
        "POST /unlock/setup/pin": () =>
          reply(200, { session: SESSION, deviceId: "dev-1", recoveryCodes: ["ABCDEFGHJKLMNPQR", "STUVWXYZ23456789"] }),
      },
      ALL_METHODS
    );
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-setup-pin"));

    await type("local-pin-new", "111111");
    await type("local-pin-confirm", "111111");
    fireEvent.click(screen.getByTestId("local-pin-submit"));
    expect((await screen.findByTestId("local-login-error")).textContent).toContain("local.errors.weak_pin");

    await type("local-pin-new", "493827");
    await type("local-pin-confirm", "493828");
    fireEvent.click(screen.getByTestId("local-pin-submit"));
    await waitFor(() => expect(screen.getByTestId("local-login-error").textContent).toBe("local.pin.mismatch"));
    expect(calls("POST /unlock/setup/pin")).toHaveLength(0);

    await type("local-pin-confirm", "493827");
    fireEvent.click(screen.getByTestId("local-pin-submit"));

    const list = await screen.findByTestId("local-codes-list");
    expect(calls("POST /unlock/setup/pin")[0].body).toMatchObject({ token: "vdls_TOKEN", pin: "493827" });
    expect(adopt).toHaveBeenCalledWith(expect.objectContaining({ accessToken: "a.b.c" }));
    expect(within(list).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "ABCD-EFGH-JKLM-NPQR",
      "STUV-WXYZ-2345-6789",
    ]);
    // The codes are shown once, so the person cannot move on until they say they saved them.
    const next = screen.getByTestId("local-codes-continue") as HTMLButtonElement;
    expect(next.disabled).toBe(true);
    expect(assign).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("local-codes-saved"));
    expect(next.disabled).toBe(false);
    fireEvent.click(next);
    expect(assign).toHaveBeenCalledWith("/agents");
  });

  it("passkey setup: requests options with the setup token, runs the ceremony, registers, shows the codes", async () => {
    setUrl("?setup=vdls_TOKEN");
    const publicKey = { challenge: "abc", rp: { id: "console.test", name: "VegaDuta" }, user: { id: "u", name: "owner", displayName: "owner" } };
    api(
      {
        "POST /unlock/passkey/register-options": () => reply(200, { challengeId: "chal-1", publicKey }),
        "POST /unlock/passkey/register": () =>
          reply(200, { passkey: { id: "p1", label: "x" }, session: SESSION, recoveryCodes: ["ABCDEFGHJKLMNPQR"] }),
      },
      ALL_METHODS
    );
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-setup-passkey"));

    await screen.findByTestId("local-codes-list");
    expect(calls("POST /unlock/passkey/register-options")[0].body).toEqual({ setupToken: "vdls_TOKEN" });
    expect(createPasskey).toHaveBeenCalledWith(publicKey);
    expect(calls("POST /unlock/passkey/register")[0].body).toMatchObject({
      challengeId: "chal-1",
      credential: '{"id":"new-cred"}',
      setupToken: "vdls_TOKEN",
    });
    expect(adopt).toHaveBeenCalled();
  });

  it("passkey setup: a dismissed prompt is a calm message and the choices stay", async () => {
    setUrl("?setup=vdls_TOKEN");
    const { PasskeyError } = await import("../lib/localAuthWebAuthn");
    createPasskey.mockRejectedValueOnce(new PasskeyError("passkey_cancelled"));
    api(
      { "POST /unlock/passkey/register-options": () => reply(200, { challengeId: "c", publicKey: {} }) },
      ALL_METHODS
    );
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-setup-passkey"));
    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.passkey_cancelled");
    expect(screen.getByTestId("local-setup-choose")).toBeTruthy();
  });

  it("an expired setup link is reported", async () => {
    setUrl("?setup=old");
    api({ "POST /unlock/setup/pin": () => reply(401, { error: "invalid_setup_token", message: "x" }) }, ALL_METHODS);
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-setup-pin"));
    await type("local-pin-new", "493827");
    await type("local-pin-confirm", "493827");
    fireEvent.click(screen.getByTestId("local-pin-submit"));
    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.invalid_setup_token");
  });
});

describe("sign-in with a passkey, a PIN or a recovery code", () => {
  it("leads with the passkey and hides the e-mail field until 'use a password instead'", async () => {
    api({}, ALL_METHODS);
    render(<LocalLogin />);

    expect(await screen.findByTestId("local-passkey-signin")).toBeTruthy();
    expect(screen.queryByTestId("local-login-email")).toBeNull();
    expect(screen.queryByTestId("local-pin-form")).toBeNull();
    fireEvent.click(screen.getByTestId("local-use-password"));
    expect(screen.getByTestId("local-login-email")).toBeTruthy();
    fireEvent.click(screen.getByTestId("local-back"));
    expect(screen.queryByTestId("local-login-email")).toBeNull();
  });

  it("passkey: asks for a challenge, runs the browser prompt, presents the assertion", async () => {
    const publicKey = { challenge: "abc", rpId: "console.test", allowCredentials: [] as unknown[] };
    api(
      {
        "POST /unlock/passkey/login-options": () => reply(200, { challengeId: "chal-9", publicKey }),
        "POST /unlock/login": () => reply(200, SESSION),
      },
      ALL_METHODS
    );
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-passkey-signin"));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("/"));
    expect(getPasskey).toHaveBeenCalledWith(publicKey);
    expect(calls("POST /unlock/login")[0].body).toEqual({
      type: "passkey",
      identifier: "chal-9",
      credentials: { assertion: '{"id":"cred"}' },
    });
    expect(adopt).toHaveBeenCalled();
  });

  it("passkey: a refused assertion shows the passkey message", async () => {
    api(
      {
        "POST /unlock/passkey/login-options": () => reply(200, { challengeId: "c", publicKey: {} }),
        "POST /unlock/login": () => reply(401, { error: "invalid_credentials", message: "x" }),
      },
      ALL_METHODS
    );
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-passkey-signin"));
    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.passkey_invalid");
  });

  it("without passkey support the password form is the screen and the reason is shown", async () => {
    support.mockReturnValue({ supported: false, reason: "insecure_context" });
    api({}, ALL_METHODS);
    render(<LocalLogin />);

    expect(await screen.findByTestId("local-login-email")).toBeTruthy();
    expect(screen.queryByTestId("local-passkey-signin")).toBeNull();
    expect((await screen.findByTestId("local-passkey-unavailable")).textContent).toBe(
      "local.passkey.unavailable.insecure_context"
    );
    expect(screen.queryByTestId("local-back")).toBeNull();
  });

  describe("PIN pad", () => {
    const withDevice = (extra: Record<string, Handler> = {}) =>
      api({ "GET /device": () => reply(200, { known: true, id: "dev-1", label: "Studio" }), ...extra }, ALL_METHODS);

    it("shows only when this browser holds a device, with a numeric password-type field that is not autofilled", async () => {
      api({}, ALL_METHODS);
      const none = render(<LocalLogin />);
      await screen.findByTestId("local-passkey-signin");
      expect(screen.queryByTestId("local-pin-form")).toBeNull();
      none.unmount();

      withDevice();
      render(<LocalLogin />);
      const field = (await screen.findByTestId("local-pin")) as HTMLInputElement;
      expect(field.type).toBe("password");
      expect(field.getAttribute("inputmode")).toBe("numeric");
      expect(field.getAttribute("autocomplete")).toBe("off");
      expect(field.maxLength).toBe(12);
      expect(screen.getByLabelText("local.signin.pinLabelDevice:device=Studio")).toBe(field);
    });

    it("accepts typed digits only, and the on-screen keypad edits the same field", async () => {
      withDevice();
      render(<LocalLogin />);
      const field = (await screen.findByTestId("local-pin")) as HTMLInputElement;
      fireEvent.change(field, { target: { value: "12ab34" } });
      expect(field.value).toBe("1234");
      fireEvent.click(screen.getByTestId("pin-key-5"));
      fireEvent.click(screen.getByTestId("pin-key-6"));
      expect(field.value).toBe("123456");
      fireEvent.click(screen.getByTestId("pin-key-delete"));
      expect(field.value).toBe("12345");
      // too short to submit
      expect((screen.getByTestId("local-pin-submit") as HTMLButtonElement).disabled).toBe(true);
    });

    it("signs in by sending only the PIN: the device id and secret are the proxy's business", async () => {
      withDevice({ "POST /unlock/login": () => reply(200, SESSION) });
      render(<LocalLogin />);
      await type("local-pin", "493827");
      fireEvent.click(screen.getByTestId("local-pin-submit"));
      await waitFor(() => expect(assign).toHaveBeenCalledWith("/"));
      expect(calls("POST /unlock/login")[0].body).toEqual({ type: "pin", pin: "493827" });
    });

    it("a wrong PIN clears the field and says so", async () => {
      withDevice({ "POST /unlock/login": () => reply(401, { error: "invalid_credentials", message: "x" }) });
      render(<LocalLogin />);
      await type("local-pin", "000000");
      fireEvent.click(screen.getByTestId("local-pin-submit"));
      expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.pin_invalid");
      expect((screen.getByTestId("local-pin") as HTMLInputElement).value).toBe("");
    });

    it("a revoked device drops the PIN pad and points at the other ways in", async () => {
      let known = true;
      api(
        {
          "GET /device": () => reply(200, known ? { known: true, id: "dev-1" } : { known: false }),
          "POST /unlock/login": () => {
            known = false; // the proxy cleared the cookie on device_revoked
            return reply(401, { error: "device_revoked", message: "x" });
          },
        },
        ALL_METHODS
      );
      render(<LocalLogin />);
      await type("local-pin", "000000");
      fireEvent.click(screen.getByTestId("local-pin-submit"));

      await waitFor(() => expect(screen.queryByTestId("local-pin-form")).toBeNull());
      expect(screen.getByTestId("local-passkey-signin")).toBeTruthy();
      expect(screen.getByTestId("local-login-error").textContent).toBe("local.errors.device_revoked");
    });

    it("throttling disables sign-in and shows a countdown from retryAfterSeconds", async () => {
      withDevice({
        "POST /unlock/login": () => reply(429, { error: "too_many_attempts", message: "x", retryAfterSeconds: 45 }),
      });
      render(<LocalLogin />);
      await type("local-pin", "000000");
      fireEvent.click(screen.getByTestId("local-pin-submit"));

      expect((await screen.findByTestId("local-login-error")).textContent).toContain("seconds=45");
      expect(screen.getByTestId("local-lockout-countdown").textContent).toContain("seconds=45");
      expect((screen.getByTestId("local-pin") as HTMLInputElement).disabled).toBe(true);
      expect((screen.getByTestId("local-passkey-signin") as HTMLButtonElement).disabled).toBe(true);
    });
  });

  it("tells a throttled password sign-in how long to wait", async () => {
    api({ "POST /login": () => reply(429, { error: "too_many_attempts", message: "x", retryAfterSeconds: 45 }) });
    render(<LocalLogin />);
    await type("local-login-email", "o@x.test");
    await type("local-login-password", "wrong");
    fireEvent.click(screen.getByTestId("local-login-submit"));
    expect((await screen.findByTestId("local-login-error")).textContent).toContain("seconds=45");
    expect((screen.getByTestId("local-login-submit") as HTMLButtonElement).disabled).toBe(true);
  });

  it("'I lost my device': a recovery code signs in and leads to the security page to set up a new way", async () => {
    client.authenticated = true;
    client.token = "held.token";
    api(
      {
        "POST /unlock/login": () => reply(200, SESSION),
        "GET /unlock/methods": () =>
          reply(200, { hasPassword: false, passkeys: [], devices: [], recoveryCodesRemaining: 7 }),
      },
      ALL_METHODS
    );
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-lost-device"));
    await type("local-recovery-code", "abcd efgh-jklm npqr");
    fireEvent.click(screen.getByTestId("local-recovery-submit"));

    expect(await screen.findByTestId("local-codes-remaining")).toBeTruthy();
    expect(calls("POST /unlock/login")[0].body).toEqual({
      type: "recovery",
      identifier: "recovery",
      credentials: { code: "ABCDEFGHJKLMNPQR" },
    });
    expect(screen.getByText("local.security.afterRecovery")).toBeTruthy();
    expect(assign).not.toHaveBeenCalled();
  });

  it("a bad recovery code gets its own message", async () => {
    api({ "POST /unlock/login": () => reply(401, { error: "invalid_credentials", message: "x" }) }, ALL_METHODS);
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-lost-device"));
    await type("local-recovery-code", "AAAA-BBBB-CCCC-DDDD");
    fireEvent.click(screen.getByTestId("local-recovery-submit"));
    expect((await screen.findByTestId("local-login-error")).textContent).toBe("local.errors.recovery_invalid");
  });

  it("an install without recovery codes shows no recovery link", async () => {
    api({}, { credentialTypes: ["password", "passkey"] });
    render(<LocalLogin />);
    await screen.findByTestId("local-passkey-signin");
    expect(screen.queryByTestId("local-lost-device")).toBeNull();
  });
});

describe("re-authentication (?reauth=1)", () => {
  it("changes the heading, explains why, and returns to ?next= after any sign-in", async () => {
    setUrl("?reauth=1&next=%2Flogin%3Fsecurity%3D1");
    api({ "POST /login": () => reply(200, SESSION) });
    render(<LocalLogin />);

    expect(await screen.findByTestId("local-reauth-note")).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("local.reauth.title");
    await type("local-login-email", "o@x.test");
    await type("local-login-password", "pw");
    fireEvent.click(screen.getByTestId("local-login-submit"));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/login?security=1"));
  });

  it("a recovery code used to re-authenticate goes back to where the person came from", async () => {
    setUrl("?reauth=1&next=%2Flogin%3Fsecurity%3D1");
    api({ "POST /unlock/login": () => reply(200, SESSION) }, ALL_METHODS);
    render(<LocalLogin />);
    fireEvent.click(await screen.findByTestId("local-lost-device"));
    await type("local-recovery-code", "AAAABBBBCCCCDDDD");
    fireEvent.click(screen.getByTestId("local-recovery-submit"));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/login?security=1"));
  });
});
