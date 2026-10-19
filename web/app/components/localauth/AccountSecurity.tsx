"use client";

import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import Link from "next/link";
import { Fingerprint, KeyRound, LifeBuoy } from "lucide-react";
import { getKeycloak } from "../../lib/authClient";
import type { LocalAuthClient } from "../../lib/localAuthClient";
import { LocalAuthError } from "../../lib/localAuthClient";
import { useTranslations } from "../../lib/i18n";
import { PIN_MAX, PIN_MIN, pinProblem } from "../../lib/localAuthPin";
import {
  addPasskey,
  enrolDevice,
  fetchDeviceStatus,
  listMethods,
  regenerateRecoveryCodes,
  removeDevice,
  removePasskey,
} from "../../lib/localAuthUnlock";
import type { DeviceStatus, DeviceView, PasskeyView, UnlockMethods } from "../../lib/localAuthUnlock";
import { guessDeviceLabel, passkeySupport } from "../../lib/localAuthWebAuthn";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { describeLocalAuthError, describePinProblem } from "./describeError";
import PinField from "./PinField";
import RecoveryCodesPanel from "./RecoveryCodesPanel";

/** Where a sign-in done only to confirm an action returns to. */
export const SECURITY_PATH = "/login?security=1";

interface Props {
  /** Shown above the sections (for example after signing in with a recovery code). */
  notice?: string;
}

type Pending = { kind: "passkey" | "device"; id: string } | null;

/**
 * The signed-in person's sign-in methods: passkeys, PIN devices and recovery codes. Changes are
 * "fresh" actions: core answers 403 reauth_required when the last sign-in is too old, and the
 * page then offers to sign in again and come straight back here.
 */
export default function AccountSecurity({ notice }: Props) {
  const t = useTranslations("signin");
  const [methods, setMethods] = useState<UnlockMethods | null>(null);
  const [thisDevice, setThisDevice] = useState<DeviceStatus>({ known: false });
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [needsReauth, setNeedsReauth] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState<Pending>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [confirmCodes, setConfirmCodes] = useState(false);
  const [pinOpen, setPinOpen] = useState(false);
  const [pin, setPin] = useState("");
  const [pinConfirm, setPinConfirm] = useState("");
  const passkey = passkeySupport();

  /** A token that is still valid; restores the session first on a direct visit to this page. */
  const token = useCallback(async (): Promise<string> => {
    const client = getKeycloak() as LocalAuthClient | null;
    if (!client) throw new LocalAuthError(401, "invalid_session", "");
    if (!client.authenticated) await client.init();
    else await client.updateToken(30);
    if (!client.token) throw new LocalAuthError(401, "invalid_session", "");
    return client.token;
  }, []);

  const load = useCallback(async () => {
    try {
      const [loaded, device] = await Promise.all([token().then(listMethods), fetchDeviceStatus()]);
      setMethods(loaded);
      setThisDevice(device);
      setLoadError(null);
    } catch (err) {
      setLoadError(describeLocalAuthError(t, err));
    }
  }, [token, t]);

  useEffect(() => {
    void load();
  }, [load]);

  /** Runs one change; reauth_required becomes the confirm-it's-you prompt instead of an error line. */
  async function act(action: () => Promise<void>): Promise<boolean> {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
      return true;
    } catch (err) {
      if (err instanceof LocalAuthError && err.code === "reauth_required") setNeedsReauth(true);
      else setError(describeLocalAuthError(t, err));
      return false;
    } finally {
      setBusy(false);
    }
  }

  function reauth() {
    window.location.assign(`/login?reauth=1&next=${encodeURIComponent(SECURITY_PATH)}`);
  }

  async function onAddPasskey() {
    await act(async () => {
      await addPasskey(await token(), guessDeviceLabel(t("local.device.defaultLabel")));
    });
  }

  async function onRemove(target: NonNullable<Pending>) {
    setConfirming(null);
    await act(async () => {
      const jwt = await token();
      if (target.kind === "passkey") await removePasskey(jwt, target.id);
      else await removeDevice(jwt, target.id);
    });
  }

  async function onAddPin(e: FormEvent) {
    e.preventDefault();
    const problem = describePinProblem(t, pinProblem(pin));
    if (problem) return setError(problem);
    if (pin !== pinConfirm) return setError(t("local.pin.mismatch"));
    const ok = await act(async () => {
      await enrolDevice(await token(), pin, guessDeviceLabel(t("local.device.defaultLabel")));
    });
    if (ok) {
      setPinOpen(false);
      setPin("");
      setPinConfirm("");
    }
  }

  async function onRegenerate() {
    setConfirmCodes(false);
    await act(async () => {
      setCodes(await regenerateRecoveryCodes(await token()));
    });
  }

  if (loadError) {
    return (
      <div className="vd-auth__form" data-testid="local-security">
        <p role="alert" className="text-sm text-destructive" data-testid="local-login-error">{loadError}</p>
        <p className="vd-auth__switch"><Link href="/login">{t("local.backToSignIn")}</Link></p>
      </div>
    );
  }
  if (!methods) {
    return <p role="status" aria-busy="true" className="text-sm text-muted-foreground" data-testid="local-security-loading">{t("local.security.loading")}</p>;
  }

  if (codes) {
    return (
      <div data-testid="local-security">
        <RecoveryCodesPanel codes={codes} onContinue={() => setCodes(null)} continueLabel={t("local.security.codesDone")} />
      </div>
    );
  }

  const date = (iso?: string | null) => {
    const d = iso ? new Date(iso) : null;
    return d && !Number.isNaN(d.getTime()) ? d.toLocaleDateString(undefined, { dateStyle: "medium" }) : null;
  };
  const meta = (item: { createdAt?: string; lastUsedAt?: string | null }) =>
    [date(item.createdAt) && t("local.security.added", { date: date(item.createdAt) ?? "" }), date(item.lastUsedAt) && t("local.security.lastUsed", { date: date(item.lastUsedAt) ?? "" })]
      .filter(Boolean)
      .join(" · ");

  const row = (kind: "passkey" | "device", item: PasskeyView | DeviceView, extra?: React.ReactNode) => {
    const asking = confirming?.kind === kind && confirming.id === item.id;
    return (
      <li key={item.id} className="flex flex-wrap items-center justify-between gap-2 py-2" data-testid={`local-${kind}-${item.id}`}>
        <span className="flex min-w-0 flex-col">
          <span className="flex flex-wrap items-center gap-2 text-sm font-medium">
            {item.label}
            {extra}
          </span>
          <span className="text-xs text-muted-foreground">{meta(item)}</span>
        </span>
        {asking ? (
          <span className="flex gap-2">
            <Button type="button" size="sm" variant="destructive" disabled={busy} onClick={() => void onRemove({ kind, id: item.id })} data-testid={`local-${kind}-confirm-${item.id}`}>
              {t("local.security.confirmRemove")}
            </Button>
            <Button type="button" size="sm" variant="outline" onClick={() => setConfirming(null)}>
              {t("local.security.cancel")}
            </Button>
          </span>
        ) : (
          <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => setConfirming({ kind, id: item.id })} aria-label={t("local.security.removeNamed", { name: item.label })} data-testid={`local-${kind}-remove-${item.id}`}>
            {t("local.security.remove")}
          </Button>
        )}
      </li>
    );
  };

  return (
    <div className="vd-auth__form" data-testid="local-security">
      {notice && <p role="status" className="text-sm">{notice}</p>}
      {needsReauth && (
        <Alert variant="warning" data-testid="local-reauth-prompt">
          <AlertDescription>
            <p>{t("local.reauth.prompt")}</p>
            <Button type="button" size="sm" onClick={reauth} data-testid="local-reauth-button">{t("local.reauth.button")}</Button>
          </AlertDescription>
        </Alert>
      )}
      <div aria-live="polite">
        {error && <p role="alert" className="text-sm text-destructive" data-testid="local-login-error">{error}</p>}
      </div>

      <section aria-labelledby="sec-passkeys" className="flex flex-col gap-1">
        <h2 id="sec-passkeys" className="flex items-center gap-2 text-sm font-semibold"><Fingerprint className="size-4" aria-hidden="true" />{t("local.security.passkeys")}</h2>
        {methods.passkeys.length ? (
          <ul className="divide-y divide-border">
            {methods.passkeys.map((p) => row("passkey", p, p.backedUp ? <Badge variant="secondary">{t("local.security.synced")}</Badge> : null))}
          </ul>
        ) : (
          <p className="text-xs text-muted-foreground">{t("local.security.noPasskeys")}</p>
        )}
        <div>
          <Button type="button" size="sm" variant="outline" disabled={busy || !passkey.supported} onClick={() => void onAddPasskey()} data-testid="local-add-passkey">
            {t("local.security.addPasskey")}
          </Button>
          {!passkey.supported && <p className="mt-1 text-xs text-muted-foreground">{t(`local.passkey.unavailable.${passkey.reason}`)}</p>}
        </div>
      </section>

      <section aria-labelledby="sec-pin" className="flex flex-col gap-1">
        <h2 id="sec-pin" className="flex items-center gap-2 text-sm font-semibold"><KeyRound className="size-4" aria-hidden="true" />{t("local.security.pinDevices")}</h2>
        <p className="text-xs text-muted-foreground">{t("local.security.pinNote")}</p>
        {methods.devices.length > 0 && (
          <ul className="divide-y divide-border">
            {methods.devices.map((d) => row("device", d, thisDevice.known && thisDevice.id === d.id ? <Badge variant="accent">{t("local.security.thisBrowser")}</Badge> : null))}
          </ul>
        )}
        {pinOpen ? (
          <form onSubmit={(e) => void onAddPin(e)} className="vd-auth__form" data-testid="local-add-pin-form">
            <PinField id="local-pin-new" label={t("local.pin.newLabel", { min: PIN_MIN, max: PIN_MAX })} value={pin} onChange={setPin} disabled={busy} testId="local-pin-new" focusOnMount />
            <PinField id="local-pin-confirm" label={t("local.pin.confirmLabel")} value={pinConfirm} onChange={setPinConfirm} disabled={busy} testId="local-pin-confirm" />
            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={busy} data-testid="local-pin-submit">{t("local.security.savePin")}</Button>
              <Button type="button" size="sm" variant="outline" onClick={() => setPinOpen(false)}>{t("local.security.cancel")}</Button>
            </div>
          </form>
        ) : (
          <div>
            <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => setPinOpen(true)} data-testid="local-add-pin">{t("local.security.addPin")}</Button>
          </div>
        )}
      </section>

      <section aria-labelledby="sec-codes" className="flex flex-col gap-1">
        <h2 id="sec-codes" className="flex items-center gap-2 text-sm font-semibold"><LifeBuoy className="size-4" aria-hidden="true" />{t("local.security.recovery")}</h2>
        <p className="text-xs text-muted-foreground" data-testid="local-codes-remaining">{t("local.security.codesLeft", { count: methods.recoveryCodesRemaining })}</p>
        {confirmCodes ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs">{t("local.security.regenerateWarning")}</span>
            <Button type="button" size="sm" variant="destructive" disabled={busy} onClick={() => void onRegenerate()} data-testid="local-codes-regenerate-confirm">{t("local.security.regenerateConfirm")}</Button>
            <Button type="button" size="sm" variant="outline" onClick={() => setConfirmCodes(false)}>{t("local.security.cancel")}</Button>
          </div>
        ) : (
          <div>
            <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => setConfirmCodes(true)} data-testid="local-codes-regenerate">{t("local.security.regenerate")}</Button>
          </div>
        )}
      </section>

      <p className="vd-auth__switch flex flex-wrap justify-center gap-x-4 gap-y-1">
        {methods.hasPassword && <Link href="/login?change=1">{t("local.security.changePassword")}</Link>}
        <Link href="/">{t("local.security.backToConsole")}</Link>
      </p>
    </div>
  );
}
