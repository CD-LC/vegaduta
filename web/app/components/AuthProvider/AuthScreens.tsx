"use client";

import { auth, type SilentAuthError } from "../../lib/auth";
import Logo from "../Logo";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";

// Shown instead of the marketing landing when the session is alive but blocked
// on a Keycloak-hosted screen. Deliberately a BUTTON rather than an automatic
// redirect: auth.login() with no prompt is a full top-level navigation to
// Keycloak, and auto-firing it on an outcome we reached by being redirected is
// exactly the shape of a loop. One click, no loop, and the person is told what
// is happening instead of being bounced.
export function InteractionRequiredScreen({ detail }: { detail: SilentAuthError | null }) {
    const tHC = useTranslations("hcComponentsAuthProviderAuthScreens");
  return (
    <main className="auth-shell">
      <div className="auth-card" style={{ textAlign: "center" }}>
        <Logo size={48} />
        <p style={{ marginTop: "1rem", fontWeight: 600 }}>{tHC("s8")}</p>
        <p className="muted" style={{ marginTop: "0.5rem" }}>
          {tHC("s7")}
        </p>
        {/* No prompt option: this must reuse the live SSO session so Keycloak
            shows the pending screen and sends the person straight back, rather
            than making them re-enter credentials they never lost. login()'s own
            default redirectUri (origin + pathname) returns them here. */}
        <Button style={{ marginTop: "1.25rem" }} onClick={() => auth.login()}>
          {tHC("s6")}
        </Button>
        {detail?.error && (
          <p className="muted" style={{ marginTop: "0.75rem", fontSize: "0.75rem" }}>
            {tHC("s5")} {detail.error}
          </p>
        )}
      </div>
    </main>
  );
}

// The "Signing you in…" splash. Uses the AXON theme-aware .auth-shell/.auth-card
// CSS classes (globals.css), which respond to <html data-mode="light|dark">.
export function SigningInSplash() {
    const tHC = useTranslations("hcComponentsAuthProviderAuthScreens");
  return (
    <main className="auth-shell">
      {/* size 48 (was 40): this is the full-screen loading panel, and at
          40 the mark read as a small smudge — "logo in page loading panel
          is not properly visible". >=40 also puts Logo into its detailed
          /logo-mark.png branch, which is the right artwork at this size.

          AXON theme-aware styling: .auth-card includes theme-responsive
          borders, backgrounds, and shadows that adapt to light/dark mode.
          The splash uses the same CSS variables as the RouteTransition
          overlay for visual consistency across route changes. */}
      <div className="auth-card" style={{ textAlign: "center" }}>
        <Logo size={48} />
        <p className="muted" style={{ marginTop: "1rem", letterSpacing: "0.5px" }}>
          {tHC("s4")}
        </p>
      </div>
    </main>
  );
}

// Shown only after the init retries are exhausted and no live session exists.
// `error` is always a real, human-readable string (describeInitError).
export function LoginFailedScreen({ error, onRetry }: { error: string | null; onRetry: () => void }) {
    const tHC = useTranslations("hcComponentsAuthProviderAuthScreens");
  return (
    <main className="auth-shell">
      <div className="auth-card" style={{ textAlign: "center" }}>
        <Logo size={48} />
        <p style={{ marginTop: "1rem", fontWeight: 600 }}>{tHC("s3")}</p>
        <p className="muted" style={{ marginTop: "0.5rem" }}>{error}</p>
        <p className="muted" style={{ marginTop: "0.5rem", fontSize: "0.8rem", wordBreak: "break-all" }}>
          {tHC("s2")} <code data-testid="signin-endpoint">{auth.signInEndpoint()}</code>
        </p>
        <Button style={{ marginTop: "1.25rem" }} onClick={onRetry}>
          {tHC("s1")}
        </Button>
      </div>
    </main>
  );
}
