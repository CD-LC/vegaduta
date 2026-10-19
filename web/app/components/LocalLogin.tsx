"use client";

import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import Link from "next/link";
import { motion } from "motion/react";
import { ShieldCheck } from "lucide-react";
import { getKeycloak } from "../lib/authClient";
import { LocalAuthError, localAuthPost, safeNext } from "../lib/localAuthClient";
import type { LocalAuthClient, LocalSessionPayload } from "../lib/localAuthClient";
import { fetchDeviceStatus, fetchLocalAuthConfig } from "../lib/localAuthUnlock";
import type { DeviceStatus, LocalAuthConfig, SetupResult } from "../lib/localAuthUnlock";
import { passkeySupport } from "../lib/localAuthWebAuthn";
import type { PasskeySupport } from "../lib/localAuthWebAuthn";
import { useTranslations } from "../lib/i18n";
import AuthBrandPanel from "./AuthBrandPanel";
import LanguageSwitcher from "./LanguageSwitcher";
import ThemePicker from "./ThemePicker";
import AccountSecurity from "./localauth/AccountSecurity";
import { describeLocalAuthError } from "./localauth/describeError";
import RecoveryCodesPanel from "./localauth/RecoveryCodesPanel";
import SetupChoose from "./localauth/SetupChoose";
import SignInPanel from "./localauth/SignInPanel";
import type { SignInVia } from "./localauth/SignInPanel";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { Input } from "./ui/input";
import { Label } from "./ui/label";

// The /login screen in local auth mode (NEXT_PUBLIC_AUTH_MODE=local; Keycloak mode keeps
// LoginLanding untouched - see app/login/page.tsx). One component, five steps:
//
//   signin    passkey, PIN pad, or password / recovery code (components/localauth/SignInPanel)
//   setup     /login?setup=<one-time token>   "Choose how you unlock" on first start
//   codes     the one-time recovery codes, shown after setup until the person confirms saving them
//   change    /login?change=1  or forced right after a temporary-password sign-in
//   security  /login?security=1   passkeys, PIN devices and recovery codes of the signed-in person
//
// /login?reauth=1 is the sign-in step reached from the security page when core wants a fresher
// sign-in (403 reauth_required): same methods, different heading, and it returns to ?next=.
//
// Credentials go to the same-origin proxy (lib/localAuthProxy.ts and lib/localAuthUnlockProxy.ts),
// which keeps core's refresh token and the device secret in HttpOnly cookies; this component only
// ever sees the short-lived access token, which it hands to the keycloak-js-shaped client
// (lib/localAuthClient.ts) so the rest of the console treats the session exactly like a Keycloak
// one. The setup token is removed from the address bar as soon as it is read, so it does not
// linger in history or a Referer header.
type Step = "signin" | "setup" | "codes" | "change" | "security";

const MIN_PASSWORD = 12;

const LAST_EMAIL_KEY = "vd_local_last_email";

export default function LocalLogin() {
  const t = useTranslations("signin");
  const [step, setStep] = useState<Step>("signin");
  const [setupToken, setSetupToken] = useState<string | null>(null);
  const [next, setNext] = useState("/");
  const [reauth, setReauth] = useState(false);
  const [config, setConfig] = useState<LocalAuthConfig | null>(null);
  const [device, setDevice] = useState<DeviceStatus>({ known: false });
  const [passkey, setPasskey] = useState<PasskeySupport>({ supported: false, reason: "no_webauthn" });
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [current, setCurrent] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [forced, setForced] = useState(false);
  const [codes, setCodes] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | undefined>(undefined);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get("setup");
    setNext(safeNext(params.get("next")));
    setReauth(params.get("reauth") === "1");
    if (token) {
      setSetupToken(token);
      setStep("setup");
      window.history.replaceState(null, "", "/login");
    } else if (params.get("change")) {
      setStep("change");
    } else if (params.get("security")) {
      setStep("security");
    }
    // A one-owner install: remember the address that last signed in on this browser so the next
    // visit is not a guessing game (nothing secret; the field stays editable).
    try {
      const saved = window.localStorage.getItem(LAST_EMAIL_KEY);
      if (saved) setEmail(saved);
    } catch {
      // storage can be blocked (private window); the field simply starts empty
    }
    setPasskey(passkeySupport());
    // What this install offers and whether this browser holds a device binding decide which
    // sign-in methods the first screen shows, so nothing is drawn before both are known.
    void Promise.all([fetchLocalAuthConfig(), fetchDeviceStatus()]).then(([cfg, dev]) => {
      setConfig(cfg);
      setDevice(dev);
    });
  }, []);

  function adopt(payload: LocalSessionPayload, typedEmail?: string) {
    (getKeycloak() as LocalAuthClient | null)?.adopt(payload);
    const known = payload.user?.email ?? typedEmail;
    if (known) {
      try {
        window.localStorage.setItem(LAST_EMAIL_KEY, known);
      } catch {
        // see above
      }
    }
  }

  function finish() {
    // A full navigation, so AuthProvider starts from the session the client now holds.
    window.location.assign(next);
  }

  function onSession(payload: LocalSessionPayload, info: { via: SignInVia; email?: string; password?: string }) {
    adopt(payload, info.email);
    if (payload.passwordChangeRequired) {
      setForced(true);
      setCurrent(info.password ?? "");
      setStep("change");
      return;
    }
    // Someone who signed in with a recovery code has lost their usual device: take them to the
    // place where a new passkey or PIN is added, unless they only came here to confirm something.
    if (info.via === "recovery" && !reauth) {
      setNotice(t("local.security.afterRecovery"));
      setStep("security");
      return;
    }
    finish();
  }

  function onSetupComplete(result: SetupResult) {
    adopt(result.session);
    if (result.recoveryCodes.length) {
      setCodes(result.recoveryCodes);
      setStep("codes");
    } else {
      finish();
    }
  }

  async function onChange(e: FormEvent) {
    e.preventDefault();
    if (password !== confirm) {
      setError(t("local.mismatch"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const client = getKeycloak() as LocalAuthClient | null;
      // A direct visit to /login?change=1 has no session in memory yet: restore it first.
      if (client && !client.authenticated) await client.init();
      if (!client?.token) throw new LocalAuthError(401, "invalid_session", "");
      const payload = await localAuthPost<LocalSessionPayload>(
        "change-password",
        { currentPassword: current, newPassword: password },
        { Authorization: `Bearer ${client.token}` }
      );
      adopt(payload);
      finish();
    } catch (err) {
      setError(describeLocalAuthError(t, err, "password"));
      setBusy(false);
    }
  }

  const methods = config?.credentialTypes ?? ["password"];
  const setupHasChoice = methods.includes("passkey") || methods.includes("pin");

  const title =
    step === "setup"
      ? setupHasChoice
        ? t("local.setup.chooseTitle")
        : t("local.setupTitle")
      : step === "codes"
        ? t("local.codes.title")
        : step === "change"
          ? t("local.changeTitle")
          : step === "security"
            ? t("local.security.title")
            : reauth
              ? t("local.reauth.title")
              : t("welcomeBack");
  const subtitle =
    step === "setup"
      ? setupHasChoice
        ? t("local.setup.chooseSubtitle")
        : t("local.setupSubtitle")
      : step === "codes"
        ? t("local.codes.subtitle")
        : step === "change"
          ? forced
            ? t("local.forcedChange")
            : t("local.changeSubtitle")
          : step === "security"
            ? t("local.security.subtitle")
            : reauth
              ? t("local.reauth.subtitle")
              : t("welcomeSubtitle");

  // Everything except the change form and the security page needs the install's config first.
  const ready = config !== null || step === "change" || step === "security";

  return (
    <main className="auth-main vd-auth text-foreground antialiased">
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
        <AuthBrandPanel title={title} subtitle={subtitle} />

        <Card className="auth-card vd-auth__card" aria-busy={!ready}>
          {!ready && (
            <p role="status" className="text-sm text-muted-foreground" data-testid="local-loading">
              {t("local.loading")}
            </p>
          )}

          {ready && step === "signin" && config && (
            <SignInPanel
              methods={methods}
              passkey={passkey}
              device={device}
              reauth={reauth}
              setupRequired={config.setupRequired}
              initialEmail={email}
              onSession={onSession}
              onDeviceChange={() => void fetchDeviceStatus().then(setDevice)}
            />
          )}

          {ready && step === "setup" && setupToken && (
            <SetupChoose token={setupToken} methods={methods} passkey={passkey} onComplete={onSetupComplete} />
          )}

          {step === "codes" && <RecoveryCodesPanel codes={codes} onContinue={finish} continueLabel={t("local.codes.continue")} />}

          {step === "security" && <AccountSecurity notice={notice} />}

          {step === "change" && (
            <form onSubmit={(e) => void onChange(e)} className="vd-auth__form" data-testid="local-change-form">
              <div className="vd-auth__field">
                <Label htmlFor="local-current">{t("local.currentPasswordLabel")}</Label>
                <Input
                  id="local-current"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={current}
                  onChange={(e) => setCurrent(e.target.value)}
                />
              </div>
              <div className="vd-auth__field">
                <Label htmlFor="local-new">{t("local.newPasswordLabel")}</Label>
                <Input
                  id="local-new"
                  type="password"
                  autoComplete="new-password"
                  required
                  minLength={MIN_PASSWORD}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  data-testid="local-new-password"
                />
                <p className="text-xs text-muted-foreground">{t("local.passwordHint", { min: MIN_PASSWORD })}</p>
              </div>
              <div className="vd-auth__field">
                <Label htmlFor="local-confirm">{t("local.confirmPasswordLabel")}</Label>
                <Input
                  id="local-confirm"
                  type="password"
                  autoComplete="new-password"
                  required
                  minLength={MIN_PASSWORD}
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  data-testid="local-confirm-password"
                />
              </div>
              <div aria-live="polite">
                {error && <p role="alert" className="text-sm text-destructive" data-testid="local-login-error">{error}</p>}
              </div>
              <Button type="submit" size="lg" className="w-full" disabled={busy} data-testid="local-password-submit">
                {busy ? t("local.signingIn") : t("local.changeButton")}
              </Button>
              {!forced && (
                <p className="vd-auth__switch flex flex-wrap justify-center gap-x-4 gap-y-1">
                  <Link href="/">{t("local.backToSignIn")}</Link>
                  <Link href="/login?security=1">{t("local.security.link")}</Link>
                </p>
              )}
            </form>
          )}
        </Card>

        <p className="vd-auth__trust">
          <ShieldCheck className="size-3.5" aria-hidden="true" />
          {t("trustLine")}
        </p>
      </motion.div>
    </main>
  );
}
