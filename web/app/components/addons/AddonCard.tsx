"use client";

import Link from "next/link";
import {
  AlertTriangle,
  CheckCircle2,
  CircleDashed,
  Clock,
  Loader2,
  PauseCircle,
  XCircle,
  type LucideIcon,
} from "lucide-react";
import type { AddonHardware, AddonStatus, AddonView } from "../../lib/addonsApi";
import { formatMb, isPlanned, learnMoreHref, statusTone, type StatusTone } from "../../lib/addonsModel";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import type { AddonText } from "./addonText";

// One add-on: what it is, what it costs this computer, and an honest line about what is true right now
// (wanted, running, waiting for the host, failed). The button asks for a change; the status badge reports
// what core and the installer have seen. They are deliberately separate.

const TONE_STATE: Record<StatusTone, string> = {
  success: "var(--state-success)",
  warning: "var(--state-warning)",
  danger: "var(--state-error)",
  progress: "var(--state-running)",
  neutral: "var(--state-idle)",
};

const STATUS_ICON: Record<AddonStatus, LucideIcon> = {
  off: CircleDashed,
  enable_requested: Clock,
  applying: Loader2,
  on: CheckCircle2,
  disable_requested: PauseCircle,
  pulling: Loader2,
  failed: XCircle,
  unavailable: Clock,
};

export interface AddonCardProps {
  addon: AddonView;
  byId: ReadonlyMap<string, AddonView>;
  text: AddonText;
  hardware: AddonHardware;
  bridgeConfigured: boolean;
  busy: boolean;
  /** False when the caller may read the catalog but not switch it; the buttons then stay off
   *  instead of letting a click hit the role check's 403. */
  mayChange: boolean;
  onEnable: (addon: AddonView) => void;
  onDisable: (addon: AddonView) => void;
}

function StatusBadge({ addon, text }: { addon: AddonView; text: AddonText }) {
  const Icon = STATUS_ICON[addon.status] ?? CircleDashed;
  const spin = addon.status === "applying" || addon.status === "pulling";
  return (
    <Badge variant="state" state={TONE_STATE[statusTone(addon.status)]} data-testid="addon-status">
      <Icon className={spin ? "animate-spin" : undefined} aria-hidden="true" />
      {text.status(addon.status)}
    </Badge>
  );
}

function gb(mb: number): string {
  return formatMb(mb);
}

function Facts({ addon, text }: { addon: AddonView; text: AddonText }) {
  const { t } = text;
  const model = addon.kind === "model-pull";
  return (
    <ul className="flex flex-wrap gap-1.5" aria-label={t("facts")}>
      {addon.sizeMb > 0 && (
        <li>
          <Badge variant="outline">{t("fact.size", { size: gb(addon.sizeMb) })}</Badge>
        </li>
      )}
      {addon.minRamMb > 0 && (
        <li>
          <Badge variant="outline">{t(model ? "fact.memoryModel" : "fact.memory", { ram: gb(addon.minRamMb) })}</Badge>
        </li>
      )}
      <li>
        <Badge variant={addon.needsGpu ? "secondary" : "outline"}>
          {t(addon.needsGpu ? "fact.gpuNeeded" : "fact.noGpu")}
        </Badge>
      </li>
      {addon.license !== "" && (
        <li>
          <Badge variant="outline">{t("fact.license", { license: addon.license })}</Badge>
        </li>
      )}
      <li>
        <Badge variant={addon.free ? "accent" : "secondary"}>{t(addon.free ? "fact.free" : "fact.paid")}</Badge>
      </li>
    </ul>
  );
}

function Relations({ addon, byId, text }: { addon: AddonView; byId: ReadonlyMap<string, AddonView>; text: AddonText }) {
  const { t } = text;
  const names = (ids: string[]) => ids.map((id) => text.nameOf(id, byId)).join(", ");
  const alsoOn = addon.requires.filter((id) => byId.get(id)?.desired === false);
  return (
    <div className="space-y-1 text-xs text-muted-foreground">
      {addon.requires.length > 0 && <p>{t("needs", { names: names(addon.requires) })}</p>}
      {!addon.desired && alsoOn.length > 0 && <p>{t("alsoTurnsOn", { names: names(alsoOn) })}</p>}
      {addon.requiredBy.length > 0 && <p>{t("neededBy", { names: names(addon.requiredBy) })}</p>}
      {addon.unlocks.length > 0 && <p>{t("unlocks", { list: addon.unlocks.map((u) => text.unlock(u)).join(", ") })}</p>}
    </div>
  );
}

function fitWarning(addon: AddonView, hardware: AddonHardware, text: AddonText): string | null {
  const { t } = text;
  if (isPlanned(addon)) return null;
  if (addon.fit === "low_memory") {
    return t("fit.low_memory", { have: Math.round(hardware.ramGb * 10) / 10, need: gb(addon.minRamMb) });
  }
  if (addon.fit === "needs_gpu") return t("fit.needs_gpu");
  return null;
}

/** The one sentence that says what is true now, for the states where the badge alone is not enough. */
function statusDetail(addon: AddonView, bridgeConfigured: boolean, text: AddonText): string | null {
  const { t } = text;
  const model = addon.kind === "model-pull";
  switch (addon.status) {
    case "enable_requested":
      return t(bridgeConfigured ? "detail.enableRequestedBridge" : "detail.enableRequestedManual");
    case "applying":
      return t("detail.applying");
    case "disable_requested":
      return t(bridgeConfigured ? "detail.disableRequestedBridge" : "detail.disableRequestedManual");
    case "on":
      return model ? t("detail.onModel") : null;
    case "off":
      return model && addon.installed ? t("detail.offModelKept") : null;
    default:
      return null;
  }
}

export default function AddonCard({
  addon,
  byId,
  text,
  hardware,
  bridgeConfigured,
  busy,
  mayChange,
  onEnable,
  onDisable,
}: AddonCardProps) {
  const { t } = text;
  const name = text.name(addon);
  const planned = isPlanned(addon);
  const learn = learnMoreHref(addon.learnMore);
  const warning = fitWarning(addon, hardware, text);
  const detail = statusDetail(addon, bridgeConfigured, text);
  const pulling = addon.status === "pulling";
  const failed = addon.status === "failed";
  const ollamaBlocked = !addon.canEnable && addon.blockedReason === "ollama_unreachable";
  const tierBlocked = !addon.canEnable && addon.blockedReason === "not_on_tier";
  // The wish decides the button; the badge reports reality. A model that is downloading cannot be cancelled here.
  const wanted = addon.desired;

  return (
    <Card
      className="h-full gap-3 p-5"
      data-testid={`addon-${addon.id}`}
      data-status={addon.status}
      aria-labelledby={`addon-${addon.id}-name`}
      role="group"
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 id={`addon-${addon.id}-name`} className="min-w-0 text-sm font-semibold text-foreground">
          {name}
        </h3>
        {planned ? <Badge variant="secondary">{t("comingSoon")}</Badge> : <StatusBadge addon={addon} text={text} />}
      </div>

      <p className="text-sm text-muted-foreground">{text.description(addon)}</p>

      {!planned && <Facts addon={addon} text={text} />}
      <Relations addon={addon} byId={byId} text={text} />

      {warning && (
        <p className="flex items-start gap-1.5 text-xs text-[var(--state-warning)]" data-testid="addon-fit">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
          <span>{warning}</span>
        </p>
      )}

      {pulling && (
        <div className="space-y-1">
          <div
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={addon.progress ?? 0}
            aria-label={t("progressLabel", { name })}
            className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
          >
            <div
              className="h-full rounded-full bg-primary transition-[width]"
              style={{ width: `${addon.progress ?? 0}%` }}
            />
          </div>
          <p className="text-xs text-muted-foreground">
            {t("downloading", { percent: addon.progress ?? 0 })}
            {addon.message ? ` ${addon.message}` : ""}
          </p>
        </div>
      )}

      {detail && <p className="text-xs text-muted-foreground">{detail}</p>}

      {failed && (
        <p className="text-xs text-[var(--state-error)]" data-testid="addon-failure">
          {addon.message ? t("failedWith", { message: addon.message }) : t("failed")}
        </p>
      )}

      {!failed && !pulling && addon.message && addon.status !== "off" && addon.status !== "on" && (
        <p className="text-xs text-muted-foreground">{addon.message}</p>
      )}

      {ollamaBlocked && (
        <p className="text-xs text-muted-foreground" data-testid="addon-ollama-blocked">
          {t("blocked.ollama_unreachable")}{" "}
          <Link href="/providers/local-model" className="underline underline-offset-2">
            {t("blocked.setupLink")}
          </Link>
        </p>
      )}

      {tierBlocked && (
        <p className="text-xs text-muted-foreground" data-testid="addon-tier-blocked">
          {t("blocked.not_on_tier")}
        </p>
      )}

      <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
        {!planned && wanted && (
          <>
            {failed && (
              <Button size="sm" disabled={busy || !addon.canEnable || !mayChange} onClick={() => onEnable(addon)}>
                {t("action.retry")} <span className="sr-only">{name}</span>
              </Button>
            )}
            <Button
              size="sm"
              variant="outline"
              disabled={busy || pulling || !mayChange}
              onClick={() => onDisable(addon)}
            >
              {t("action.turnOff")} <span className="sr-only">{name}</span>
            </Button>
          </>
        )}
        {!planned && !wanted && (
          <Button size="sm" disabled={busy || !addon.canEnable || !mayChange} onClick={() => onEnable(addon)}>
            {t("action.turnOn")} <span className="sr-only">{name}</span>
          </Button>
        )}
        {busy && <Loader2 className="size-4 animate-spin text-muted-foreground" aria-hidden="true" />}
        {learn && (
          <a
            href={learn}
            target="_blank"
            rel="noopener noreferrer"
            className="ml-auto text-xs text-muted-foreground underline underline-offset-2 hover:text-foreground"
          >
            {t("learnMore")} <span className="sr-only">{name}</span>
          </a>
        )}
      </div>
    </Card>
  );
}
