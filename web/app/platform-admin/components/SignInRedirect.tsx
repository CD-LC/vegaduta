"use client";

import { useEffect, useState } from "react";
import { auth } from "../../lib/auth";
import { Button } from "../../components/ui/button";
import Logo from "./Logo";
import { useTranslations } from "../../lib/i18n";

// Rendered by the ROOT AuthProvider (web/app/components/AuthProvider.tsx)
// when an unauthenticated visitor hits a /platform-admin/* route. The tenant
// console's default for that state is the marketing GenesisExperience
// landing - wrong for this section, which has no public surface at all
// (every screen is an internal admin tool). The old standalone web-admin
// app used Keycloak "login-required" onLoad for the same reason: nothing to
// show a logged-out visitor, so send them straight to hosted login and
// bring them back to THIS url.
//
// auth.login() navigates away on success. If it rejects (keycloak-js cannot
// build the authorize URL: non-secure context, unreachable Keycloak) or the
// navigation never happens within REDIRECT_TIMEOUT_MS (blocked mixed
// content), the splash turns into an error with Retry and the failing
// endpoint instead of spinning forever (SE-01).
export const REDIRECT_TIMEOUT_MS = 15_000;

export default function SignInRedirect() {
    const tHC = useTranslations("hcPlatformadminComponentsSignInRedirect");
  const [failure, setFailure] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const timer = setTimeout(() => {
      if (!cancelled) setFailure("The sign-in page did not open.");
    }, REDIRECT_TIMEOUT_MS);
    auth.login({ redirectUri: window.location.href }).catch((err: unknown) => {
      if (cancelled) return;
      const detail = err instanceof Error && err.message ? err.message : null;
      setFailure(detail ?? "We could not start sign-in.");
    });
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [attempt]);

  if (failure) {
    return (
      <main className="auth-shell">
        <div className="auth-card" style={{ textAlign: "center" }}>
          <Logo size={48} />
          <p style={{ marginTop: "1rem", fontWeight: 600 }}>{tHC("s4")}</p>
          <p className="muted" style={{ marginTop: "0.5rem" }}>{failure}</p>
          <p className="muted" style={{ marginTop: "0.5rem", fontSize: "0.8rem", wordBreak: "break-all" }}>
            {tHC("s3")} <code data-testid="signin-endpoint">{auth.signInEndpoint()}</code>
          </p>
          <Button
            style={{ marginTop: "1.25rem" }}
            onClick={() => {
              setFailure(null);
              setAttempt((n) => n + 1);
            }}
          >
            {tHC("s2")}
          </Button>
        </div>
      </main>
    );
  }

  return (
    <main className="auth-shell">
      <div className="auth-card" style={{ textAlign: "center" }}>
        <Logo size={48} />
        <p className="muted" style={{ marginTop: "1rem" }}>
          {tHC("s1")}
        </p>
      </div>
    </main>
  );
}
