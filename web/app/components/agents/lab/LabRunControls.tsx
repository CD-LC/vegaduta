"use client";

// Agent Lab - the three controls Prompt Knockout and the Consistency check
// share: the pre-run call estimate, the (plan-gated) Run button, and the
// status/progress/cancel strip for a run driven by useLabRun. Kept together
// so both instruments state spend and progress in exactly the same words.
import type { ReactElement } from "react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import type { LabCapabilities } from "./labApi";
import type { UseLabRun } from "./useLabRun";

/** "This will make N model calls", shown BEFORE anything is spent. `calls`
 * comes from labApi.plannedCalls, the same formula the server enforces. */
export function LabEstimate(props: { calls: number; cap: number; breakdown?: string }): ReactElement {
  const { calls, cap, breakdown } = props;
  const t = useTranslations("agentLab");
  let headline: string;
  if (calls === 0) headline = t("estimateNone");
  else if (calls === 1) headline = t("estimateCallsOne");
  else headline = t("estimateCalls", { count: calls });
  return (
    <div className="space-y-1 text-xs text-muted-foreground">
      <p className="font-medium text-foreground">
        {headline}
        {breakdown ? ` ${breakdown}` : null}
      </p>
      {calls > cap && (
        <p className="text-destructive" role="alert">
          {t("estimateOverCap", { cap })}
        </p>
      )}
      <p>{t("estimateUsageNote")}</p>
    </div>
  );
}

// A natively disabled Button sets pointer-events:none, which also hides its
// `title` from a mouse user - the one place the reason is explained. These
// put hover back (a disabled button still cannot be clicked).
const EXPLAINED_DISABLED = "disabled:pointer-events-auto disabled:cursor-not-allowed";

export function LabRunButton(props: {
  label: string; calls: number; capabilities: LabCapabilities | null;
  disabled?: boolean; disabledReason?: string; onRun: () => void;
}): ReactElement {
  const { label, calls, capabilities, disabled, disabledReason, onRun } = props;
  const t = useTranslations("agentLab");
  // No capabilities body at all: a core that predates the lab routes (or the
  // read failed). Nothing can run, and an upgrade would not change that.
  if (capabilities === null) {
    return (
      <Button type="button" disabled title={t("capabilitiesUnsupported")} className={EXPLAINED_DISABLED}>
        {label}
      </Button>
    );
  }
  // FREE plan: locked, with the Upgrade affordance on the control itself.
  // Core enforces the same gate with a 403, so this is presentation only.
  if (capabilities.allowed === false) {
    return (
      <Button type="button" upgradeLocked upgradeReason={t("planLockedReason")} upgradeLabel={t("upgradeLabel")}>
        {label}
      </Button>
    );
  }
  const callerDisabled = disabled === true;
  const blocked = callerDisabled || calls === 0 || calls > capabilities.maxCallsPerRun;
  const reason = callerDisabled ? disabledReason : undefined;
  return (
    <Button type="button" disabled={blocked} title={reason} className={reason ? EXPLAINED_DISABLED : undefined} onClick={onRun}>
      {label}
    </Button>
  );
}

/** Status line, live progress and the actions that fit the phase: Cancel
 * while live; when lost, Check again, Cancel (the run may still be spending)
 * and Clear results (the way out when the server keeps refusing the poll);
 * Clear results afterwards. */
export function LabRunStatus<C>(props: { lab: UseLabRun<C>; ttlMinutes: number }): ReactElement | null {
  const { lab, ttlMinutes } = props;
  const { run, phase, error, cancelling } = lab;
  const t = useTranslations("agentLab");
  if (phase === "idle") return null;

  let status: string;
  if (phase === "starting") status = t("statusStarting");
  else if (phase === "error") status = t("statusError", { reason: error ?? "" });
  else if (phase === "expired") status = t("statusExpired", { minutes: ttlMinutes });
  else if (phase === "lost") status = t("statusLost");
  // Polling a restored run whose first response has not landed yet.
  else if (!run) status = t("statusRunning");
  else if (run.status === "QUEUED") status = t("statusQueued");
  else if (run.status === "RUNNING") status = t("statusRunning");
  else if (run.status === "SUCCEEDED") status = t("statusSucceeded");
  else if (run.status === "CANCELLED") status = t("statusCancelled");
  else status = t("statusFailed", { reason: run.errorMessage ?? "" });

  const live = phase === "polling";
  const showProgress = live && run != null && (run.status === "QUEUED" || run.status === "RUNNING");
  const total = run ? Math.max(run.plannedCalls, 0) : 0;
  const done = run ? Math.min(Math.max(run.completedCalls, 0), total) : 0;
  const percent = total > 0 ? (done / total) * 100 : 0;
  const failed = phase === "error" || (phase === "done" && run?.status === "FAILED");

  return (
    <div className="space-y-2 rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p role="status" aria-live="polite" className={failed ? "text-destructive" : "text-foreground"}>
          {status}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          {phase === "lost" && (
            <Button type="button" variant="outline" size="sm" onClick={lab.resume}>
              {t("resume")}
            </Button>
          )}
          {(live || phase === "lost") && (
            <Button type="button" variant="outline" size="sm" disabled={cancelling} onClick={() => void lab.cancel()}>
              {cancelling ? t("cancelling") : t("cancel")}
            </Button>
          )}
          {(phase === "done" || phase === "expired" || phase === "error") && (
            <Button type="button" variant="outline" size="sm" onClick={lab.reset}>
              {t("runAgain")}
            </Button>
          )}
          {/* From "lost", reset also asks the server to stop the run (useLabRun), hence the different wording. */}
          {phase === "lost" && (
            <Button type="button" variant="outline" size="sm" onClick={lab.reset}>
              {t("stopAndClear")}
            </Button>
          )}
        </div>
      </div>
      {showProgress && (
        <div className="space-y-1">
          <svg
            role="progressbar"
            aria-label={t("progress", { done, total })}
            aria-valuemin={0}
            aria-valuemax={total}
            aria-valuenow={done}
            viewBox="0 0 100 4"
            preserveAspectRatio="none"
            className="block h-1.5 w-full overflow-hidden rounded-full"
          >
            <rect x="0" y="0" width="100" height="4" className="fill-border" />
            <rect x="0" y="0" width={percent} height="4" className="fill-primary" />
          </svg>
          <p className="text-muted-foreground">{t("progress", { done, total })}</p>
        </div>
      )}
    </div>
  );
}
