import { describe, it, expect, vi } from "vitest";

// --- keycloak-js mock -------------------------------------------------------
// Conservative, shape-accurate mock. Two deliberate robustness choices:
//
//  * vi.hoisted(): vi.mock() is hoisted above imports, and its factory may NOT
//    reference ordinary top-level consts declared later (they aren't hoisted).
//    Defining the stubs + class inside vi.hoisted() makes them available to
//    both the factory and the assertions without hitting that trap.
//  * An explicit class matching the real Keycloak instance surface auth.ts
//    touches (init/login/logout/updateToken + token/tokenParsed/realmAccess),
//    with realistic return types (init & updateToken -> Promise<boolean>,
//    login/logout -> Promise<void>) rather than a loose catch-all.
//
// auth.ts resolves the constructor via `const Keycloak = mod.default || mod`
// (CJS/ESM interop guard, itself a baked-in fix), so we expose the class on
// `.default` to exercise that branch.
const kc = vi.hoisted(() => {
  const initMock = vi.fn<(options?: Record<string, unknown>) => Promise<boolean>>(() => Promise.resolve(true));
  const loginMock = vi.fn(() => Promise.resolve());
  const logoutMock = vi.fn(() => Promise.resolve());
  const updateTokenMock = vi.fn(() => Promise.resolve(true));
  class KeycloakMock {
    token = "tok-123";
    // OSS keeps tokens in memory only (no sessionStorage copy), so logout()'s
    // id_token_hint comes from the live instance, as with real keycloak-js.
    idToken = "id-1";
    authenticated = true;
    realmAccess = { roles: ["tenant-admin"] as string[] };
    resourceAccess = {};
    tokenParsed: Record<string, unknown> = {
      preferred_username: "alice",
      email: "a@b.c",
      sub: "sub-1",
    };
    constructor(_config: { url: string; realm: string; clientId: string }) {}
    init = initMock;
    login = loginMock;
    logout = logoutMock;
    updateToken = updateTokenMock;
  }
  return { initMock, loginMock, logoutMock, updateTokenMock, KeycloakMock };
});

vi.mock("keycloak-js", () => ({ default: kc.KeycloakMock }));

// Imported AFTER the mock is registered. auth keeps a module-level singleton +
// memoized initPromise, so the tests below deliberately share that state.
import {
  auth,
  ADMIN_ROLE,
  PLATFORM_ADMIN_ROLE,
  parseSilentAuthError,
  isInteractionRequiredError,
} from "./auth";

describe("auth (Keycloak lifecycle)", () => {
  it("exposes the realm role-name constants", () => {
    expect(ADMIN_ROLE).toBe("tenant-admin");
    expect(PLATFORM_ADMIN_ROLE).toBe("platform-admin");
  });

  // The core baked-in fix: init() is memoized so keycloak-js init() runs exactly
  // once even under React-StrictMode's double-invoked effects — the guard
  // against the infinite-redirect-loop regression. This is the only test that
  // calls init(), so the call-count assertion is order-independent.
  it("memoizes init(): one kc.init despite concurrent repeat calls, with check-sso + PKCE", async () => {
    const results = await Promise.all([auth.init(), auth.init(), auth.init()]);
    expect(kc.initMock).toHaveBeenCalledTimes(1);
    const opts = kc.initMock.mock.calls[0][0] as Record<string, unknown>;
    expect(opts).toMatchObject({ onLoad: "check-sso", pkceMethod: "S256", checkLoginIframe: false });
    // All callers resolve to the same memoized promise value.
    expect(new Set(results).size).toBe(1);
  });

  it("getToken() refreshes (updateToken(30)) then returns the current token", async () => {
    const token = await auth.getToken();
    expect(kc.updateTokenMock).toHaveBeenCalledWith(30);
    expect(token).toBe("tok-123");
  });

  it("getRoles / getUsername / getUserId read the parsed token", () => {
    expect(auth.getRoles()).toEqual(["tenant-admin"]);
    expect(auth.getUsername()).toBe("alice");
    expect(auth.getUserId()).toBe("sub-1");
  });

  it("getToken() prompts for reauth instead of redirecting when refresh fails", async () => {
    // Deliberate since 2026-08-04: a single failed demand-refresh must NOT
    // kc.login() - that was an unconditional full-page redirect that could
    // yank an actively-working user out over one flaky network blip. The
    // contract is: resolve null, raise the "vd:session-needs-reauth" event
    // IdleLogout turns into a stay-signed-in prompt, and leave login() for
    // the user's own click.
    kc.updateTokenMock.mockRejectedValueOnce({ error: "expired" });
    const events: Event[] = [];
    window.addEventListener("vd:session-needs-reauth", (e) => events.push(e));
    const token = await auth.getToken();
    expect(token).toBeNull();
    expect(kc.loginMock).not.toHaveBeenCalled();
    expect(events.length).toBeGreaterThan(0);
  });
});

// The whole point of these two helpers is that keycloak-js 25.0.6 throws the
// distinction away: processCallback() resolves a prompt=none failure as plain
// "not authenticated" and processInit() replaceState()s the reason out of the
// URL first. Everything downstream (AuthProvider showing a recovery screen
// instead of the marketing landing over a LIVE session) hangs off classifying
// that reason correctly, so it is tested directly. Both are pure functions of
// their argument - deliberately independent of the keycloak-js singleton.
describe("silent (prompt=none) auth errors", () => {
  it("reads error + error_description out of the fragment (keycloak-js's default responseMode)", () => {
    const err = parseSilentAuthError(
      "https://app.example.com/#state=abc&error=interaction_required&error_description=Account%20is%20not%20fully%20set%20up"
    );
    expect(err).toEqual({
      error: "interaction_required",
      errorDescription: "Account is not fully set up",
    });
  });

  it("reads the same params from the query string (responseMode=query clients)", () => {
    const err = parseSilentAuthError("https://app.example.com/?error=login_required&state=abc");
    expect(err).toEqual({ error: "login_required", errorDescription: null });
  });

  it("returns null for a successful callback or an ordinary URL", () => {
    expect(parseSilentAuthError("https://app.example.com/#code=xyz&state=abc")).toBeNull();
    expect(parseSilentAuthError("https://app.example.com/agents")).toBeNull();
    expect(parseSilentAuthError("not a url")).toBeNull();
  });

  it("classifies interaction_required as 'session alive, finish interactively'", () => {
    // The exact answer Keycloak gives a prompt=none request while a required
    // action (VERIFY_PROFILE) is pending - the bug this suite exists for.
    expect(isInteractionRequiredError({ error: "interaction_required", errorDescription: null })).toBe(true);
    expect(isInteractionRequiredError({ error: "consent_required", errorDescription: null })).toBe(true);
    expect(isInteractionRequiredError({ error: "account_selection_required", errorDescription: null })).toBe(true);
  });

  it("classifies login_required (and nothing/unknown) as genuinely logged out", () => {
    expect(isInteractionRequiredError({ error: "login_required", errorDescription: null })).toBe(false);
    expect(isInteractionRequiredError({ error: "server_error", errorDescription: null })).toBe(false);
    expect(isInteractionRequiredError(null)).toBe(false);
    expect(isInteractionRequiredError(undefined)).toBe(false);
  });
});

// Last on purpose: logout() discards the module's keycloak-js singleton.
describe("logout", () => {
  it("clears every per-person trace, then ends the Keycloak session with id_token_hint", async () => {
    const replace = vi.fn();
    const realLocation = window.location;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { origin: "https://app.example.test", protocol: "https:", href: "https://app.example.test/agents", replace },
    });
    try {
      window.localStorage.setItem("vd_authed", "1");
      window.localStorage.setItem("vd_last_activity", "123");
      window.localStorage.setItem("kc-callback-abc", "{}");
      window.sessionStorage.setItem("vd_kc_session", JSON.stringify({ token: "t", refreshToken: "r", idToken: "id-1" }));
      document.cookie = "vd_authed=1; Path=/";

      await auth.logout();

      expect(window.localStorage.getItem("vd_authed")).toBeNull();
      expect(window.localStorage.getItem("vd_last_activity")).toBeNull();
      expect(window.localStorage.getItem("kc-callback-abc")).toBeNull();
      expect(window.sessionStorage.getItem("vd_kc_session")).toBeNull();
      expect(document.cookie).not.toContain("vd_authed=1");
      expect(replace).toHaveBeenCalledTimes(1);
      const url = new URL(replace.mock.calls[0][0]);
      expect(url.pathname).toMatch(/\/realms\/[^/]+\/protocol\/openid-connect\/logout$/);
      expect(url.searchParams.get("post_logout_redirect_uri")).toBe("https://app.example.test");
      expect(url.searchParams.get("id_token_hint")).toBeTruthy();
    } finally {
      Object.defineProperty(window, "location", { configurable: true, value: realLocation });
    }
  });
});
