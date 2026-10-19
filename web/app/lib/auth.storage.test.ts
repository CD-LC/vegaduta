import { describe, it, expect, vi, beforeAll } from "vitest";

// FE-001 (security review 2026-09-30): no Keycloak token may be written to web
// storage. Tokens live only inside the keycloak-js instance; a reload restores
// the session through Keycloak's SSO cookie (check-sso), never from storage.
//
// Separate file from auth.test.ts on purpose: auth.ts keeps a module-level
// singleton + memoized init(), and this test needs storage pre-seeded BEFORE
// the first init() of the module.

const ACCESS = "access-token-SECRET-A";
const REFRESH = "refresh-token-SECRET-R";
const ID = "id-token-SECRET-I";

const kc = vi.hoisted(() => {
  const instances: Array<Record<string, unknown>> = [];
  const initMock = vi.fn<(options?: Record<string, unknown>) => Promise<boolean>>(() => Promise.resolve(true));
  class KeycloakMock {
    token = "access-token-SECRET-A";
    refreshToken = "refresh-token-SECRET-R";
    idToken = "id-token-SECRET-I";
    refreshTokenParsed = { exp: 9999999999 };
    authenticated = true;
    realmAccess = { roles: [] as string[] };
    resourceAccess = {};
    tokenParsed: Record<string, unknown> = { sub: "sub-1" };
    onAuthSuccess?: () => void;
    onAuthRefreshSuccess?: () => void;
    constructor(_config: unknown) {
      instances.push(this as unknown as Record<string, unknown>);
    }
    init = initMock;
    login = vi.fn(() => Promise.resolve());
    logout = vi.fn(() => Promise.resolve());
    updateToken = vi.fn(() => {
      // keycloak-js fires onAuthRefreshSuccess after a refresh.
      this.onAuthRefreshSuccess?.();
      return Promise.resolve(true);
    });
  }
  return { initMock, KeycloakMock, instances };
});

vi.mock("keycloak-js", () => ({ default: kc.KeycloakMock }));

import { auth, LEGACY_TOKEN_CACHE_KEY } from "./auth";

function everyStoredValue(): string {
  const out: string[] = [];
  for (const store of [window.sessionStorage, window.localStorage]) {
    for (let i = 0; i < store.length; i++) {
      const k = store.key(i);
      if (k) out.push(`${k}=${store.getItem(k) ?? ""}`);
    }
  }
  return out.join("\n");
}

describe("auth token storage (FE-001)", () => {
  beforeAll(() => {
    // A browser upgrading from the old build still holds the legacy cache and
    // the "was signed in" hint that used to trigger the fast-restore path.
    window.localStorage.setItem("vd_authed", "1");
    window.sessionStorage.setItem(
      LEGACY_TOKEN_CACHE_KEY,
      JSON.stringify({ token: "old-access", refreshToken: "old-refresh" })
    );
  });

  it("never initialises keycloak-js from stored tokens; always uses check-sso", async () => {
    await auth.init();
    expect(kc.initMock).toHaveBeenCalledTimes(1);
    const opts = kc.initMock.mock.calls[0][0] as Record<string, unknown>;
    expect(opts).toMatchObject({ onLoad: "check-sso", pkceMethod: "S256" });
    expect(String(opts.silentCheckSsoRedirectUri)).toMatch(/\/silent-check-sso\.html$/);
    expect(opts).not.toHaveProperty("token");
    expect(opts).not.toHaveProperty("refreshToken");
    expect(opts).not.toHaveProperty("idToken");
  });

  it("scrubs the legacy sessionStorage token cache", () => {
    expect(window.sessionStorage.getItem(LEGACY_TOKEN_CACHE_KEY)).toBeNull();
  });

  it("writes no access, refresh or id token to web storage after auth and refresh", async () => {
    const instance = kc.instances[0] as { onAuthSuccess?: () => void };
    instance.onAuthSuccess?.();
    await auth.getToken(); // triggers updateToken -> onAuthRefreshSuccess
    const stored = everyStoredValue();
    expect(stored).not.toContain(REFRESH);
    expect(stored).not.toContain(ACCESS);
    expect(stored).not.toContain(ID);
    expect(stored).not.toContain("old-refresh");
  });
});
