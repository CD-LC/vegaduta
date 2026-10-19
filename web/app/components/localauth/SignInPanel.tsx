"use client";

import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Fingerprint } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { localAuthPost } from "../../lib/localAuthClient";
import type { LocalSessionPayload } from "../../lib/localAuthClient";
import { pinLogin, passkeyLogin, recoveryLogin } from "../../lib/localAuthUnlock";
import type { DeviceStatus } from "../../lib/localAuthUnlock";
import type { PasskeySupport } from "../../lib/localAuthWebAuthn";
import { PIN_MIN } from "../../lib/localAuthPin";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { describeLocalAuthError } from "./describeError";
import type { ErrorContext } from "./describeError";
import PinField from "./PinField";
import { useLockout } from "./useLockout";

export type SignInVia = "password" | "pin" | "passkey" | "recovery";

interface Props {
  /** Server-registered verifier types (credentialTypes from /config). */
  methods: string[];
  passkey: PasskeySupport;
  device: DeviceStatus;
  /** Signing in again to confirm an account change (?reauth=1). */
  reauth: boolean;
  setupRequired: boolean;
  initialEmail: string;
  onSession: (payload: LocalSessionPayload, info: { via: SignInVia; email?: string; password?: string }) => void;
  /** The device binding changed under us (revoked): re-read it. */
  onDeviceChange: () => void;
}

type View = "main" | "password" | "recovery";

/**
 * The sign-in methods of a personal install, in the order a person would reach for them: a
 * passkey button, the PIN pad when this browser is enrolled, then "use a password" (which is where
 * the e-mail field lives) and "I lost my device" (a recovery code). When neither a passkey nor a
 * PIN is possible here (an older core, an unsupported browser, no enrolled device) the password
 * form is simply the screen.
 */
export default function SignInPanel({ methods, passkey, device, reauth, setupRequired, initialEmail, onSession, onDeviceChange }: Props) {
  const t = useTranslations("signin");
  const canPasskey = methods.includes("passkey") && passkey.supported;
  const canPin = methods.includes("pin") && device.known;
  const canRecovery = methods.includes("recovery");
  const hasAlternative = canPasskey || canPin;

  const [view, setView] = useState<View>("main");
  const shown: View = view === "main" && !hasAlternative ? "password" : view;
  const [email, setEmail] = useState(initialEmail);
  const [password, setPassword] = useState("");
  const [pin, setPin] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  // Which method the request in flight belongs to, so only its button says "Signing in".
  const [active, setActive] = useState<ErrorContext | null>(null);
  const [error, setError] = useState<string | null>(null);
  const lockout = useLockout();
  const locked = lockout.remaining > 0;
  // True while the error on screen is the throttle message, so it can go when the countdown ends.
  const lockedMessage = useRef(false);

  // A saved address arrives after the first render (it is read from storage in an effect).
  useEffect(() => {
    if (initialEmail) setEmail((current) => current || initialEmail);
  }, [initialEmail]);

  // The countdown ran out: the throttle message is stale.
  useEffect(() => {
    if (!locked && lockedMessage.current) {
      lockedMessage.current = false;
      setError(null);
    }
  }, [locked]);

  function go(next: View) {
    setError(null);
    setView(next);
  }

  async function attempt(context: ErrorContext, action: () => Promise<{ payload: LocalSessionPayload; via: SignInVia; email?: string; password?: string }>) {
    setBusy(true);
    setActive(context);
    setError(null);
    lockedMessage.current = false;
    try {
      const { payload, ...info } = await action();
      onSession(payload, info);
    } catch (err) {
      const failure = err as { code?: string; retryAfterSeconds?: number };
      if (failure.code === "too_many_attempts") {
        lockout.start(failure.retryAfterSeconds ?? 60);
        lockedMessage.current = true;
      } else if (failure.code === "device_revoked" || failure.code === "device_unknown") {
        onDeviceChange();
      }
      if (context === "pin") setPin("");
      setError(describeLocalAuthError(t, err, context));
      setBusy(false);
    }
  }

  function onPassword(e: FormEvent) {
    e.preventDefault();
    void attempt("password", async () => ({
      payload: await localAuthPost<LocalSessionPayload>("login", { identifier: email.trim(), password }),
      via: "password",
      email: email.trim(),
      password,
    }));
  }

  function onPin(e: FormEvent) {
    e.preventDefault();
    void attempt("pin", async () => ({ payload: await pinLogin(pin), via: "pin" }));
  }

  function onPasskey() {
    void attempt("passkey", async () => ({ payload: await passkeyLogin(), via: "passkey" }));
  }

  function onRecovery(e: FormEvent) {
    e.preventDefault();
    void attempt("recovery", async () => ({ payload: await recoveryLogin(code), via: "recovery" }));
  }

  const status = (
    <div aria-live="polite">
      {error && (
        <p role="alert" className="text-sm text-destructive" data-testid="local-login-error">
          {error}
        </p>
      )}
      {locked && (
        <p aria-hidden="true" className="text-xs text-muted-foreground" data-testid="local-lockout-countdown">
          {t("local.lockout.countdown", { seconds: lockout.remaining })}
        </p>
      )}
    </div>
  );

  const notices = (
    <>
      {reauth && (
        <p role="status" className="vd-auth__switch" style={{ marginTop: 0 }} data-testid="local-reauth-note">
          {t("local.reauth.note")}
        </p>
      )}
      {setupRequired && (
        <p role="status" className="vd-auth__switch" style={{ marginTop: 0 }}>
          {t("local.setupNeeded")}
        </p>
      )}
    </>
  );

  const passkeyHint =
    methods.includes("passkey") && !passkey.supported ? (
      <p className="text-xs text-muted-foreground" data-testid="local-passkey-unavailable">
        {t(`local.passkey.unavailable.${passkey.reason}`)}
      </p>
    ) : null;

  const recoveryLink = canRecovery ? (
    <button type="button" className="underline" onClick={() => go("recovery")} data-testid="local-lost-device">
      {t("local.signin.lostDevice")}
    </button>
  ) : null;

  if (shown === "recovery") {
    return (
      <form onSubmit={onRecovery} className="vd-auth__form" data-testid="local-recovery-form">
        {notices}
        <p className="text-sm text-muted-foreground">{t("local.recovery.intro")}</p>
        <div className="vd-auth__field">
          <Label htmlFor="local-recovery-code">{t("local.recovery.label")}</Label>
          <Input
            id="local-recovery-code"
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            required
            placeholder="XXXX-XXXX-XXXX-XXXX"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            data-testid="local-recovery-code"
          />
        </div>
        {status}
        <Button type="submit" size="lg" className="w-full" disabled={busy || locked} data-testid="local-recovery-submit">
          {busy ? t("local.signingIn") : t("local.recovery.button")}
        </Button>
        <p className="vd-auth__switch">
          <button type="button" className="underline" onClick={() => go("main")} data-testid="local-back">
            {t("local.signin.back")}
          </button>
        </p>
      </form>
    );
  }

  if (shown === "password") {
    return (
      <form onSubmit={onPassword} className="vd-auth__form" data-testid="local-signin-form">
        {notices}
        <div className="vd-auth__field">
          <Label htmlFor="local-email">{t("workEmailLabel")}</Label>
          <Input
            id="local-email"
            type="email"
            inputMode="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            data-testid="local-login-email"
          />
        </div>
        <div className="vd-auth__field">
          <Label htmlFor="local-password">{t("passwordLabel")}</Label>
          <Input
            id="local-password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            data-testid="local-login-password"
          />
        </div>
        {status}
        <Button type="submit" size="lg" className="w-full" disabled={busy || locked} data-testid="local-login-submit">
          {busy ? t("local.signingIn") : t("local.signInButton")}
        </Button>
        {passkeyHint}
        {(hasAlternative || recoveryLink) && (
          <p className="vd-auth__switch flex flex-wrap justify-center gap-x-4 gap-y-1">
            {hasAlternative && (
              <button type="button" className="underline" onClick={() => go("main")} data-testid="local-back">
                {t("local.signin.back")}
              </button>
            )}
            {recoveryLink}
          </p>
        )}
      </form>
    );
  }

  return (
    <div className="vd-auth__form" data-testid="local-signin-main">
      {notices}
      {canPasskey && (
        <Button type="button" size="lg" className="w-full" disabled={busy || locked} onClick={onPasskey} data-testid="local-passkey-signin">
          <Fingerprint aria-hidden="true" />
          {busy && active === "passkey" ? t("local.signingIn") : t("local.signin.passkeyButton")}
        </Button>
      )}
      {canPasskey && canPin && (
        <p className="vd-auth__switch" style={{ marginTop: 0 }} aria-hidden="true">
          {t("or")}
        </p>
      )}
      {canPin && (
        <form onSubmit={onPin} className="vd-auth__form" data-testid="local-pin-form">
          <PinField
            id="local-pin"
            label={device.label ? t("local.signin.pinLabelDevice", { device: device.label }) : t("local.signin.pinLabel")}
            value={pin}
            onChange={setPin}
            disabled={busy || locked}
            keypad
            focusOnMount={!canPasskey}
            testId="local-pin"
          />
          {status}
          <Button type="submit" size="lg" className="w-full" disabled={busy || locked || pin.length < PIN_MIN} data-testid="local-pin-submit">
            {busy && active === "pin" ? t("local.signingIn") : t("local.signin.pinButton")}
          </Button>
        </form>
      )}
      {!canPin && status}
      {passkeyHint}
      <p className="vd-auth__switch flex flex-wrap justify-center gap-x-4 gap-y-1">
        <button type="button" className="underline" onClick={() => go("password")} data-testid="local-use-password">
          {t("local.signin.usePassword")}
        </button>
        {recoveryLink}
      </p>
    </div>
  );
}
