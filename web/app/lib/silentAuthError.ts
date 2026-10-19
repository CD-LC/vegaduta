// Silent (prompt=none) Keycloak outcomes for the web console. Split out of
// auth.ts (file-size ratchet) - the pure helpers here have no keycloak-js
// dependency, and auth.ts re-exports the public ones so importers are
// unchanged. auth.init() calls captureSilentAuthError() BEFORE kc.init() and
// registers watchSilentCheckSsoErrors() once; auth.getSilentAuthError() reads
// the latched answer.
//
// ---------------------------------------------------------------------------
// prompt=none outcomes: "interaction_required" is NOT "logged out"
// ---------------------------------------------------------------------------
// Every silent path in this app (init's onLoad "check-sso" iframe, and
// AuthProvider's top-level `login({prompt:"none"})` recovery) asks Keycloak for
// a token WITHOUT any user interaction. Keycloak answers a failure with an OIDC
// error code, and the code matters enormously:
//
//   login_required        - there really is no SSO session. Logged out.
//   interaction_required  - the SSO SESSION IS ALIVE, but a token cannot be
//                           issued without showing the person a screen. A
//                           pending required action is exactly this case, and
//                           this realm has VERIFY_PROFILE enabled with
//                           email/firstName/lastName marked required in the
//                           declarative User Profile - re-evaluated at EVERY
//                           authentication, so a user missing those fields hits
//                           it on every single silent attempt.
//
// keycloak-js 25.0.6 (the installed version) collapses both into "not
// authenticated" and then destroys the evidence. From its own
// dist/keycloak.js, processCallback():
//
//     if (error) {
//         if (prompt != 'none') { ...setError({error, error_description})... }
//         else { promise && promise.setSuccess(); }        // <- no argument
//         return;
//     }
//
// i.e. on a prompt=none failure init() RESOLVES, with kc.authenticated
// undefined - indistinguishable from "no session found" to any caller. And
// processInit() runs `window.history.replaceState(..., callback.newUrl)` (which
// parseCallbackUrl builds with error/error_description stripped) BEFORE that
// promise settles, so by the time our own .then() runs the address bar no
// longer carries the reason either.
//
// So the only place the distinction is still observable is: read the URL
// ourselves before kc.init() touches it (captureSilentAuthError below, called
// from init()), and listen for the silent-check-sso iframe's own postMessage
// (watchSilentCheckSsoErrors below) - public/silent-check-sso.html posts its
// full location.href, which carries the same error params, and our listener is
// registered before keycloak-js's so it always sees the message first.
//
// Honest limitation: when the browser blocks the hidden iframe's storage
// outright (third-party partitioning - see AuthProvider's
// tryTopLevelSsoRecovery) the iframe never answers at all, so there is NO error
// code to read on that path. That case is unchanged: the top-level prompt=none
// recovery is what produces a readable answer for it, and this capture then
// classifies THAT answer correctly.
export interface SilentAuthError {
  error: string;
  errorDescription: string | null;
}

// Codes that mean "session alive, finish something interactively" rather than
// "you are logged out". consent_required / account_selection_required are the
// same family (Keycloak can return them to a prompt=none request) and want the
// same handling; login_required is deliberately NOT here - that one genuinely
// means logged out and must keep rendering the landing page.
const INTERACTION_REQUIRED_ERRORS = ["interaction_required", "consent_required", "account_selection_required"];

export function isInteractionRequiredError(err: SilentAuthError | null | undefined): boolean {
  return !!err && INTERACTION_REQUIRED_ERRORS.indexOf(err.error) !== -1;
}

// Pull ?error= / #error= (+ error_description) out of a callback URL. A named
// export because it is pure and directly unit-testable, unlike everything that
// depends on the keycloak-js singleton.
export function parseSilentAuthError(href: string): SilentAuthError | null {
  let url: URL;
  try {
    url = new URL(href);
  } catch {
    return null;
  }
  // keycloak-js's default responseMode is "fragment", so the error normally
  // rides in the hash; a client configured for query mode puts it in the search
  // string instead. Read both rather than betting on one.
  const hash = url.hash.startsWith("#") ? url.hash.slice(1) : url.hash;
  const fromHash = new URLSearchParams(hash);
  const error = fromHash.get("error") || url.searchParams.get("error");
  if (!error) return null;
  const description = fromHash.get("error_description") || url.searchParams.get("error_description");
  return { error, errorDescription: description || null };
}

let silentAuthErrorCaptured = false;
let silentAuthError: SilentAuthError | null = null;

// Must run BEFORE kc.init() - see the block comment above for why (keycloak-js
// replaceState()s the params away as its first act on a callback load).
export function captureSilentAuthError(): void {
  if (typeof window === "undefined" || silentAuthErrorCaptured) return;
  silentAuthErrorCaptured = true;
  silentAuthError = parseSilentAuthError(window.location.href);
}

let silentCheckSsoWatched = false;
export function watchSilentCheckSsoErrors(): void {
  if (typeof window === "undefined" || silentCheckSsoWatched) return;
  silentCheckSsoWatched = true;
  window.addEventListener("message", (event: MessageEvent) => {
    // silent-check-sso.html posts its own location.href, from OUR origin.
    // Anything else on the message bus (dev tooling, embedded widgets) is none
    // of our business.
    if (event.origin !== window.location.origin) return;
    if (typeof event.data !== "string" || !event.data.startsWith(window.location.origin)) return;
    const err = parseSilentAuthError(event.data);
    if (!err) return;
    silentAuthError = err;
    silentAuthErrorCaptured = true;
  });
}

/**
 * Why the last silent (prompt=none) attempt failed, or null if it didn't fail
 * or the reason was never observable. Feed it to isInteractionRequiredError()
 * to tell "must finish something interactively" apart from "logged out".
 */
export function getSilentAuthError(): SilentAuthError | null {
  captureSilentAuthError();
  return silentAuthError;
}
