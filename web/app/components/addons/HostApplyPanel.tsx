"use client";

import { useEffect, useRef, useState } from "react";
import { Check, Copy, Monitor } from "lucide-react";
import type { AddonApply, AddonView } from "../../lib/addonsApi";
import { envLines, isWindowsClient, windowsApply } from "../../lib/addonsModel";
import { toast } from "../../lib/toastBus";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import type { AddonText } from "./addonText";

// Compose add-ons are started by the host, not by core (core runs in a container and cannot start
// containers). This panel says plainly which saved changes are still waiting for the host and gives the
// exact lines to apply them by hand. It never claims the command line can switch an add-on off: the
// launcher only adds, so removals are done by VegaDuta Setup.

function CopyBlock({ label, value, text }: { label: string; value: string; text: AddonText }) {
  const { t } = text;
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    []
  );

  function copy() {
    if (!navigator?.clipboard?.writeText) {
      toast.error(t("host.copyFailed"));
      return;
    }
    navigator.clipboard.writeText(value).then(
      () => {
        setCopied(true);
        if (timer.current) clearTimeout(timer.current);
        timer.current = setTimeout(() => setCopied(false), 1500);
      },
      () => toast.error(t("host.copyFailed"))
    );
  }

  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-foreground">{label}</p>
      <div className="flex items-start gap-2">
        <pre
          // A long command scrolls sideways; focus lets a keyboard user reach the rest of it.
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
          tabIndex={0}
          className="min-w-0 flex-1 overflow-x-auto whitespace-pre rounded-md border border-control bg-secondary/40 px-3 py-2 font-mono text-xs text-foreground"
          aria-label={label}
        >
          {value}
        </pre>
        <Button type="button" size="sm" variant="outline" onClick={copy}>
          {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
          {copied ? t("host.copied") : t("host.copy")} <span className="sr-only">{label}</span>
        </Button>
      </div>
    </div>
  );
}

export interface HostApplyPanelProps {
  apply: AddonApply;
  bridgeConfigured: boolean;
  /** Host-managed add-ons in enable_requested, applying or disable_requested. */
  pending: AddonView[];
  text: AddonText;
  /** Test seam; defaults to the browser's user agent. */
  userAgent?: string;
}

export default function HostApplyPanel({ apply, bridgeConfigured, pending, text, userAgent }: HostApplyPanelProps) {
  const { t } = text;
  const [ua, setUa] = useState(userAgent);
  useEffect(() => {
    if (userAgent === undefined) setUa(navigator.userAgent);
  }, [userAgent]);

  if (pending.length === 0) return null;

  const turningOn = pending.filter((a) => a.status !== "disable_requested");
  const turningOff = pending.filter((a) => a.status === "disable_requested");
  const win = isWindowsClient(ua);
  const winApply = windowsApply(apply);
  const env = envLines(apply.envSet);
  const list = (items: AddonView[]) => items.map((a) => text.name(a)).join(", ");

  return (
    <Card className="gap-4 p-5" data-testid="addon-host-panel" aria-labelledby="addon-host-title">
      <div className="flex items-start gap-3">
        <span className="cat-chip cat-chip-sm mt-0.5" aria-hidden="true">
          <Monitor className="size-3.5" />
        </span>
        <div className="min-w-0 space-y-1">
          <h2 id="addon-host-title" className="text-base font-semibold tracking-tight text-foreground">
            {t("host.title")}
          </h2>
          <p className="text-sm text-muted-foreground">
            {t(bridgeConfigured ? "host.introBridge" : "host.introManual")}
          </p>
        </div>
      </div>

      <ul className="space-y-1 text-sm text-foreground" aria-label={t("host.pendingLabel")}>
        {turningOn.length > 0 && <li>{t("host.toTurnOn", { names: list(turningOn) })}</li>}
        {turningOff.length > 0 && <li>{t("host.toTurnOff", { names: list(turningOff) })}</li>}
      </ul>

      {turningOn.length > 0 && (
        <div className="space-y-3">
          {win ? (
            <>
              <CopyBlock label={t("host.commandWindows")} value={winApply.command} text={text} />
              {winApply.unsupportedFlags.length > 0 && (
                <p className="text-xs text-[var(--state-warning)]" data-testid="addon-host-ps-note">
                  {t("host.noPowerShellFlag", { flags: winApply.unsupportedFlags.join(" ") })}
                </p>
              )}
            </>
          ) : (
            <CopyBlock label={t("host.command")} value={apply.command} text={text} />
          )}
          {env.length > 0 && <CopyBlock label={t("host.envLines")} value={env.join("\n")} text={text} />}
          <p className="text-xs text-muted-foreground">{t("host.runFrom")}</p>
        </div>
      )}

      {turningOff.length > 0 && (
        <Alert variant="info" role="note">
          <AlertTitle>{t("host.offTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("host.offBody")}</p>
            {apply.envUnset.length > 0 && <p>{t("host.envUnset", { keys: apply.envUnset.join(", ") })}</p>}
          </AlertDescription>
        </Alert>
      )}

      {!bridgeConfigured && (
        <p className="text-xs text-muted-foreground" data-testid="addon-host-no-bridge">
          {t("host.noBridge")}
        </p>
      )}
    </Card>
  );
}
