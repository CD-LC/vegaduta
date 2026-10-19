"use client";

import { useContext } from "react";
import { AuthContext } from "./AuthProvider";

// "Is there a signed-in session RIGHT NOW" - the gate every authenticated
// background call must sit behind (prod signed-in pass 2026-10-05, bug 8).
//
// Three kinds of page render without a usable token:
//   - PUBLIC_ROUTES (/templates, /directory, /plugins, /code, /forms/<token>...):
//     AuthProvider passes children straight through, no Keycloak lifecycle,
//     no AuthContext at all - even for someone signed in on another tab;
//   - /features and /help while Keycloak is still resolving (and for a
//     logged-out visitor): an anonymous AuthContext with username null;
//   - any route for a logged-out visitor.
// A call fired from any of them goes out with no bearer token and 401s. The
// localStorage "vd_authed" hint is NOT a session - it only says this browser
// signed in at some point - so gating on it produced a guaranteed 401 on
// every public page for every returning user.
//
// useContext (not useAuth()): useAuth() throws when no provider is above,
// which is exactly the public-route case this hook exists to answer "no" for.
export function useSignedIn(): boolean {
  const ctx = useContext(AuthContext) as { username?: string | null } | null;
  return !!ctx?.username;
}
