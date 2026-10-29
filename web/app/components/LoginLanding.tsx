"use client";

import { useState } from "react";
import type { FormEvent } from "react";
import Link from "next/link";
import { motion } from "motion/react";
import { Mail, ShieldCheck } from "lucide-react";
import { auth } from "../lib/auth";
import { useAuthedHint } from "../lib/hooks/useAuthedHint";
import { useTranslations } from "../lib/i18n";
import AuthBrandPanel from "./AuthBrandPanel";
import LanguageSwitcher from "./LanguageSwitcher";
import { Button } from "./ui/button";
import SocialLoginRow from "./signup/SocialLoginRow";
import { Card } from "./ui/card";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import ThemePicker from "./ThemePicker";
import BrandText from "./BrandText";

// "Sign in" screen - one centred column (auth-simplify pass, 2026-09-24):
// brand lockup + short title (AuthBrandPanel, now a slim strip), a glass card
// with the email field and one primary pill CTA, then the realm-driven social
// row, and a "new here? start free" switch under the card. The 50/50 brand
// banner is gone - see AuthBrandPanel.tsx's header for the feedback and the
// reference pages. Layout/styling: app/auth.css.
//
// This app has never had its own password backend: every credential check
// happens on Keycloak's hosted login page (auth.js "check-sso" flow). So the
// screen is honestly SSO-first - Google/GitHub/Microsoft go straight to
// Keycloak's IdPs, and "Continue" seeds Keycloak's loginHint (auth.login) so
// the real, secure credential step happens where it actually can. No
// fabricated password/reset flows this screen has no way to perform.
export default function LoginLanding() {
  const t = useTranslations("signin");
  const authedHint = useAuthedHint();
  const [email, setEmail] = useState("");
  const [redirecting, setRedirecting] = useState(false);

  // redirectUri is explicitly the app root, NOT keycloak-js's default (this
  // /login URL): AuthProvider treats /login as always-public and never runs
  // its Keycloak lifecycle here, so bouncing back to /login would leave the
  // auth code unprocessed in the URL and look like a silent failure.
  // prompt: 'login' forces re-authentication even if there's an existing session.
  function startLogin() {
    setRedirecting(true);
    // `void`: the promise resolves only by navigating away to Keycloak - there
    // is nothing to await or handle here (no-floating-promises lint).
    void auth.login({
      redirectUri: `${window.location.origin}/`,
      prompt: "login",
      ...(email ? { loginHint: email } : {}),
    });
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    startLogin();
  }

  return (
    <main className="auth-main vd-auth text-foreground antialiased">
      {/* One top-right cluster for both controls (they used to overlap: the
          theme picker was fixed top-right and the language switcher sat
          absolute top-right of the form column below md). */}
      <div className="vd-auth__controls">
        <LanguageSwitcher />
        <ThemePicker />
      </div>

      <motion.div
        className="vd-auth__col"
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
      >
        {/* The headline used to read "Your agents handled 2,834 tasks while
            you were away." to EVERY visitor - a static, invented number,
            against this codebase's own no-fabrication rule (funnel audit,
            2026-09-09). One plain sentence about what signing in gets you. */}
        <AuthBrandPanel title={t("welcomeBack")} subtitle={t("welcomeSubtitle")} />

        <Card className="auth-card vd-auth__card">
          {/* A signed-in visitor who reached /login (stale link, "Sign in"
              muscle memory) gets a way back to their console instead of a
              form implying they are logged out. The form below stays live -
              prompt:"login" is still the switch-account path. */}
          {authedHint && (
            <p className="vd-auth__switch" style={{ marginTop: 0 }}>
              {t("alreadySignedIn")}{" "}
              <Link href="/">
                <BrandText text={t("openConsole")} />
              </Link>
            </p>
          )}
          <form onSubmit={handleSubmit} className="vd-auth__form">
            <div className="vd-auth__field">
              <Label htmlFor="login-email">{t("workEmailLabel")}</Label>
              <div className="relative">
                <Mail
                  aria-hidden="true"
                  className="pointer-events-none absolute start-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                />
                <Input
                  id="login-email"
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  placeholder={t("workEmailPlaceholder")}
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="ps-9"
                />
              </div>
            </div>
            {/* data-testid: e2e/fixtures.js's loginViaKeycloak clicks
                [data-testid="login-landing-button"] to reach Keycloak. */}
            <Button
              type="submit"
              size="lg"
              className="w-full"
              disabled={redirecting}
              data-testid="login-landing-button"
            >
              {redirecting ? "…" : t("continueButton")}
            </Button>
          </form>

          {/* 2026-09-17: was two hardcoded Google/Microsoft buttons that both
              just called startLogin(); now the realm-driven row the signup
              page uses (GitHub included, one idp hint per button). It renders
              its own "Or connect via" separator, so it sits under the form. */}
          <SocialLoginRow />
        </Card>

        <p className="vd-auth__switch">
          <BrandText text={t("newToVegaduta")} /> <Link href="/signup">{t("startFree")}</Link>
          {/* Second exit for the visitor who landed here by mistake (e.g.
              followed a console deep link) and has no account: /try is the
              real no-signup sandbox, and a login page that only offers
              "sign in or sign up" loses them entirely. */}{" "}
          <Link href="/try">{t("tryWithoutAccount")}</Link>
        </p>

        <p className="vd-auth__trust">
          <ShieldCheck className="size-3.5" aria-hidden="true" />
          {t("trustLine")}
        </p>
      </motion.div>
    </main>
  );
}
