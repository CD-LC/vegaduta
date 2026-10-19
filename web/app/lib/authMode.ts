// Which sign-in the console uses, from the PUBLIC runtime config value
// NEXT_PUBLIC_AUTH_MODE (see lib/env.ts for how it reaches the browser).
//
//   keycloak  (default, and for any unrecognised or empty value) - the Keycloak OIDC flow in
//             lib/auth.ts, untouched.
//   local     - core's own single-user auth (core: app.auth.mode=local). Credentials go to the
//             same-origin /api/local-auth proxy; tokens come back through lib/localAuthClient.ts,
//             which presents the keycloak-js surface the rest of the console already reads.
//
// Deliberately dependency-free apart from env.ts, so proxy.js, server components and the
// keycloak-js-free public pages can all ask.
import { getPublicEnv } from "./env";

export type AuthMode = "keycloak" | "local";

export function authMode(): AuthMode {
  return getPublicEnv("NEXT_PUBLIC_AUTH_MODE")?.trim().toLowerCase() === "local" ? "local" : "keycloak";
}

export function isLocalAuth(): boolean {
  return authMode() === "local";
}

/** Same-origin prefix of the local-auth proxy (app/api/local-auth). */
export const LOCAL_AUTH_BASE = "/api/local-auth";
