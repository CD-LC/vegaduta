"use client";

// Agent Lab - the "run with the agent's tools" switch Prompt Knockout and the Consistency check share.
// Off by default. Where the install does not allow it the switch is shown, disabled, with the reason, never
// hidden: a missing control reads as a missing feature. Turning it on asks for confirmation because real tools
// can have real effects.
import { useEffect, useId, useState, type ReactElement } from "react";
import { Wrench } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { ConfirmDialog } from "../../ui/confirm-dialog";
import { Switch } from "../../ui/switch";
import type { LabCapabilities } from "./labApi";

export interface LabWithTools {
  /** The run asks for the agent's tools: switched on, and nothing says it cannot be. */
  withTools: boolean;
  setWithTools: (on: boolean) => void;
  /** The server's sentence when it refused a run for tools; null otherwise. */
  refusal: string | null;
  /** Capabilities say this install has the mode off. */
  unavailable: boolean;
}

/**
 * State of the switch for one panel. A run the server refuses for tools (the mode is off on this install: its
 * sentence names app.agent-lab.with-tools.enabled) turns the switch off and keeps it disabled with that sentence.
 * That text match is only a fallback for an older core whose capabilities body has no withToolsAvailable field; a
 * core that reports the field is believed instead, and the switch is disabled up front when it says false.
 */
export function useLabWithTools(
  lab: { phase: string; error: string | null },
  capabilities: LabCapabilities | null
): LabWithTools {
  const [on, setOn] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const unavailable = capabilities?.withToolsAvailable === false;

  const reportsAvailability = capabilities?.withToolsAvailable !== undefined;

  useEffect(() => {
    if (!reportsAvailability && on && lab.phase === "error" && lab.error && /with-tools/i.test(lab.error)) {
      setRefusal(lab.error);
      setOn(false);
    }
  }, [reportsAvailability, on, lab.phase, lab.error]);

  return { withTools: on && !unavailable && refusal === null, setWithTools: setOn, refusal, unavailable };
}

export default function LabWithToolsToggle(props: { state: LabWithTools; disabled?: boolean }): ReactElement {
  const { state, disabled = false } = props;
  const t = useTranslations("agentLab");
  const uid = useId();
  const [confirming, setConfirming] = useState(false);
  const blocked = state.unavailable || state.refusal !== null;

  return (
    <div className="space-y-1.5 rounded-lg border border-border p-3">
      <div className="flex items-start gap-3">
        <Switch
          id={`${uid}-switch`}
          checked={state.withTools}
          disabled={disabled || blocked}
          aria-describedby={`${uid}-help`}
          aria-label={t("wtLabel")}
          onCheckedChange={(next) => {
            if (next) setConfirming(true);
            else state.setWithTools(false);
          }}
        />
        <div className="min-w-0 space-y-1">
          <label htmlFor={`${uid}-switch`} className="flex items-center gap-1.5 text-sm font-medium text-foreground">
            <Wrench size={13} aria-hidden="true" />
            {t("wtLabel")}
          </label>
          <p id={`${uid}-help`} className="text-xs text-muted-foreground">
            {t("wtHelp")}
          </p>
        </div>
      </div>
      {state.unavailable && (
        <p className="text-xs text-foreground" data-testid="lab-tools-unavailable">
          {t("wtUnavailable")}
        </p>
      )}
      {state.refusal !== null && (
        <p role="alert" className="text-xs text-destructive" data-testid="lab-tools-refused">
          {t("wtRefused", { reason: state.refusal })}
        </p>
      )}
      {state.withTools && <p className="text-xs font-medium text-foreground">{t("wtActive")}</p>}
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("wtConfirmTitle")}
        description={t("wtConfirmBody")}
        confirmLabel={t("wtConfirm")}
        cancelLabel={t("cancel")}
        onConfirm={() => state.setWithTools(true)}
      />
    </div>
  );
}
