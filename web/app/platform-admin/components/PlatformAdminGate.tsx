"use client";

import { createContext, useContext, useEffect, type ReactNode } from "react";
import { AuthContext } from "../../components/AuthProvider";
import { auth } from "../../lib/auth";
import Logo from "./Logo";

// Platform-admin access gate for the merged console (2026 merge of the
// standalone web-admin app into web/app/platform-admin).
//
// THE MODEL IS UNCHANGED FROM THE OLD STANDALONE APP: this section is
// role-gated to Keycloak's `platform-admin` realm role - a hand-granted,
// never-auto-provisioned role (see PlatformController.java's javadoc and
// SecurityConfig's `/api/platform/**` -> ROLE_platform-admin rule on the
// backend, which is the authoritative enforcement; this gate is UX, not a
// security boundary).
//
// What changed vs the old web-admin/app/components/AuthProvider.tsx:
//   - Keycloak init now comes from web/'s auth.ts (agentic-ai-web client,
//     onLoad "check-sso"), not a second agentic-ai-platform-admin client -
//     the section lives on the console's own origin, so it shares the
//     console session.
//   - The "not signed in" case is handled by the ROOT AuthProvider: it does
//     not render children at all for an unauthenticated visitor on a
//     non-public route, and for /platform-admin it now shows a
//     sign-in redirect instead of the marketing landing (see
//     AuthProvider.tsx's isPlatformAdminRoute branch).
//   - This gate owns only the "denied" state: a signed-in user without the
//     platform-admin role gets the same clear access-restricted screen the
//     standalone app showed.

// Same hook name/shape the standalone app's AuthProvider exported
// ({ username, roles, logout }) so AdminShell and friends stay untouched.
export interface PlatformAdminAuth {
  username: string | null;
  roles: string[];
  logout: () => void;
}

const PlatformAdminAuthContext = createContext<PlatformAdminAuth | null>(null);

export function useAuth(): PlatformAdminAuth {
  const ctx = useContext(PlatformAdminAuthContext);
  if (!ctx) throw new Error("useAuth() must be used inside <PlatformAdminGate>");
  return ctx;
}

export default function PlatformAdminGate({ children }: { children: ReactNode }) {
  // web's root AuthProvider only mounts children once Keycloak init has
  // resolved "ready" on a non-public route, so by the time this renders the
  // session is real. A null context is unreachable in practice (anonymous
  // render routes don't include /platform-admin), but fail closed anyway -
  // a gate that can't see the session must never render children.
  const ctx = useContext(AuthContext);

  useEffect(() => {
    // Defence-in-depth: if this ever mounts without an authenticated
    // context (a future route that renders children anonymously), send the
    // visitor through a real Keycloak login rather than showing the denied
    // screen to someone who simply isn't signed in yet.
    if (ctx === null && !auth.isAuthenticated()) {
      void auth.login({ redirectUri: window.location.href });
    }
  }, [ctx]);

  if (ctx === null) {
    return (
      <main className="auth-shell">
        <div className="auth-card" style={{ textAlign: "center" }}>
          <Logo size={48} />
          <p className="muted" style={{ marginTop: "1rem" }}>
            Signing you in…
          </p>
        </div>
      </main>
    );
  }

  if (!ctx.isPlatformAdmin) {
    return (
      <main className="auth-shell">
        <div className="auth-card" style={{ textAlign: "center", maxWidth: "440px" }}>
          <Logo size={48} />
          <h1 style={{ fontSize: "1.1rem", marginTop: "1.25rem" }}>Access restricted</h1>
          <p className="muted" style={{ marginTop: "0.5rem" }}>
            You&apos;re signed in as <strong>{ctx.username}</strong>, but this account doesn&apos;t hold the
            platform-admin role this section requires. Ask an existing platform admin to grant it to you in
            Keycloak, or sign in with a different account.
          </p>
          <button
            type="button"
            className="cross-link"
            style={{ marginTop: "1rem" }}
            onClick={() => auth.logout()}
          >
            Sign out
          </button>
        </div>
      </main>
    );
  }

  return (
    <PlatformAdminAuthContext.Provider
      value={{ username: ctx.username, roles: ctx.roles, logout: () => void auth.logout() }}
    >
      {children}
    </PlatformAdminAuthContext.Provider>
  );
}
