// Session keepalive, the "stay signed in?" reauth prompt, and the demand/forced
// token refreshes (split out of auth.ts, which exposes them on `auth`).
import { reportClientError } from "./clientLogger";
import { getKeycloak, getSessionExpiryMs } from "./authClient";
import { matchesPublicRoute } from "./publicRoutes";

function isPublicPathname(pathname: string | null | undefined): boolean {
  return matchesPublicRoute(pathname);
}

// "Has this page load ever held a real Keycloak session?" - latched true the
// first time init() observes one, and deliberately NEVER reset. It is the guard
// promptReauth() reads to tell "your session expired" apart from "you were
// never signed in"; see that function for the full reasoning. Kept as a
// separate latch rather than reading kc.authenticated because keycloak-js sets
// that back to false via clearToken() on the exact terminal failure the prompt
// is for.
let everAuthenticated = false;

/** Latch everAuthenticated - called by init() whenever it observes a real session. */
export function markEverAuthenticated(): void {
  everAuthenticated = true;
}

// ---------------------------------------------------------------------------
// Silent session keepalive
// ---------------------------------------------------------------------------
// onTokenExpired above covers the "tab is alive and the timer fired" case. It
// does NOT cover the two cases that actually produced the bug report:
//   * the machine slept - no timer of any kind runs, and on wake keycloak-js's
//     armed timeout fires late (or is skipped) with a token that is already
//     long dead;
//   * the tab was backgrounded/frozen - Chrome throttles background timers to
//     >=1/min and freezes them outright in a discarded tab.
// So: a low-frequency interval (a third of the 900s accessTokenLifespan) plus
// event-driven forced refreshes the instant the tab becomes visible again or
// the network comes back. The visibility handler is the one that covers laptop
// sleep - it fires on wake, before the person has clicked anything.
//
// Cost control (this runs in EVERY open tab): the interval only refreshes while
// the tab is visible. A hidden tab refreshes only when the SESSION itself (the
// refresh token, not the access token) is within 15 minutes of dying - i.e.
// only when skipping it would actually lose the session.
const KEEPALIVE_INTERVAL_MS = 300_000;
const KEEPALIVE_MIN_VALIDITY_SECONDS = 60;
const HIDDEN_TAB_RESCUE_WINDOW_MS = 15 * 60 * 1000;
let keepaliveStarted = false;

// Direct report (2026-08-12): "session timeout... continuously use web for
// 15-20 mins" with no prompt at all - traced to a real gap in the "stay
// silent on a transient network failure" design just below. That design is
// correct for a ONE-OFF blip (a dropped packet, a momentary Keycloak
// restart): retrying quietly a few minutes later on the next tick is the
// right call, and nagging on every single hiccup would be worse than the
// bug it prevents. But it had no ESCALATION path for a failure that keeps
// recurring - a genuinely down auth server (confirmed possible: this same
// session found the .xyz staging Cloudflare tunnel itself down earlier
// today, which would make every keepalive tick fail identically) could fail
// silently, tick after tick, indefinitely, with nothing ever telling the
// person their session might not actually be safe - not a countdown, not a
// reconnect prompt, nothing - until they happened to trigger a real apiFetch
// (whose OWN demand-refresh in getToken() below has always prompted
// unconditionally on any failure) or their already-open stream broke with a
// raw, unexplained error. Fixed by counting CONSECUTIVE tick failures and
// escalating once persistence itself becomes the signal, even without
// keycloak-js's own sessionCleared marker - 3 ticks at the 300s interval is
// 15 minutes of sustained, unbroken failure, deliberately matching the
// reported window: long enough that a single blip or two never trips it,
// short enough that a real outage no longer hides behind "wait for the next
// tick" forever.
const KEEPALIVE_ESCALATE_AFTER_CONSECUTIVE_FAILURES = 3;
let consecutiveKeepaliveFailures = 0;

async function silentRefresh(minValidity = KEEPALIVE_MIN_VALIDITY_SECONDS): Promise<boolean> {
  const kc = getKeycloak();
  if (!kc || !kc.authenticated) return false;
  try {
    await kc.updateToken(minValidity);
    consecutiveKeepaliveFailures = 0;
    return true;
  } catch {
    // Only escalate to a visible prompt when the SESSION is genuinely gone,
    // not on a one-off network failure - the latter is retried by the next
    // tick a few minutes later.
    //
    // The signal is whether keycloak-js cleared its own token state, NOT the
    // refresh token's exp. keycloak-js calls clearToken() from the refresh
    // path on exactly one condition - the token endpoint answering HTTP 400
    // (invalid_grant: session revoked, refresh token used/expired) - and
    // clearToken() deletes `refreshTokenParsed`. A network-level failure
    // (status 0/5xx) leaves it intact, which is precisely the transient case
    // that must stay silent.
    //
    // Reading `exp` here instead - as this did originally - is unreachable by
    // construction: on the terminal 400 the parsed token is already gone, so
    // the expiry reads null and the prompt never fires for the one case it
    // exists to catch. That also left IdleLogout's expiry branch dead, since
    // it reads the same value.
    const sessionCleared = !getKeycloak()?.refreshTokenParsed;
    consecutiveKeepaliveFailures += 1;
    if (sessionCleared) {
      consecutiveKeepaliveFailures = 0;
      promptReauth();
    } else if (consecutiveKeepaliveFailures >= KEEPALIVE_ESCALATE_AFTER_CONSECUTIVE_FAILURES) {
      // Persistence, not a single failure, is the signal here - see the
      // block comment above. Reset immediately so a later genuine
      // sessionCleared failure (or a fresh run of transient blips well after
      // this prompt was already resolved) isn't miscounted against this
      // escalation's own threshold.
      consecutiveKeepaliveFailures = 0;
      reportClientError("auth_failure", "Keycloak silent refresh failed repeatedly - escalating to reauth prompt", {
        reason: "keepalive_repeated_failure",
      });
      promptReauth();
    }
    return false;
  }
}

export function startSessionKeepalive(): void {
  if (keepaliveStarted || typeof window === "undefined") return;
  keepaliveStarted = true;
  setInterval(() => {
    if (document.visibilityState === "visible") {
      void silentRefresh();
      return;
    }
    const expiry = getSessionExpiryMs();
    if (expiry !== null && expiry - Date.now() < HIDDEN_TAB_RESCUE_WINDOW_MS) {
      void silentRefresh();
    }
  }, KEEPALIVE_INTERVAL_MS);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") void silentRefresh();
  });
  window.addEventListener("online", () => {
    void silentRefresh();
  });
}

// Direct feedback (2026-08-04): getToken() used to call kc.login() - an
// IMMEDIATE, unconditional full-page redirect to Keycloak - the instant a
// single background updateToken(30) call failed, with no warning or
// confirmation. This fires from every apiFetch call (see api.ts), so it can
// - and did - trip while someone was actively using the app, not just idle:
// a blocked third-party cookie, one flaky network blip, or Keycloak being
// briefly unreachable was enough to silently yank them out mid-action. This
// is a DIFFERENT failure mode than IdleLogout.tsx's timer (that one only
// fires after real inactivity, and already warns first) - Keycloak's own
// session is typically still alive for hours (ssoSessionIdleTimeout, see
// deploy/keycloak/agentic-ai-realm.json) when this happens, so a hard
// redirect was throwing away a perfectly good session over what's usually a
// transient refresh hiccup.
//
// Fix: broadcast a DOM event instead of redirecting immediately.
// IdleLogout.tsx listens for it and shows the same kind of "stay signed
// in?" prompt as its idle-timeout warning, offering a manual retry
// (auth.refreshToken()) before ever falling back to a real, user-initiated
// auth.login(). reauthPromptActive de-dupes: apiFetch calls getToken() on
// every single request, so a genuine outage would otherwise fire this event
// dozens of times a second - only the first failure while a prompt isn't
// already showing dispatches one; clearReauthPrompt() (called by
// IdleLogout.tsx once the prompt resolves, one way or another) re-arms it.
let reauthPromptActive = false;
export function clearReauthPrompt(): void {
  reauthPromptActive = false;
}

// Single place that raises the visible "we couldn't verify your session"
// prompt, so the de-dupe above holds no matter which path noticed first
// (getToken's demand refresh, or the keepalive's silent one). Never fires on a
// public route - see getToken's javadoc for why that guard exists.
//
// ROUTE-LIST GUARDS ALONE WERE NOT ENOUGH (2026-08-16). E2E on production,
// logged out, caught this prompt being shown on /help and /features to a
// visitor who had never signed in. Those two are NOT in PUBLIC_ROUTES and
// deliberately can't be (see AuthProvider's ANONYMOUS_RENDER_ROUTES: they need
// the real Keycloak lifecycle so a signed-in visitor still gets the
// authenticated view) - they render their own children under an anonymous
// AuthContext instead. So isPublicPathname() answered "false", the demand
// refresh in getToken() below rejected exactly as it always does when there is
// no session to refresh, and a stranger got told their session couldn't be
// verified. Any future anonymous-render route would have reproduced it.
//
// The honest question is not "which route is this" but "was there EVER a
// session here to lose" - if there wasn't, a failed refresh is the expected
// outcome, not an expiry worth interrupting anyone over. `everAuthenticated`
// (latched in init() above) answers exactly that, and unlike kc.authenticated it
// survives keycloak-js's own clearToken() on a genuine invalid_grant - which
// flips kc.authenticated to false and would therefore have suppressed the one
// prompt this function exists to raise.
function promptReauth(): void {
  if (typeof window === "undefined") return;
  if (!everAuthenticated) return;
  if (isPublicPathname(window.location.pathname)) return;
  if (reauthPromptActive) return;
  reauthPromptActive = true;
  window.dispatchEvent(new CustomEvent("vd:session-needs-reauth"));
}

// Used by api.js on every request. updateToken(30) refreshes if the token
// expires within 30s, so a long-idle tab doesn't send a stale/expired
// bearer token and get a confusing 401 mid-action.
//
// `opts.silent` - for a caller that is itself a background probe (e.g.
// api.js's getSystemHealthSilent, polled by SystemHealthProvider) rather
// than a person-initiated action: never prompt on failure, just resolve
// to "no token," on every route, authenticated or not.
//
// Prompting on refresh failure is ALSO skipped outright on a public route
// (PUBLIC_ROUTES above) even for a non-silent caller. getKeycloak() lazily
// constructs a Keycloak instance the moment ANY caller asks for a token -
// including SystemHealthProvider's root-mounted poll, which runs on every
// route (see layout.tsx: it's mounted inside AuthProvider but wraps
// {children}, and AuthProvider's public-route branch just passes children
// through with no session gating at all). On /signup or /login that
// instance was never run through auth.init() (AuthProvider deliberately
// skips it there - nothing to check), so kc.updateToken(30) rejects
// immediately - not because a real session expired, but because there was
// never one to begin with. Treating that as "session expired" bounced a
// brand-new visitor clean off /signup before they could use it - the actual
// bug this guards against. A genuine session expiry on an authenticated app
// route (dashboard, agents, etc. - none of which are public) still prompts
// exactly as before, just gracefully now instead of an instant redirect.
export async function getToken(opts?: { silent?: boolean }): Promise<string | null> {
  const kc = getKeycloak();
  if (!kc) return null;
  try {
    await kc.updateToken(30);
  } catch {
    const onPublicRoute = typeof window !== "undefined" && isPublicPathname(window.location.pathname);
    if (opts?.silent || onPublicRoute) {
      return null;
    }
    // Someone who has never authenticated has no session to refresh, so this
    // is not a failure worth reporting: promptReauth() already no-ops for
    // them (same `everAuthenticated` latch), which would make the message
    // below literally untrue - it would claim a prompt was shown when none
    // was. Checking the latch HERE too keeps the telemetry honest; leaving it
    // only inside promptReauth() suppressed the UI but still logged an
    // auth_failure for every anonymous visitor, which is exactly the
    // dashboard noise this change set out to remove.
    if (!everAuthenticated) {
      return null;
    }
    reportClientError("auth_failure", "Keycloak token refresh failed - showing reauth prompt", {
      reason: "demand_refresh_failed",
    });
    promptReauth();
    return null;
  }
  return kc.token;
}

// Force a token refresh regardless of expiry (updateToken(-1)) - used after
// onboarding provisions a tenant, so the next token carries the freshly-set
// tenant_id claim without the user having to log out and back in; also the
// "Stay signed in" button's action and api.ts's one-shot 401 retry.
//
// Resolves TRUE only when the session is genuinely still alive. Callers must
// honour that boolean (IdleLogout used to ignore it, which silently reset a
// countdown against a session that no longer existed).
//
// The wait is bounded: keycloak-js's token XHR has NO timeout and its refresh
// queue makes every later updateToken() wait behind a stalled one, which wedged
// "Stay signed in" on its spinner and latched IdleLogout's refresh-in-flight
// flag. Racing a cap turns "hang" into "fail" (callers surface that); a late
// refresh still lands via setToken.
const REFRESH_TIMEOUT_MS = 15_000;
export async function refreshToken(): Promise<boolean> {
  const kc = getKeycloak();
  if (!kc) return false;
  try {
    await Promise.race([
      kc.updateToken(-1),
      new Promise((_resolve, reject) =>
        setTimeout(() => reject(new Error("Keycloak token refresh timed out")), REFRESH_TIMEOUT_MS)
      ),
    ]);
    return true;
  } catch (err: any) {
    // Previously swallowed with no trace at all - a genuine session death and
    // a transient network blip looked identical in telemetry.
    reportClientError("auth_failure", "Forced Keycloak token refresh failed", {
      reason: "forced_refresh_failed",
      detail: String(err?.error || err?.message || err || "").slice(0, 200),
    });
    return false;
  }
}
