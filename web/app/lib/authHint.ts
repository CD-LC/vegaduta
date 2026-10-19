// "Was signed in" hint (vd_authed) - a UI hint only, never an auth decision.
//
// Deliberately free of keycloak-js and every other import: public pages
// (landing chrome, /billing, /docs) read this hint through useAuthedHint, and
// pulling the auth client into their bundles would slow exactly the pages
// that must load fastest.
//
// The localStorage key and its cookie mirror (read server-side as layout.tsx's
// authedHint) decide whether "/" shows the "Signing you in…" splash or the
// landing. They used to live for a YEAR and survive logout (only the
// localStorage half was removed), so a signed-out visitor - or anyone whose
// Keycloak session had quietly idled out - got the splash plus a check-sso
// round-trip on "/" for a session that no longer existed. The hint now
// expires with the session itself: the refresh token's exp (Keycloak's SSO
// idle / max lifespan, whichever is first), re-stamped on every refresh, and
// logout clears both halves before leaving the page.
const AUTHED_HINT_KEY = "vd_authed";
const AUTHED_HINT_EXP_KEY = "vd_authed_exp";
// Used only when the refresh token carries no exp (offline tokens).
const AUTHED_HINT_FALLBACK_MS = 8 * 60 * 60 * 1000;

function writeHintCookie(maxAgeSeconds: number): void {
  const secure = window.location.protocol === "https:" ? "; Secure" : "";
  document.cookie =
    maxAgeSeconds > 0
      ? `${AUTHED_HINT_KEY}=1; Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`
      : `${AUTHED_HINT_KEY}=; Path=/; Max-Age=0; SameSite=Lax${secure}`;
}

export function markAuthedHint(sessionExpiryMs: number | null): void {
  if (typeof window === "undefined") return;
  const now = Date.now();
  const exp = sessionExpiryMs ?? now + AUTHED_HINT_FALLBACK_MS;
  const maxAge = Math.ceil((exp - now) / 1000);
  if (maxAge <= 0) return;
  try {
    window.localStorage.setItem(AUTHED_HINT_KEY, "1");
    window.localStorage.setItem(AUTHED_HINT_EXP_KEY, String(exp));
  } catch {
    // storage blocked - the cookie half below still works
  }
  writeHintCookie(maxAge);
}

export function clearAuthedHint(): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(AUTHED_HINT_KEY);
    window.localStorage.removeItem(AUTHED_HINT_EXP_KEY);
  } catch {
    // ignore
  }
  writeHintCookie(0);
}

/** True while a "was signed in" hint exists and the session it describes has not expired. Purges an expired hint. */
export function hasAuthedHint(): boolean {
  if (typeof window === "undefined") return false;
  try {
    if (window.localStorage.getItem(AUTHED_HINT_KEY) !== "1") return false;
    // A hint written before the expiry stamp existed has no exp: honour it
    // once - the next "ready" stamps it, the next "unauthenticated" clears it.
    const exp = Number(window.localStorage.getItem(AUTHED_HINT_EXP_KEY));
    if (Number.isFinite(exp) && exp > 0 && exp <= Date.now()) {
      clearAuthedHint();
      return false;
    }
    return true;
  } catch {
    return false;
  }
}
