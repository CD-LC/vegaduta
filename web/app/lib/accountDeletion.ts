// Account self-deletion, recent-authentication flow.
//
// Core's DELETE /api/account refuses a session whose last real sign-in
// (`auth_time`) is older than app.account.deletion.max-auth-age (15 min by
// default) with 401 {code:"reauth_required", max_age}. The flow here:
//
//   1. The person clicks the confirm button -> deleteAccountOrReauth().
//   2. On reauth_required we send them through Keycloak with prompt=login and
//      max_age=0 (a real password prompt), returning to THIS page with
//      ?confirmDelete=1.
//   3. On return, consumePrearmedDeletion() says "reopen the confirmation
//      dialog". Nothing is deleted: the person must click confirm a second time.
//
// The only thing that survives the redirect is that non-sensitive URL marker.
// No token goes into web storage (the OSS build forbids it).
import { auth } from "./auth";
import { accountApi } from "./api/account";
import { isReauthRequired } from "./errors";

export const CONFIRM_DELETE_PARAM = "confirmDelete";

/** Mirrors core's default app.account.deletion.max-auth-age (15 min). */
export const DEFAULT_MAX_AUTH_AGE_SECONDS = 900;

/** `href` with the confirm marker set and any hash/callback state dropped. */
export function buildReauthReturnUrl(href: string): string {
  const url = new URL(href);
  url.hash = "";
  url.searchParams.set(CONFIRM_DELETE_PARAM, "1");
  return url.toString();
}

/** True when `href` carries the marker set by buildReauthReturnUrl. */
export function hasConfirmDeleteMarker(href: string): boolean {
  try {
    return new URL(href).searchParams.get(CONFIRM_DELETE_PARAM) === "1";
  } catch {
    return false;
  }
}

/** `href` without the marker, so a reload or shared link does not re-arm the dialog. */
export function stripConfirmDeleteMarker(href: string): string {
  const url = new URL(href);
  url.searchParams.delete(CONFIRM_DELETE_PARAM);
  return url.toString();
}

/** Start the Keycloak password re-prompt. Resolves only if the redirect could not start. */
export function beginReauthForDeletion(): Promise<void> {
  return auth.login({ prompt: "login", maxAge: 0, redirectUri: buildReauthReturnUrl(window.location.href) });
}

/**
 * Call once on the page that owns the delete dialog, after auth is ready.
 * Returns true (and removes the marker from the address bar) only when the
 * marker is present AND the token's auth_time is fresh, so a hand-typed
 * ?confirmDelete=1 on a stale session opens nothing. True means "open the
 * confirmation dialog"; it never means "delete".
 */
export function consumePrearmedDeletion(maxAgeSeconds: number = DEFAULT_MAX_AUTH_AGE_SECONDS): boolean {
  if (typeof window === "undefined" || !hasConfirmDeleteMarker(window.location.href)) return false;
  window.history.replaceState(window.history.state, "", stripConfirmDeleteMarker(window.location.href));
  const authTime = auth.getAuthTimeMs();
  return authTime !== null && Date.now() - authTime <= maxAgeSeconds * 1000;
}

export type DeleteAccountOutcome = "deleted" | "reauth";

/**
 * The confirm-click handler's body. "deleted": core accepted. "reauth": core
 * wants a fresh sign-in and the redirect has been started. Any other failure
 * rethrows for the caller's normal error display.
 */
export async function deleteAccountOrReauth(): Promise<DeleteAccountOutcome> {
  try {
    await accountApi.deleteAccount();
    return "deleted";
  } catch (e: unknown) {
    if (!isReauthRequired(e)) throw e;
    await beginReauthForDeletion();
    return "reauth";
  }
}
