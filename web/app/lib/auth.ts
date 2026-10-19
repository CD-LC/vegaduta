// Keycloak OIDC integration for the web console (Phase 0 Stage 3 client
// side). Uses the "agentic-ai-web" public client already defined in
// deploy/keycloak/agentic-ai-realm.json (standardFlowEnabled +
// authorization-code-with-PKCE, no client secret - a browser can't keep one).
//
// This is a plain module-level singleton, not a React dependency, so the
// current access token is reachable from app/lib/api.js (a non-component
// module) without threading it through every function call. AuthProvider
// (./AuthProvider.js) owns the React-facing lifecycle (init, context,
// re-render on auth state change); this file owns the Keycloak client
// instance itself and read/write access to its token.
//
// KEYCLOAK_URL/REALM/CLIENT_ID are NEXT_PUBLIC_* so they're baked in at
// build time and available in the browser bundle - same pattern as
// NEXT_PUBLIC_API_BASE_URL in api.js. Defaults match the realm-export file
// and docker-compose.yml exactly, so a fresh checkout works with zero env
// setup for local dev.
//
// Layout: authClient.ts (keycloak-js loading + instance), authReauth.ts
// (keepalive, reauth prompt, token refresh) and authClaims.ts (token readers)
// hold the pieces; this file owns init/login/logout and the `auth` facade.
import { clearAuthedHint, hasAuthedHint } from "./authHint";
import { isLocalAuth, LOCAL_AUTH_BASE } from "./authMode";
import {
  KEYCLOAK_CLIENT_ID,
  KEYCLOAK_REALM,
  KEYCLOAK_URL,
  getKeycloak,
  isKeycloakLoaded,
  loadKeycloak,
  markAuthedHint,
  peekKeycloakInstance,
  resetKeycloakInstance,
  getSessionExpiryMs,
} from "./authClient";
import { clearReauthPrompt, getToken, markEverAuthenticated, refreshToken, startSessionKeepalive } from "./authReauth";
import {
  accountUrl,
  getAccessTokenExpiryMs,
  getAuthTimeMs,
  getFirstName,
  getRoles,
  getSessionId,
  getTenantId,
  getUserId,
  getUsername,
  isAuthenticated,
  signInEndpoint,
} from "./authClaims";

// realm role name that maps to "lands in the admin control panel" - kept as
// a single named export rather than scattered string literals, and named
// after the actual realm role (see agentic-ai-realm.json) rather than any
// informal name ("admin_agentic") used in conversation about this feature.
export const ADMIN_ROLE = "tenant-admin";

// realm role name that maps to "lands in the Ecosystem Maintenance console" -
// a cross-tenant, platform-wide super-admin role (see PlatformController.java),
// deliberately separate from and NOT implied by ADMIN_ROLE above. A
// tenant-admin only governs their own tenant; this role is for whoever
// operates the platform itself across every tenant. Kept as its own named
// export for the same reason as ADMIN_ROLE - named after the actual realm
// role, not an informal nickname.
export const PLATFORM_ADMIN_ROLE = "platform-admin";

// PUBLIC_ROUTES and matchesPublicRoute() live in ./publicRoutes.ts since
// 2026-09-28 (moved so proxy.js can share the same table without bundling
// keycloak-js into the middleware layer — that module documents the
// per-route history). Re-exported here so the long list of existing
// consumers (layout.tsx, AuthProvider.tsx, tests) keeps working untouched.
import { PUBLIC_ROUTES, matchesPublicRoute } from "./publicRoutes";
export { PUBLIC_ROUTES, matchesPublicRoute };

// onLoad: "check-sso" (changed from "login-required" for the branded-landing
// redesign) silently checks for an existing Keycloak session via a hidden
// iframe (silentCheckSsoRedirectUri, unchanged) but - unlike
// "login-required" - never forces a redirect when there isn't one. init()
// simply resolves with authenticated=false, and AuthProvider renders its own
// branded LoginLanding screen instead of bouncing straight to Keycloak's
// generic hosted login page. The actual redirect to Keycloak now only
// happens when the person clicks that screen's "Log in" button (see
// login() below) - a deliberate, visible action instead of a surprise
// full-page navigation on every cold visit.
//
// initPromise memoizes the actual kc.init() call - keycloak-js throws (or
// behaves unpredictably) if init() is called a second time, and Next.js's
// reactStrictMode (next.config.js) intentionally double-invokes effects in
// dev, which was calling AuthProvider's init effect twice per mount. Under
// the old "login-required" mode, two independent in-flight init() calls
// could each decide "no session yet" and each fire their own redirect to
// Keycloak, which redirects back, remounts, and loops forever (a real bug
// hit while testing this feature). "check-sso" no longer redirects on its
// own, so that specific failure mode is gone, but memoizing here is still
// correct - and still guards keycloak-js's documented "don't call init()
// twice" rule regardless of which onLoad mode is active.
// Extracted from init()'s own hasAuthCallback check (below) so
// AuthProvider can ask the SAME question synchronously, before init()
// even runs - see its usage for why: "/" landing right after a fresh
// Keycloak login (this URL) had no vd_authed hint yet (that's only set
// once init() resolves "ready"), so the optimistic-route branch rendered
// the marketing landing page for the ~1-2s the code exchange takes,
// then swapped to the dashboard - a visible landing-then-redirect flash
// on every first login. Treating "this load IS a Keycloak callback" as
// its own signal, independent of the historical vd_authed hint, closes
// that gap without touching the hint's own refresh-flicker logic.
function isAuthCallbackUrl(): boolean {
  if (typeof window === "undefined") return false;
  return /[?#&](code|error)=/.test(window.location.href);
}

// prompt=none outcomes ("interaction_required" is NOT "logged out") live in
// ./silentAuthError.ts; the pure helpers are re-exported here so importers
// (AuthProvider, tests) keep their existing paths.
import { captureSilentAuthError, getSilentAuthError, watchSilentCheckSsoErrors } from "./silentAuthError";
export { isInteractionRequiredError, parseSilentAuthError, type SilentAuthError } from "./silentAuthError";

// ---------------------------------------------------------------------------
// Tokens stay in memory (security review FE-001, 2026-09-30)
// ---------------------------------------------------------------------------
// From 2026-09-24 to 2026-09-30 this module cached {token, refreshToken,
// idToken} in sessionStorage["vd_kc_session"] so a hard reload could skip the
// check-sso round-trip. That made the refresh token readable by any script in
// the page, and a refresh token can be carried off and used from another
// device until the SSO session ends. It is gone: tokens live only inside the
// keycloak-js instance (its default), and a reload restores the session
// through Keycloak's own SSO cookie - check-sso via the hidden
// silent-check-sso.html iframe below, then AuthProvider's top-level
// prompt=none fallback when the browser blocks that iframe's cookies.
//
// When is the iframe enough? The Keycloak session cookie is only sent to the
// iframe if the browser treats it as first-party-ish: app.<domain> and
// auth.<domain> share a registrable domain (the supported deployment layout),
// so the cookie is same-site and the silent check completes in well under a
// second. Split-domain deployments (auth on another site) depend on
// third-party cookies, which modern browsers block; there the iframe times
// out (messageReceiveTimeout) and the prompt=none redirect restores the
// session instead - slower, but it never exposes a token to storage.
//
// LEGACY_TOKEN_CACHE_KEY is only removed, never written: it scrubs the copy a
// browser may still hold from the old build.
export const LEGACY_TOKEN_CACHE_KEY = "vd_kc_session";
function clearLegacyTokenCache(): void {
  try {
    window.sessionStorage.removeItem(LEGACY_TOKEN_CACHE_KEY);
  } catch {
    // Storage blocked: nothing could have been written there either.
  }
}

let initPromise: Promise<any> | null = null;
function init(): Promise<any> {
  if (typeof window === "undefined") return Promise.resolve(null);
  if (!isKeycloakLoaded()) {
    // First call: fetch the keycloak-js chunk, then run the real init. The
    // loading promise is memoized in initPromise too, so concurrent callers
    // (React StrictMode's double effect) still share ONE kc.init().
    if (initPromise === null) {
      initPromise = loadKeycloak().then(() => {
        initPromise = null;
        return init();
      });
    }
    return initPromise;
  }
  const kc = getKeycloak();
  if (!kc) return Promise.resolve(null);
  // `=== null`, not `!initPromise`: the variable is only ever null or a
  // Promise, so the two are identical - but the explicit form is what
  // @typescript-eslint/no-misused-promises accepts in a boolean position.
  if (initPromise === null) {
    // Is THIS page load the redirect back from a Keycloak login (a code/error
    // in the URL)? If so we must NOT arm the silent-SSO probe. keycloak-js runs
    // that 3rd-party-cookie check iframe as part of init(), and when the browser
    // blocks it (every modern browser now does) the probe TIMES OUT and rejects
    // init() *before* keycloak-js ever exchanges the authorization code - so the
    // login is silently dropped, the person is bounced back to the landing, and
    // the `?code=` just sits unconsumed in the URL (one orphaned kc-callback-*
    // entry piling up in localStorage per failed attempt - the exact symptom
    // observed). On a real callback we therefore init with NO onLoad /
    // silentCheckSso at all: keycloak-js still reads and exchanges the code+state
    // from the URL regardless of onLoad, so the token exchange runs cleanly with
    // no probe left to hang on.
    const hasAuthCallback = isAuthCallbackUrl();
    clearLegacyTokenCache();
    // Last chance to read WHY a silent attempt failed: kc.init() below
    // replaceState()s error/error_description out of the address bar before it
    // ever resolves, and reports a prompt=none failure as a plain "not
    // authenticated". See SilentAuthError's block comment.
    captureSilentAuthError();
    watchSilentCheckSsoErrors();
    const initOptions: Record<string, unknown> = {
      pkceMethod: "S256",
      checkLoginIframe: false,
    };
    if (!hasAuthCallback) {
      // Fresh visit (no callback): use check-sso to silently detect an existing
      // session. The probe can still time out here, but on a non-callback load
      // that only means "treat as logged out" (see AuthProvider's catch) - no
      // login is at stake, so a discarded probe is harmless.
      initOptions.onLoad = "check-sso";
      initOptions.silentCheckSsoRedirectUri = `${window.location.origin}/silent-check-sso.html`;
      initOptions.silentCheckSsoFallback = false;
      // Cap how long keycloak-js waits for its hidden-iframe messages. The
      // 3rd-party-cookie probe (see above) is blocked by modern browsers and
      // otherwise hangs for the full 10s default before rejecting init() - which
      // is exactly the long "Signing you in…" a returning visitor sees on every
      // refresh. The real silent-check-sso round-trip (which detects the existing
      // session) completes in well under a second when the session cookie is
      // present, so a shorter cap makes refresh resolve fast while still leaving
      // the genuine check ample time.
      initOptions.messageReceiveTimeout = 3500;
    }
    initPromise = kc
      .init(initOptions)
      .then((authenticated: any) => {
        // Arm the keepalive exactly once, and only for a real session - see
        // startSessionKeepalive's comment for why the demand-driven refreshes
        // alone were not enough.
        if (authenticated) {
          markEverAuthenticated();
          startSessionKeepalive();
        }
        return authenticated ? kc : null;
      })
      .catch((err: any) => {
        // Belt-and-suspenders: if a token was somehow already obtained despite
        // init() rejecting, honour it rather than discarding a real session.
        if (kc.authenticated && kc.token) {
          // Latch the same flag as the success path - this branch returns a
          // genuinely authenticated instance, so a later expiry here must still
          // be allowed to raise the reauth prompt (see promptReauth).
          markEverAuthenticated();
          return kc;
        }
        throw err;
      });
  }
  return initPromise;
}

// Re-run init() after it REJECTED. init() is memoized (initPromise above), so
// a caller retrying by simply invoking auth.init() again would just re-await
// the same rejected promise - the exact reason a transient keycloak-js
// rejection (a bare `undefined` from the silent-SSO iframe, a Cloudflare
// blip, one dropped token exchange) used to land a working session on the
// dead "Login failed" screen with no way back. Discarding the instance
// before re-running matters just as much: keycloak-js refuses to init() the
// same client twice, which is precisely the reset the cached-token fallback
// above already does (keycloakInstance = null; initPromise = null).
//
// The authenticated-instance guard is belt-and-suspenders: init()'s own
// catch already converts "rejected but a token landed anyway" into a
// resolved kc, so a rejection should never leave a live session behind -
// but if one ever does, discarding it here would be a forced logout.
function retryInit(): Promise<any> {
  const current = peekKeycloakInstance();
  if (current?.authenticated && current?.token) {
    return Promise.resolve(current);
  }
  resetKeycloakInstance();
  initPromise = null;
  return init();
}

// `opts` is optional and forwarded straight to keycloak-js's own login() -
// every existing caller (GenesisNav, LoginLanding, MarketingNav,
// Act7FutureResolved) calls this with no arguments and is unaffected. Added
// so the signup page's cosmetic "Sign In" panel can pass
// `{ loginHint: email }`, pre-filling the email the person already typed
// into Keycloak's own hosted login form - without this app ever seeing or
// handling their password itself.
//
// Async, and awaits init() first - not just a defensive nicety. keycloak-js's
// own login() reads internal state (the realm's discovered endpoints, PKCE
// config, etc.) that only gets populated by init(). Every OTHER caller of
// login() lives on a route AuthProvider treats as private, so by the time a
// person can click anything there, AuthProvider's own effect has already
// awaited auth.init(). SignInPanel.js is the one caller that does NOT have
// that guarantee: AuthProvider explicitly skips calling init() at all on
// /signup (it's a public route with no session to check - see
// AuthProvider.js's isPublicRoute branch), so getKeycloak() there was
// constructing a brand-new, never-initialized Keycloak instance and handing
// it straight to kc.login(opts) - which crashed inside keycloak-js itself
// ("Cannot read properties of undefined (reading 'login')") because init()
// had never run. init() is memoized (initPromise above), so calling it here
// is a safe no-op on every route where AuthProvider already called it, and
// is the missing piece specifically on /signup.
//
// The await is wrapped in try/catch - deliberately swallowing a rejection
// here, not just tidiness. init()'s returned promise covers keycloak-js's
// full check-sso lifecycle (an iframe round-trip against the Keycloak
// server to see if a session already exists), which can fail for reasons
// that have nothing to do with whether login() itself will work - the
// iframe timing out, the Keycloak server being briefly unreachable, etc.
// The part login() actually depends on (realm/endpoint URLs) is set up
// synchronously the moment kc.init() is called, before that promise even
// settles - so a failed check-sso shouldn't block or crash this cosmetic
// "sign in" button. Without this try/catch, a rejected init() surfaced as
// an unhandled promise rejection (Next's dev overlay showed it as a bare
// "Runtime Error: undefined" since keycloak-js rejects with a plain object,
// not an Error, so there's no .message to display).
async function login(opts?: Record<string, unknown>): Promise<void> {
  if (isLocalAuth()) {
    // No redirect to an identity provider: the local sign-in page is part of this app.
    getKeycloak()?.login(opts);
    return;
  }
  try {
    await loadKeycloak();
  } catch {
    return; // chunk failed to load (offline): nothing to redirect with
  }
  const kc = getKeycloak();
  if (!kc) return;
  // Fire init() so keycloak-js populates the realm/endpoint URLs kc.login()
  // needs - but do NOT block the redirect on check-sso's full lifecycle. That
  // lifecycle includes a hidden third-party-cookie probe iframe against
  // Keycloak which, behind cookie partitioning (and especially over the
  // staging tunnel, where Keycloak is a cross-site origin), routinely
  // hangs for ~10s until it times out. Awaiting it made clicking "Sign in"
  // look broken - nothing visibly happened for ten seconds before the page
  // finally navigated. The endpoints kc.login() actually reads are set up
  // SYNCHRONOUSLY the moment kc.init() is called (see the block comment
  // above), so racing init() against a short cap lets us redirect promptly
  // while still giving a fast check-sso (local dev) time to resolve first.
  try {
    await Promise.race([init(), new Promise((resolve) => setTimeout(resolve, 800))]);
  } catch {
    // Non-fatal - see comment above.
  }
  // Always return to a CLEAN url. If login() runs while the address bar still
  // carries a previous attempt's callback fragment (#...&code=...&state=...) -
  // e.g. the person clicked "Sign in" again because the first attempt had not
  // visibly completed yet - Keycloak APPENDS a second code/state to the existing
  // one. The resulting `#...code=A...&code=B` is unparseable: keycloak-js cannot
  // match a stored PKCE verifier to it, the token exchange fails, and the person
  // is silently bounced back to the landing ("login does nothing / never reaches
  // the dashboard" - the exact double-code URL this fixes). Pinning redirectUri
  // to origin+pathname (no hash, no query) guarantees a single clean code every
  // time. An explicit redirectUri in `opts` (e.g. from the signup flow) still
  // wins via the spread below.
  const cleanRedirectUri = window.location.origin + window.location.pathname;
  kc.login({ redirectUri: cleanRedirectUri, ...opts });
}

// Every key this app keeps per signed-in person outside the token cache.
// Cleared on logout so the next person on this browser starts clean.
const PER_SESSION_LOCAL_KEYS = ["vd_last_activity"];
const PER_SESSION_SESSION_KEYS = ["vd_sso_redirect_attempted"];

async function logout(): Promise<void> {
  if (typeof window === "undefined") return;
  const kc = getKeycloak();
  // Read the id token before tearing anything down: Keycloak needs it as
  // id_token_hint to end the session without its own "Do you want to log
  // out?" confirmation page - which, when skipped or abandoned, left the SSO
  // session alive and signed the next person on this browser straight back
  // in as the previous one. Tokens live only in memory (no persisted copy).
  const idToken: string | undefined = kc?.idToken;

  // Clear the "was signed in" hint (see authHint.ts) so the next visit shows
  // the marketing landing, not the signing-in splash.
  clearAuthedHint();
  clearLegacyTokenCache();
  try {
    PER_SESSION_LOCAL_KEYS.forEach((k) => window.localStorage.removeItem(k));
    PER_SESSION_SESSION_KEYS.forEach((k) => window.sessionStorage.removeItem(k));
    // keycloak-js's own PKCE/state entries from unfinished login attempts.
    Object.keys(window.localStorage)
      .filter((k) => k.startsWith("kc-callback-"))
      .forEach((k) => window.localStorage.removeItem(k));
  } catch {
    // storage blocked - nothing was persisted there either
  }
  // Drop the shared GET cache (memory + IndexedDB) and WAIT for it, capped so
  // a wedged IndexedDB can never hold the person on the page: a different
  // identity signing in next must never be served this session's tenant
  // reads. Dynamic import - api/core.ts already imports this module for
  // getToken(), keep the dependency one-directional.
  try {
    await Promise.race([
      import("./api/core").then((m) => m.clearApiGetCache()),
      new Promise((resolve) => setTimeout(resolve, 1000)),
    ]);
  } catch {
    // best effort
  }

  if (isLocalAuth()) {
    // Ends the login on core (revokes the refresh token family), clears the HttpOnly cookie and
    // returns to the sign-in page. Best effort: a failed call must never trap the person here.
    try {
      await fetch(`${LOCAL_AUTH_BASE}/logout`, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
    } catch {
      // offline: the cookie expires on its own; still leave the page
    }
    resetKeycloakInstance();
    initPromise = null;
    window.location.replace("/login");
    return;
  }

  // End the Keycloak SSO session. Built by hand rather than kc.logout() so it
  // works even when keycloak-js was never initialised in this tab (its
  // endpoints are only populated by init()).
  const params = new URLSearchParams({
    client_id: KEYCLOAK_CLIENT_ID,
    post_logout_redirect_uri: window.location.origin,
  });
  if (idToken) params.set("id_token_hint", idToken);
  resetKeycloakInstance();
  initPromise = null;
  window.location.replace(
    `${KEYCLOAK_URL}/realms/${encodeURIComponent(KEYCLOAK_REALM)}/protocol/openid-connect/logout?${params.toString()}`
  );
}

export const auth = {
  init,
  retryInit,
  login,
  logout,
  hasAuthedHint,
  markAuthedHint,
  clearAuthedHint,
  getRoles,
  getUsername,
  getFirstName,
  getUserId,
  getTenantId,
  getToken,
  refreshToken,
  accountUrl,
  getSessionId,
  isAuthenticated,
  signInEndpoint,
  clearReauthPrompt,
  getAccessTokenExpiryMs,
  getSessionExpiryMs,
  getAuthTimeMs,
  isAuthCallbackUrl,
  getSilentAuthError,
};
