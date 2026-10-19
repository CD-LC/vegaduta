"use client";

import type { ReactNode } from "react";
import { AlertTriangle } from "lucide-react";
import type { AddonHardware, AddonView } from "../../lib/addonsApi";
import { formatMb, isPlanned } from "../../lib/addonsModel";
import { ConfirmDialog } from "../ui/confirm-dialog";
import type { AddonText } from "./addonText";

// The confirmations. Turning something on says what it costs (disk, memory, a download) and what else
// comes with it; turning something off says what stops working and, for a model, that the files stay.
// The body sits inside the dialog's description, which is a <p>, so it is inline elements only.

export type PendingChange =
  | { kind: "enable"; addon: AddonView; requirements: AddonView[] }
  | { kind: "disable"; addon: AddonView }
  // A disable that other add-ons depend on: ask before taking them down too.
  | { kind: "cascade"; addon: AddonView; dependents: AddonView[] };

export interface AddonDialogsProps {
  pending: PendingChange | null;
  hardware: AddonHardware;
  bridgeConfigured: boolean;
  text: AddonText;
  /** Called with the change the dialog was showing, so a stale close cannot dismiss a newer dialog. */
  onCancel: (change: PendingChange) => void;
  onConfirm: (change: PendingChange) => Promise<void>;
}

const Line = ({ children, ...rest }: { children: ReactNode } & Record<string, unknown>) => (
  <span className="block" {...rest}>
    {children}
  </span>
);

function names(text: AddonText, list: AddonView[]): string {
  return list.map((a) => text.name(a)).join(", ");
}

function EnableBody({
  change,
  hardware,
  bridgeConfigured,
  text,
}: {
  change: Extract<PendingChange, { kind: "enable" }>;
  hardware: AddonHardware;
  bridgeConfigured: boolean;
  text: AddonText;
}) {
  const { t } = text;
  const { addon, requirements } = change;
  const model = addon.kind === "model-pull";
  const all = [addon, ...requirements];
  const sizeMb = all.reduce((n, a) => n + a.sizeMb, 0);
  const ramMb = all.reduce((n, a) => n + a.minRamMb, 0);
  const warn = all.some((a) => !isPlanned(a) && (a.fit === "low_memory" || a.fit === "needs_gpu"));
  return (
    <span className="block space-y-2">
      <Line>{t(model ? "confirm.enableCostModel" : "confirm.enableCost", { size: formatMb(sizeMb), ram: formatMb(ramMb) })}</Line>
      {requirements.length > 0 && <Line>{t("confirm.enableRequirements", { names: names(text, requirements) })}</Line>}
      <Line>{model ? t("confirm.enableModel") : t(bridgeConfigured ? "confirm.enableHostBridge" : "confirm.enableHostManual")}</Line>
      {warn && (
        <Line data-testid="confirm-fit">
          <span className="flex items-start gap-1.5 text-[var(--state-warning)]">
            <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            <span>{t("confirm.enableFit", { have: Math.round(hardware.ramGb * 10) / 10 })}</span>
          </span>
        </Line>
      )}
    </span>
  );
}

export default function AddonDialogs({ pending, hardware, bridgeConfigured, text, onCancel, onConfirm }: AddonDialogsProps) {
  const { t } = text;
  if (!pending) return null;
  const name = text.name(pending.addon);
  const model = pending.addon.kind === "model-pull";

  let title: string;
  let body: ReactNode;
  let confirmLabel: string;
  let destructive = false;

  if (pending.kind === "enable") {
    title = t("confirm.enableTitle", { name });
    confirmLabel = t("action.turnOn");
    body = <EnableBody change={pending} hardware={hardware} bridgeConfigured={bridgeConfigured} text={text} />;
  } else if (pending.kind === "disable") {
    title = t("confirm.disableTitle", { name });
    confirmLabel = t("action.turnOff");
    body = <Line>{model ? t("confirm.disableModel") : t(bridgeConfigured ? "confirm.disableHostBridge" : "confirm.disableHostManual")}</Line>;
  } else {
    title = t("confirm.cascadeTitle", { name });
    confirmLabel = t("confirm.cascadeConfirm");
    destructive = true;
    body = <Line>{t("confirm.cascadeBody", { name, dependents: names(text, pending.dependents) })}</Line>;
  }

  return (
    <ConfirmDialog
      key={`${pending.kind}:${pending.addon.id}`}
      open
      onOpenChange={(open) => {
        if (!open) onCancel(pending);
      }}
      title={title}
      description={body}
      confirmLabel={confirmLabel}
      cancelLabel={t("confirm.cancel")}
      destructive={destructive}
      onConfirm={() => onConfirm(pending)}
    />
  );
}
