import { auth, isInteractionRequiredError, type SilentAuthError } from "../../lib/auth";

// Route classification and Keycloak init policy for AuthProvider: which routes
// render optimistically or anonymously, the init retry/watchdog budget, how a
// keycloak-js rejection becomes a readable message, and the two silent-auth
// recovery checks. Pure functions and constants only; the React lifecycle that
// uses them lives in useAuthSession.ts.

// "/" is different from every other authenticated route: it's also the
// cold-landing entry point for a brand-new or logged-out visitor (that's
// the whole reason GenesisExperience exists as the "unauthenticated"
// render below). Every OTHER route in the console has no logged-out
// experience at all, so blocking on auth.init() before rendering anything
// is correct there. On "/" it isn't: it made every single cold visit sit on
// a blank "Signing you in…" spinner for however long Keycloak's silent-SSO
// check takes - which got noticeably worse once the 3rd-party-cookie-probe
// timeout (see lib/auth.js / the catch block below) started being caught
// instead of failing fast, since now the UI waits out that probe's full
// timeout before it even gets to render anything. The overwhelming common
// case - a first-time or logged-out visitor - has nothing to gain from
// that wait, so "/" renders GenesisExperience immediately (optimistically
// assuming logged-out). auth.init() no longer runs in the background for
// everyone (2026-10-05): the init effect below skips Keycloak entirely when
// neither the vd_authed hint nor a ?code=/?error= callback says a session
// might exist - an anonymous landing pays zero sign-in round-trips. When
// init() DOES run (hint present, or a fresh login callback) and resolves
// authenticated, the effect flips status to "ready" and the real Dashboard
// swaps in. Deep routes like /agents or /workflows keep the full blocking
// behavior since they have no logged-out render to fall back to.
export const OPTIMISTIC_ROUTES = ["/"];

// /help and /features carry real, distinct SEO metadata (help/layout.tsx,
// features/layout.tsx) and sitemap.ts entries, but their page content lives
// behind AppShell, which unconditionally calls useAuth() — so they can't
// simply join PUBLIC_ROUTES the way /docs or /billing do (that would also
// sweep in gated siblings like "/help/docs" via startsWith, and would skip
// the real Keycloak lifecycle entirely, losing the proper authenticated view
// for a signed-in visitor). Content-onpage SEO audit (2026-07-30): a
// logged-out visitor or crawler hitting either URL got the <title>/<meta
// description> for Help/Features in <head> but GenesisExperience (the
// homepage) as the actual rendered body, because the "unauthenticated"
// branch below unconditionally swapped in the landing page regardless of
// route. These two exact paths (not startsWith — "/help/workflow-recipes"
// and "/help/docs" must NOT match) render their own children instead, so the
// promised content actually reaches the page.
export const ANONYMOUS_RENDER_ROUTES = ["/help", "/features"];

// Set once per browser tab when we've already tried the silent top-level
// re-auth below, so a genuinely logged-out visitor can never bounce in a
// redirect loop (Keycloak answers prompt=none with ?error=login_required,
// which must land exactly once and then fall through to the landing page).
export const SSO_REDIRECT_ATTEMPTED = "vd_sso_redirect_attempted";

// Init retry policy (prod defect 2026-09-25 — "Login failed: undefined"
// mid-session death): keycloak-js can reject init() with a bare `undefined`
// or an unstringifiable value on transient failures (Cloudflare blip, the
// silent-SSO iframe timing out in a non-probe-shaped way, one dropped token
// exchange). Those are recoverable, so init() gets bounded background
// retries with backoff instead of an instant dead error screen. The first
// call is auth.init(); every retry is auth.retryInit(), which discards the
// dead keycloak-js instance and re-runs the full init (see lib/auth.ts —
// the library refuses to init() the same instance twice, and initPromise
// memoization would otherwise just re-await the same rejection).
export const INIT_RETRY_DELAYS_MS = [1200, 3000, 6000];

// Upper bound for the "Signing you in..." splash. Sits above the worst case of
// the retry policy above (4 attempts, ~10s of backoff, 3.5s probe each).
export const INIT_WATCHDOG_MS = 30_000;

// The generic message shown when keycloak-js gives us nothing displayable —
// a rejection value of undefined/null, an object that stringifies to "{}" or
// "[object Object]", or a literal "undefined" string. A user must never see
// the literal "Login failed: undefined".
const GENERIC_INIT_FAILURE =
  "We couldn't reach the sign-in service. Check your connection, then try again.";

/**
 * Turn an arbitrary keycloak-js rejection into a human-readable string.
 * keycloak-js rejects with plain objects ({error, error_description}),
 * Error instances, bare strings, or nothing at all — never render the raw
 * value blindly.
 */
export function describeInitError(err: unknown): string {
  const usable = (v: unknown): string | null =>
    typeof v === "string" && v.trim() && v.trim() !== "undefined" ? v.trim() : null;
  const direct = usable(err);
  if (direct) return direct;
  const anyErr = err as { message?: unknown; error?: unknown; error_description?: unknown } | null;
  const fromMessage = usable(anyErr?.message);
  if (fromMessage) return fromMessage;
  // keycloak-js's own rejection shape: {error: "...", error_description: "..."}
  const fromKcShape = [anyErr?.error, anyErr?.error_description].map(usable).filter(Boolean).join(": ");
  if (fromKcShape) return fromKcShape;
  try {
    const json = JSON.stringify(err);
    const fromJson = usable(json && json !== "{}" && json !== "null" ? json : null);
    if (fromJson) return fromJson;
  } catch {
    // circular structure - fall through
  }
  const str = String(err);
  if (str && str !== "[object Object]" && str !== "undefined") return str;
  return GENERIC_INIT_FAILURE;
}

/**
 * Recover a session that the IFRAME-based silent check couldn't see.
 *
 * keycloak-js's check-sso runs inside a hidden iframe, and every modern browser
 * now partitions/blocks third-party storage there - so for a person who IS
 * signed in, the probe either times out or reports "no session", and init()
 * resolves (or rejects) as logged-out. The Keycloak SSO cookie is perfectly
 * valid; it just isn't readable from that iframe. This is the exact reason a
 * refresh dumped a signed-in user on the landing page while clicking "Sign in"
 * dropped them straight into the dashboard with no credential prompt - a
 * TOP-LEVEL navigation carries the cookie, an iframe doesn't.
 *
 * So: when the silent path comes back empty AND we have a hint this visitor was
 * signed in, redirect the top-level window to Keycloak with `prompt=none`.
 * Keycloak sees the SSO cookie, immediately redirects back with a code, and the
 * normal callback path in lib/auth.js exchanges it - no login screen, no
 * flicker. If there genuinely is no session, Keycloak returns
 * `?error=login_required`, the guard above stops any second attempt, and the
 * landing page renders as before.
 *
 * Returns true if a redirect was started (caller should stop and let it happen).
 */
export function tryTopLevelSsoRecovery(): boolean {
  if (typeof window === "undefined") return false;
  // Already carrying a Keycloak answer (code or error) - never re-redirect.
  if (/[?#&](code|error)=/.test(window.location.href)) return false;
  if (window.sessionStorage.getItem(SSO_REDIRECT_ATTEMPTED) === "1") return false;
  // Only for someone we've actually seen signed in - a brand-new visitor must
  // still get the landing page instantly, with zero extra round-trip.
  if (!auth.hasAuthedHint()) return false;
  window.sessionStorage.setItem(SSO_REDIRECT_ATTEMPTED, "1");
  void auth.login({ prompt: "none", redirectUri: window.location.href });
  return true;
}

/**
 * Did the last silent (prompt=none) attempt fail because the person must finish
 * something on Keycloak's own screens - NOT because they're logged out?
 *
 * This is the second half of "after creating the organization it throws me out
 * to the landing page". A user whose required actions are pending (this realm
 * runs VERIFY_PROFILE, and Keycloak re-evaluates it at every authentication)
 * gets `error=interaction_required` from every prompt=none request while their
 * SSO session is perfectly alive - and keycloak-js reports that as plain "not
 * authenticated" (see lib/auth.ts's SilentAuthError block comment for the exact
 * lines in the installed 25.0.6). Rendering GenesisExperience there paints the
 * marketing landing over a live session and leaves the person with no way
 * forward at all, since the one thing that WOULD fix it is the interactive
 * login they were never offered.
 *
 * Returns the error when it's the interactive kind, null otherwise (including
 * for `login_required`, which really does mean logged out - the landing page is
 * the correct answer there and stays untouched).
 */
export function pendingInteractiveAuth(): SilentAuthError | null {
  const err = auth.getSilentAuthError();
  return isInteractionRequiredError(err) ? err : null;
}
