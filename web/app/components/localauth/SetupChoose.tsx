"use client";

import { useState } from "react";
import type { FormEvent } from "react";
import { Fingerprint, KeyRound, Lock } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { localAuthPost } from "../../lib/localAuthClient";
import type { LocalSessionPayload } from "../../lib/localAuthClient";
import { PIN_MAX, PIN_MIN, pinProblem } from "../../lib/localAuthPin";
import { setupWithPasskey, setupWithPin } from "../../lib/localAuthUnlock";
import type { SetupResult } from "../../lib/localAuthUnlock";
import { guessDeviceLabel } from "../../lib/localAuthWebAuthn";
import type { PasskeySupport } from "../../lib/localAuthWebAuthn";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { describeLocalAuthError, describePinProblem } from "./describeError";
import PinField from "./PinField";

const MIN_PASSWORD = 12;

type View = "choose" | "pin" | "password";

interface Props {
  /** The one-time setup token from the link. */
  token: string;
  /** Server-registered verifier types (credentialTypes from /config). */
  methods: string[];
  passkey: PasskeySupport;
  onComplete: (result: SetupResult) => void;
}

/**
 * "Choose how you unlock": the first thing a new install asks. Passkey is recommended, a PIN is
 * the quick unlock for this browser, a password stays available. Only the methods this install's
 * core actually offers are listed; if that is just the password, the choice is skipped.
 */
export default function SetupChoose({ token, methods, passkey, onComplete }: Props) {
  const t = useTranslations("signin");
  const offersPasskey = methods.includes("passkey");
  const offersPin = methods.includes("pin");
  const [view, setView] = useState<View>(offersPasskey || offersPin ? "choose" : "password");
  const [pin, setPin] = useState("");
  const [pinConfirm, setPinConfirm] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(action: () => Promise<SetupResult>) {
    setBusy(true);
    setError(null);
    try {
      onComplete(await action());
    } catch (err) {
      setError(describeLocalAuthError(t, err));
      setBusy(false);
    }
  }

  function back() {
    setError(null);
    setView("choose");
  }

  function onPin(e: FormEvent) {
    e.preventDefault();
    const problem = describePinProblem(t, pinProblem(pin));
    if (problem) return setError(problem);
    if (pin !== pinConfirm) return setError(t("local.pin.mismatch"));
    void run(() => setupWithPin(token, pin, guessDeviceLabel(t("local.device.defaultLabel"))));
  }

  function onPassword(e: FormEvent) {
    e.preventDefault();
    if (password !== confirm) return setError(t("local.mismatch"));
    void run(async () => {
      const session = await localAuthPost<LocalSessionPayload>("setup", { token, password });
      return { session, recoveryCodes: session.recoveryCodes ?? [] };
    });
  }

  const errorLine = error && (
    <p role="alert" className="text-sm text-destructive" data-testid="local-login-error">
      {error}
    </p>
  );

  if (view === "choose") {
    return (
      <div className="vd-auth__form" data-testid="local-setup-choose">
        <div role="group" aria-label={t("local.setup.chooseTitle")} className="flex flex-col gap-2">
          {offersPasskey && (
            <OptionButton
              icon={<Fingerprint className="size-5" aria-hidden="true" />}
              title={t("local.setup.passkeyTitle")}
              description={passkey.supported ? t("local.setup.passkeyDesc") : t(`local.passkey.unavailable.${passkey.reason}`)}
              badge={passkey.supported ? t("local.setup.recommended") : undefined}
              disabled={busy || !passkey.supported}
              onClick={() => void run(() => setupWithPasskey(token, guessDeviceLabel(t("local.device.defaultLabel"))))}
              testId="local-setup-passkey"
            />
          )}
          {offersPin && (
            <OptionButton
              icon={<KeyRound className="size-5" aria-hidden="true" />}
              title={t("local.setup.pinTitle")}
              description={t("local.setup.pinDesc")}
              disabled={busy}
              onClick={() => setView("pin")}
              testId="local-setup-pin"
            />
          )}
          <OptionButton
            icon={<Lock className="size-5" aria-hidden="true" />}
            title={t("local.setup.passwordTitle")}
            description={t("local.setup.passwordDesc", { min: MIN_PASSWORD })}
            disabled={busy}
            onClick={() => setView("password")}
            testId="local-setup-password"
          />
        </div>
        <div aria-live="polite">{errorLine}</div>
        {busy && <p role="status" className="text-sm text-muted-foreground">{t("local.setup.working")}</p>}
      </div>
    );
  }

  if (view === "pin") {
    return (
      <form onSubmit={onPin} className="vd-auth__form" data-testid="local-setup-pin-form">
        <p className="text-sm text-muted-foreground">{t("local.setup.pinNote")}</p>
        <PinField id="local-pin-new" label={t("local.pin.newLabel", { min: PIN_MIN, max: PIN_MAX })} value={pin} onChange={setPin} disabled={busy} testId="local-pin-new" focusOnMount />
        <PinField id="local-pin-confirm" label={t("local.pin.confirmLabel")} value={pinConfirm} onChange={setPinConfirm} disabled={busy} testId="local-pin-confirm" />
        <div aria-live="polite">{errorLine}</div>
        <Button type="submit" size="lg" className="w-full" disabled={busy} data-testid="local-pin-submit">
          {busy ? t("local.signingIn") : t("local.setup.pinButton")}
        </Button>
        <p className="vd-auth__switch">
          <button type="button" className="underline" onClick={back} data-testid="local-setup-back">
            {t("local.setup.otherWay")}
          </button>
        </p>
      </form>
    );
  }

  return (
    <form onSubmit={onPassword} className="vd-auth__form" data-testid="local-setup-form">
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
      <div aria-live="polite">{errorLine}</div>
      <Button type="submit" size="lg" className="w-full" disabled={busy} data-testid="local-password-submit">
        {busy ? t("local.signingIn") : t("local.setPasswordButton")}
      </Button>
      {(offersPasskey || offersPin) && (
        <p className="vd-auth__switch">
          <button type="button" className="underline" onClick={back} data-testid="local-setup-back">
            {t("local.setup.otherWay")}
          </button>
        </p>
      )}
    </form>
  );
}

function OptionButton({
  icon,
  title,
  description,
  badge,
  disabled,
  onClick,
  testId,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
  badge?: string;
  disabled?: boolean;
  onClick: () => void;
  testId: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      data-testid={testId}
      className="flex w-full items-start gap-3 rounded-lg border border-border bg-transparent p-3 text-left outline-none transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60"
    >
      <span className="mt-0.5 text-muted-foreground">{icon}</span>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="flex flex-wrap items-center gap-2 text-sm font-medium">
          {title}
          {badge && <Badge variant="accent">{badge}</Badge>}
        </span>
        <span className="text-xs text-muted-foreground">{description}</span>
      </span>
    </button>
  );
}
