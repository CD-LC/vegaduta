"use client";

import { useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import { writeAppAuth, appApi, normUser } from "./appBridge";
import { useTranslations } from "../../lib/i18n";

// ---------------------------------------------------------------------------
// The end-user sign-in overlay - rendered by the HOST (this studio page),
// never inside the sandboxed app. Mirrors the vanilla overlay in
// public/run-app.html. The app's end users are the builder's customers:
// accounts live on the published app itself (not console accounts), and the
// session token stays on this page, attached to call/ai requests the app
// makes - it never crosses the iframe boundary.

export function StudioSignInOverlay({
  slug,
  appName,
  onDone,
}: {
  slug: string;
  appName: string;
  onDone: (user: { id?: string; email?: string; name?: string } | null) => void;
}) {
    const tHC = useTranslations("hcBuildStudioStudioSignInOverlay");
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  // Mounted only while sign-in is pending: focus in, Tab trap, Escape = "Not now" (unless a request is in
  // flight), focus back to the opener on close (FE-041).
  useDialogFocus(dialogRef, null, () => {
    if (!busy) onDone(null);
  });

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await appApi(
        slug,
        mode === "signup" ? "/auth/signup" : "/auth/login",
        mode === "signup" ? { email, password, name } : { email, password }
      );
      if (!r.ok || !r.body?.token) {
        const code = r.body?.error;
        setError(
          code === "invalid_credentials" ? "Wrong email or password."
          : code === "email_taken" ? "That email already has an account - sign in instead."
          : code === "weak_password" ? "Use at least 8 characters for the password."
          : code === "bad_email" ? "That doesn't look like an email address."
          : code === "rate_limited" ? "Too many tries - wait a minute."
          : "Couldn't reach the sign-in service."
        );
        return;
      }
      const user = normUser(r.body.user);
      writeAppAuth(slug, { token: r.body.token, user });
      onDone(user);
    } catch {
      setError("Couldn't reach the sign-in service.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4 backdrop-blur-sm">
      <div ref={dialogRef} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="studio-signin-title" className="w-full max-w-sm rounded-2xl border border-border bg-card p-5 shadow-2xl">
        <p id="studio-signin-title" className="text-sm font-semibold text-foreground">{mode === "signin" ? "Sign in" : "Create your account"}</p>
        <p className="mt-0.5 text-xs text-muted-foreground">
          {appName} {tHC("hcBuildStudioStudioSignInOverlay.s2")}
        </p>
        <form
          className="mt-4 flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!busy && email.trim() && password.length >= (mode === "signup" ? 8 : 1)) void submit();
          }}
        >
          {mode === "signup" && (
            <Input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder={tHC("hcBuildStudioStudioSignInOverlay.s1")} aria-label={tHC("hcBuildStudioStudioSignInOverlay.s1")} />
          )}
          <Input autoFocus={mode === "signin"} value={email} onChange={(e) => setEmail(e.target.value)} type="email" placeholder="Email" aria-label="Email" />
          <Input
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            type="password"
            placeholder={mode === "signup" ? "Choose a password (8+ characters)" : "Password"}
            aria-label="Password"
          />
          {error && <p className="text-xs text-destructive">{error}</p>}
          <Button type="submit" size="sm" disabled={busy || !email.trim() || password.length < (mode === "signup" ? 8 : 1)} className="gap-1.5">
            {busy ? <Loader2 aria-hidden="true" size={13} className="animate-spin" /> : null}
            {mode === "signin" ? "Sign in" : "Create account"}
          </Button>
          <button
            type="button"
            onClick={() => setMode(mode === "signin" ? "signup" : "signin")}
            className="text-xs text-primary hover:underline"
          >
            {mode === "signin" ? "New here? Create an account" : "Have an account? Sign in"}
          </button>
          <button type="button" onClick={() => onDone(null)} className="text-xs text-muted-foreground hover:text-foreground">
            Not now
          </button>
        </form>
      </div>
    </div>
  );
}
