// Read-only views of the signed-in person's Keycloak token (roles, names, ids,
// deadlines) and realm URLs - split out of auth.ts, which exposes them on `auth`.
import { KEYCLOAK_REALM, KEYCLOAK_URL, expiryToLocalMs, getKeycloak } from "./authClient";

// Roles land in the JWT's realm_access.roles claim (see
// KeycloakRoleConverter.java on the backend, which reads the same claim to
// build Spring's ROLE_* authorities) - kc.realmAccess mirrors that directly,
// no manual token decoding needed.
export function getRoles(): string[] {
  const kc = getKeycloak();
  return kc?.realmAccess?.roles || [];
}

export function getUsername(): string | null {
  const kc = getKeycloak();
  return kc?.tokenParsed?.preferred_username || kc?.tokenParsed?.email || null;
}

// The person's first (given) name for a friendly, personal identity label in
// the shell — direct feedback: "in menu bar - display only First Name First".
// Prefers the standard OIDC `given_name` claim, then the first token of the
// full `name` claim, then the local-part of preferred_username/email as a last
// resort (never the raw "p.n@y.com" email, which is what getUsername() returns).
export function getFirstName(): string | null {
  const tp = getKeycloak()?.tokenParsed as Record<string, unknown> | undefined;
  if (!tp) return null;
  const given = tp.given_name;
  if (typeof given === "string" && given.trim()) return given.trim();
  const full = tp.name;
  if (typeof full === "string" && full.trim()) return full.trim().split(/\s+/)[0];
  const uname = (tp.preferred_username || tp.email) as string | undefined;
  if (uname) return uname.split("@")[0];
  return null;
}

// The JWT's raw `sub` claim - deliberately NOT the same value as
// getUsername() above. AgentController computes its long-term-memory
// subjectKey server-side from `principal.getName()`, and Spring Security's
// JwtAuthenticationToken.getName() returns the token's subject claim, not
// preferred_username/email - so anything client-side that needs to match
// that same subjectKey (e.g. fetching GET /api/agents/{id}/memories) has to
// read `sub` specifically, not the display name.
export function getUserId(): string | null {
  const kc = getKeycloak();
  return kc?.tokenParsed?.sub || null;
}

// The tenant this user belongs to — the `tenant_id` claim (Keycloak tenant_id
// protocol mapper). null for a freshly social-logged-in (e.g. Google) user who
// hasn't been onboarded to an organization yet, and for platform admins (who
// are tenant-less by design). AuthProvider uses this to route the former into
// the onboarding step instead of a broken, tenant-less dashboard.
export function getTenantId(): string | null {
  const tp = getKeycloak()?.tokenParsed as Record<string, unknown> | undefined;
  const t = tp?.tenant_id;
  return typeof t === "string" && t.trim() ? t : null;
}

// Keycloak's own hosted Account Console URL - the standard, secure place a
// user updates their own profile (name, email, password, 2FA, sessions).
// This app deliberately never renders a profile-edit form of its own: the
// identity of record lives in Keycloak, and letting the browser POST profile
// changes through the app would just be a thin, less-safe proxy for the
// console Keycloak already ships. keycloak-js's createAccountUrl() builds the
// realm's `/account` URL with a referrer back to this page, so "← Back to the
// application" returns the user here. Returns null before init() (no realm
// endpoints resolved yet) so the caller can hide the affordance until it's
// ready rather than linking to a dead URL.
export function accountUrl(): string | null {
  const kc = getKeycloak();
  if (!kc || typeof kc.createAccountUrl !== "function") return null;
  try {
    return kc.createAccountUrl() || null;
  } catch {
    return null;
  }
}

/** When the current ACCESS token lapses (local clock ms), or null. Refreshed silently long before this - see startSessionKeepalive. */
export function getAccessTokenExpiryMs(): number | null {
  return expiryToLocalMs(getKeycloak()?.tokenParsed?.exp);
}

// The Keycloak LOGIN-session identity — `sid` (KC9+) or `session_state`. Unlike
// `sub` (stable per user), this changes on every fresh login, so comparing it
// lets SessionAccentEngine re-roll the accent on each new login even within the
// same browser tab. Null if neither claim is present (older realm/client), in
// which case the accent falls back to once-per-sessionStorage-lifetime.
export function getSessionId(): string | null {
  const tp = getKeycloak()?.tokenParsed as Record<string, unknown> | undefined;
  const s = tp?.sid ?? tp?.session_state;
  return typeof s === "string" && s ? s : null;
}

/**
 * When the person LAST typed their credentials (the token's `auth_time`, local
 * clock ms, skew-adjusted), or null if the claim is absent. This is what core's
 * recent-authentication gate reads; a silent refresh does not move it.
 */
export function getAuthTimeMs(): number | null {
  return expiryToLocalMs(getKeycloak()?.tokenParsed?.auth_time);
}

// Is there a genuinely authenticated Keycloak session RIGHT NOW? Reads the live
// keycloak-js instance state, so it is correct independent of React auth
// context. SessionAccentEngine needs this because it mounts under
// AppChromeProvider, which AuthProvider renders on PUBLIC_ROUTES too (children
// pass-through) — i.e. also for logged-out visitors on /explore, /docs, etc.,
// where auth.init() never ran (kc.authenticated is falsy). Gating the per-login
// accent on this keeps those anonymous public pages on the landing per-refresh
// random instead of a sticky session accent.
export function isAuthenticated(): boolean {
  const kc = getKeycloak();
  return !!(kc && kc.authenticated && kc.token);
}

/**
 * The Keycloak realm URL the browser talks to (no secrets). Shown on the
 * sign-in failure screens so a misconfigured public URL (wrong scheme, wrong
 * host) is visible instead of an endless "Signing you in" splash.
 */
export function signInEndpoint(): string {
  return `${KEYCLOAK_URL}/realms/${encodeURIComponent(KEYCLOAK_REALM)}`;
}
