"use client";

import { useId, useState } from "react";
import { ShieldAlert } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../../../components/ui/alert";
import { ConfirmDialog } from "../../../components/ui/confirm-dialog";
import { Switch } from "../../../components/ui/switch";
import { Badge } from "../../../components/ui/badge";
import { useTranslations } from "../../../lib/i18n";
import { useTrajectoryConfig } from "../useTrajectoryConfig";
import { normalizeMode, TEXT_ONLY, WORKSPACE_DEFAULT, type DatasetModeValue, type EvalMode } from "./datasetMode";

/**
 * How a dataset replays its cases: with the workspace default (the full pipeline unless an admin changed it), always
 * through the full pipeline with the agent's tools in the eval sandbox, or always quick and text only; and, when
 * tools run, whether the eval sandbox approves tool calls that would wait for a person. Controlled: the parent owns
 * the value and decides when to save. Enabling auto-approve asks for confirmation and states the consequence; when
 * the server refused it (the workspace is not declared non-production) the switch stays off, disabled, with the
 * server's sentence.
 */
export default function DatasetModeControls({
  agentId,
  value,
  onChange,
  disabled = false,
  refusal = null,
  workspaceDefault = null,
}: {
  agentId: string | null;
  value: DatasetModeValue;
  onChange: (next: DatasetModeValue) => void;
  disabled?: boolean;
  /** The server's sentence when it refused auto-approval for this workspace; null when it has not. */
  refusal?: string | null;
  /** The mode runs take when the dataset pins none, when known (the workspace setting over the platform's). */
  workspaceDefault?: EvalMode | null;
}) {
  const t = useTranslations("evalControls");
  const uid = useId();
  const trajectory = useTrajectoryConfig(agentId);
  const [confirming, setConfirming] = useState(false);
  const mode = normalizeMode(value);
  const pin = mode.toolExecutionEnabled;
  // Tools run when the dataset pins the full pipeline, or pins nothing and the workspace default is the full pipeline.
  const tools = pin === true || (pin === null && workspaceDefault !== "QUICK");

  const autoApproveBlocked = disabled || !tools || refusal !== null;

  return (
    <fieldset className="min-w-0 space-y-4" disabled={disabled}>
      <legend className="text-sm font-semibold text-foreground">{t("modeTitle")}</legend>
      <p className="text-xs text-muted-foreground">{t("modeHelp")}</p>

      <div role="radiogroup" aria-label={t("modeTitle")} className="grid gap-2 sm:grid-cols-3">
        <label
          htmlFor={`${uid}-default`}
          className={`flex cursor-pointer items-start gap-2 rounded-md border p-3 text-sm ${pin === null ? "border-primary" : "border-border"}`}
        >
          <input
            id={`${uid}-default`}
            type="radio"
            name={`${uid}-mode`}
            className="mt-1"
            checked={pin === null}
            onChange={() => onChange({ ...WORKSPACE_DEFAULT, autoApproveTools: mode.autoApproveTools })}
          />
          <span>
            <span className="block font-medium text-foreground">{t("modeDefault")}</span>
            <span className="block text-xs text-muted-foreground">
              {workspaceDefault
                ? t("modeDefaultCurrent", { mode: t(workspaceDefault === "FULL" ? "modeNameFull" : "modeNameQuick") })
                : t("modeDefaultHelp")}
            </span>
          </span>
        </label>
        <label
          htmlFor={`${uid}-tools`}
          className={`flex cursor-pointer items-start gap-2 rounded-md border p-3 text-sm ${pin === true ? "border-primary" : "border-border"}`}
        >
          <input
            id={`${uid}-tools`}
            type="radio"
            name={`${uid}-mode`}
            className="mt-1"
            checked={pin === true}
            onChange={() => onChange({ toolExecutionEnabled: true, autoApproveTools: mode.autoApproveTools })}
          />
          <span>
            <span className="block font-medium text-foreground">{t("modeFull")}</span>
            <span className="block text-xs text-muted-foreground">{t("modeFullHelp")}</span>
          </span>
        </label>
        <label
          htmlFor={`${uid}-text`}
          className={`flex cursor-pointer items-start gap-2 rounded-md border p-3 text-sm ${pin === false ? "border-primary" : "border-border"}`}
        >
          <input
            id={`${uid}-text`}
            type="radio"
            name={`${uid}-mode`}
            className="mt-1"
            checked={pin === false}
            onChange={() => onChange(TEXT_ONLY)}
          />
          <span>
            <span className="block font-medium text-foreground">{t("modeTextOnly")}</span>
            <span className="block text-xs text-muted-foreground">{t("modeTextOnlyHelp")}</span>
          </span>
        </label>
      </div>

      {tools && (
        <p className="text-xs text-muted-foreground" data-testid="mode-full-note">
          {t("modeFullNote")}
        </p>
      )}

      <div className="space-y-2 rounded-md border border-border p-3">
        <div className="flex items-start gap-3">
          <Switch
            id={`${uid}-auto`}
            checked={mode.autoApproveTools}
            disabled={autoApproveBlocked}
            aria-describedby={`${uid}-auto-help`}
            aria-label={t("autoApproveLabel")}
            onCheckedChange={(next) => {
              if (next) setConfirming(true);
              else onChange({ toolExecutionEnabled: pin, autoApproveTools: false });
            }}
          />
          <div className="min-w-0 space-y-1">
            <label htmlFor={`${uid}-auto`} className="block text-sm font-medium text-foreground">
              {t("autoApproveLabel")}
            </label>
            <p id={`${uid}-auto-help`} className="text-xs text-muted-foreground">
              {t("autoApproveHelp")}
            </p>
          </div>
        </div>
        <Alert variant="warning" role="note" data-testid="auto-approve-warning">
          <ShieldAlert aria-hidden="true" />
          <AlertTitle>{t("autoApproveWarnTitle")}</AlertTitle>
          <AlertDescription>{t("autoApproveWarnBody")}</AlertDescription>
        </Alert>
        {!tools && !refusal && (
          <p className="text-xs text-muted-foreground" data-testid="auto-approve-disabled-reason">
            {t("autoApproveNeedsFull")}
          </p>
        )}
        {refusal && (
          <p role="alert" className="text-xs text-destructive" data-testid="auto-approve-refused">
            {t("autoApproveRefused", { reason: refusal })}
          </p>
        )}
      </div>

      <div className="space-y-1 rounded-md border border-border p-3">
        <div className="flex items-center gap-3">
          <Switch
            id={`${uid}-traj`}
            checked={Boolean(trajectory)}
            disabled
            aria-describedby={`${uid}-traj-help`}
            aria-label={t("trajectoryLabel")}
          />
          <label htmlFor={`${uid}-traj`} className="text-sm font-medium text-foreground">
            {t("trajectoryLabel")}
          </label>
          <Badge variant="outline">{trajectory ? t("trajectoryOn") : t("trajectoryOff")}</Badge>
        </div>
        <p id={`${uid}-traj-help`} className="text-xs text-muted-foreground">
          {trajectory ? t("trajectoryHelpOn") : t("trajectoryHelpOff")}
        </p>
        {trajectory && !tools && (
          <p className="text-xs text-amber-600 dark:text-amber-400" data-testid="trajectory-text-only-note">
            {t("trajectoryNeedsFull")}
          </p>
        )}
      </div>

      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("autoApproveConfirmTitle")}
        description={t("autoApproveConfirmBody")}
        confirmLabel={t("autoApproveConfirm")}
        cancelLabel={t("cancel")}
        onConfirm={() => onChange({ toolExecutionEnabled: pin, autoApproveTools: true })}
      />
    </fieldset>
  );
}
