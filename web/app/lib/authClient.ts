// The keycloak-js client behind auth.ts (split out of it): lazy loading of the
// keycloak-js chunk, the realm/client configuration, the single Keycloak
// instance with its token lifecycle hooks, and the skew-adjusted session expiry
// the "was signed in" hint is stamped with. auth.ts owns the public `auth` API.
import { reportClientError } from "./clientLogger";
// An ESM import, deliberately - NOT `require("keycloak-js")` (which
// this was until 2026-09-17). keycloak-js ships two entries and they are not
// equivalent for a browser bundle: the `require` condition resolves to
// dist/keycloak.js, a UMD build whose header does
// `factory(require('crypto'), require('buffer'))` unconditionally, so the
// bundler had to ship crypto-browserify + buffer + stream polyfills
// (~1.1 MB of dev JS, ~780 KB of it crypto-browserify alone) into the
// static closure of every page that imports this module - which, via
// layout.tsx's PUBLIC_ROUTES import and DemoLanding's `auth.login`, is every
// page including the anonymous landing. The `import` condition resolves to
// dist/keycloak.mjs, which imports js-sha256 - whose package.json marks
// `crypto` and `buffer` as `false` for browsers - so the polyfills are simply
// never pulled. Same code, same API, same 25.0.6; only the polyfills go.
//
// LAZY since 2026-09-30 (review FE-013): the module is now a dynamic
// import() (loadKeycloak below), so keycloak-js is its own chunk, fetched only
// when something actually needs a Keycloak instance - init() on a private
// route, or login(). Public marketing pages, where AuthProvider never calls
// init(), no longer download it. Before the chunk has loaded, getKeycloak()
// returns null, which every sync caller already treats as "not signed in".
type KeycloakCtor = typeof import("keycloak-js").default;
let KeycloakClass: KeycloakCtor | null = null;
let keycloakModulePromise: Promise<void> | null = null;
export function loadKeycloak(): Promise<void> {
  // Local auth mode (NEXT_PUBLIC_AUTH_MODE=local) has no keycloak-js chunk to fetch.
  if (isLocalAuth()) return Promise.resolve();
  if (KeycloakClass) return Promise.resolve();
  if (keycloakModulePromise === null) {
    keycloakModulePromise = import("keycloak-js").then(
      (mod) => {
        KeycloakClass = mod.default;
      },
      (err: unknown) => {
        keycloakModulePromise = null; // let a later call retry the chunk
        throw err;
      }
    );
  }
  return keycloakModulePromise;
}
import { markAuthedHint as stampAuthedHint } from "./authHint";
import { getPublicEnv, publicKeycloakUrl } from "./env";
import { isLocalAuth } from "./authMode";
import { LocalAuthClient } from "./localAuthClient";

// In local mode there is no Keycloak to point at; skip the "URL not configured" production warning.
export const KEYCLOAK_URL = isLocalAuth() ? "" : publicKeycloakUrl();
export const KEYCLOAK_REALM = getPublicEnv("NEXT_PUBLIC_KEYCLOAK_REALM") || "agentic-ai";
export const KEYCLOAK_CLIENT_ID = getPublicEnv("NEXT_PUBLIC_KEYCLOAK_CLIENT_ID") || "agentic-ai-web";

let keycloakInstance: any = null;

// Local auth mode: the same hooks keycloak-js gets below, on the keycloak-js-shaped adapter.
function createLocalAuthClient(): LocalAuthClient {
  const client = new LocalAuthClient();
  client.onTokenExpired = () => {
    client.updateToken(-1).catch(() => {
      // Quiet on purpose, same as the Keycloak handler: onAuthRefreshError reports it and
      // IdleLogout owns the visible prompt.
    });
  };
  client.onAuthSuccess = () => markAuthedHint();
  client.onAuthRefreshSuccess = () => markAuthedHint();
  client.onAuthRefreshError = () => {
    reportClientError("auth_failure", "Local auth token refresh failed (onAuthRefreshError)", {
      reason: "refresh_error",
    });
  };
  return client;
}

// keycloak-js touches `window`, so it can only be constructed in the
// browser - Next.js also renders this module on the server during the
// build/SSR pass, which would throw without this guard.
export function getKeycloak(): any {
  if (typeof window === "undefined") return null;
  if (isLocalAuth()) {
    if (!keycloakInstance) keycloakInstance = createLocalAuthClient();
    return keycloakInstance;
  }
  if (!KeycloakClass) return null;
  if (!keycloakInstance) {
    // (History: this used to `require("keycloak-js")` and pick
    // `mod.default || mod` to survive CJS/ESM interop ambiguity. The static
    // ESM import at the top of the file resolves the `import` condition, whose
    // dist/keycloak.mjs has a single, unambiguous `export default Keycloak` -
    // so the interop dance is gone along with the polyfills. See the import.)
    keycloakInstance = new KeycloakClass({
      url: KEYCLOAK_URL,
      realm: KEYCLOAK_REALM,
      clientId: KEYCLOAK_CLIENT_ID,
    });
    // THE missing line behind "I came back to my laptop and got 'We couldn't
    // verify your session'". Without an onTokenExpired handler, keycloak-js
    // does NOTHING when the access token lapses - it only ever refreshes when
    // some caller happens to ask for a token (api.ts's apiFetch, or
    // SystemHealthProvider's 20s poll). Both of those stop entirely in a
    // backgrounded/frozen tab or a sleeping machine, so the refresh token
    // quietly aged past the realm's ssoSessionIdleTimeout and the NEXT
    // updateToken() - the one the person's own click triggered - was already
    // doomed. keycloak-js arms its own timer for this callback off the token's
    // real `exp`, so this makes the refresh happen on the token's schedule
    // instead of on request traffic's schedule.
    keycloakInstance.onTokenExpired = () => {
      // -1 == "refresh regardless of remaining validity".
      keycloakInstance?.updateToken(-1).catch(() => {
        // Deliberately quiet: onAuthRefreshError below already reports it, and
        // the visible prompt is owned by IdleLogout (see promptReauth).
      });
    };
    // Distinguishes "the session genuinely died" from a transient blip in
    // telemetry - previously both were invisible until a user-facing failure.
    // Slide the "was signed in" hint's expiry with the session (authHint.ts).
    // No token copy is persisted (see LEGACY_TOKEN_CACHE_KEY) - only the hint.
    keycloakInstance.onAuthSuccess = () => markAuthedHint();
    keycloakInstance.onAuthRefreshSuccess = () => markAuthedHint();
    keycloakInstance.onAuthRefreshError = () => {
      reportClientError("auth_failure", "Keycloak silent token refresh failed (onAuthRefreshError)", {
        reason: "refresh_error",
      });
    };
  }
  return keycloakInstance;
}

/** True once the keycloak-js chunk has loaded (getKeycloak() can then build an instance). */
export function isKeycloakLoaded(): boolean {
  return isLocalAuth() || KeycloakClass !== null;
}

/** The current instance without constructing one (null before the first getKeycloak()). */
export function peekKeycloakInstance(): any {
  return keycloakInstance;
}

/** Drop the instance so the next getKeycloak() builds a fresh, un-initialised one. */
export function resetKeycloakInstance(): void {
  keycloakInstance = null;
}

// See authHint.ts - stamped with the live session's expiry.
export function markAuthedHint(): void {
  stampAuthedHint(getSessionExpiryMs());
}

// keycloak-js corrects for clock skew between this browser and Keycloak
// (kc.timeSkew, populated at init from the token's iat). Every deadline this
// module hands out is therefore returned in LOCAL clock milliseconds, already
// skew-adjusted, so a caller can compare it against Date.now() directly.
export function expiryToLocalMs(exp: unknown): number | null {
  if (typeof exp !== "number" || !Number.isFinite(exp) || exp <= 0) return null;
  const skew = getKeycloak()?.timeSkew;
  return (exp + (typeof skew === "number" ? skew : 0)) * 1000;
}

/**
 * When the SESSION itself can no longer be renewed (local clock ms), or null if
 * unknown/unbounded. This is the refresh token's exp, which Keycloak sets to
 * whichever comes first - the SSO idle timeout or the SSO max lifespan - and
 * slides forward on every successful refresh. It is the only honest deadline to
 * put in front of a user: a client-side guess would either nag early or promise
 * time the server will not honour.
 */
export function getSessionExpiryMs(): number | null {
  return expiryToLocalMs(getKeycloak()?.refreshTokenParsed?.exp);
}
