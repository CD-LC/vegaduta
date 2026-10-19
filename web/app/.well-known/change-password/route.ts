import { NextResponse } from "next/server";
import { getPublicEnv, publicKeycloakUrl } from "../../lib/env";
import { isLocalAuth } from "../../lib/authMode";

// W3C "well-known URL for changing passwords" (https://w3c.github.io/webappsec-change-password-url/).
// Password managers (1Password, Chrome, Safari, etc.) probe this exact path
// to find a site's password-change flow instead of guessing. This app has no
// password form of its own - Keycloak owns the credential (see lib/auth.ts's
// accountUrl() comment) - so this just redirects to the realm's account
// console, the same URL createAccountUrl() builds client-side.
const KEYCLOAK_URL = isLocalAuth() ? "" : publicKeycloakUrl();
const KEYCLOAK_REALM = getPublicEnv("NEXT_PUBLIC_KEYCLOAK_REALM") || "agentic-ai";

// Runtime config (lib/env.ts): without this the handler is prerendered at
// build time and the redirect target is frozen to the build-time Keycloak URL.
export const dynamic = "force-dynamic";

export async function GET() {
  // Local auth mode has no Keycloak account console; the sign-in page owns password change.
  // A relative Location, so it is correct behind any proxy or hostname.
  if (isLocalAuth()) return new Response(null, { status: 302, headers: { Location: "/login?change=1" } });
  return NextResponse.redirect(`${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/account`, 302);
}
