"use client";

import { useEffect, useState } from "react";
import { Check, CheckCircle2, Loader2, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { formatElapsed } from "../../lib/turnProgress";
import { cn } from "../../lib/utils";
import type { ApprovalContinuationWatch } from "./useApprovalContinuation";

/** How long the "finished carrying on" line stays before it closes itself. */
export const COMPLETED_AUTO_CLOSE_MS = 8_000;
/** After this long the live line adds "still working" (a local model on a laptop is often this slow). */
export const STILL_WORKING_MS = 30_000;
/** After this long it says plainly that this is taking a while. */
export const LONG_WAIT_MS = 3 * 60_000;

/**
 * The line a chat surface shows while the agent carries on after a tool approval was decided
 * (useApprovalContinuation), and what became of it: still going, finished, or could not carry on - with the
 * plain-language reason and, where the host can send one, a Retry. Renders nothing otherwise.
 *
 *   continuing -> "The agent is carrying on with the task."
 *   outcome completed -> a short receipt that closes itself
 *   outcome failed / problem notStarted / timedOut -> an alert that stays until dismissed or retried
 */
export default function ApprovalContinuationStatus({
  continuation,
  className,
}: {
  continuation: Pick<ApprovalContinuationWatch, "continuing" | "problem" | "dismiss"> &
    Partial<Pick<ApprovalContinuationWatch, "outcome" | "retry" | "since" | "phase">>;
  className?: string;
}) {
  const t = useTranslations("toolApproval");
  const tq = useTranslations("approvalsQueue");
  const { continuing, problem, outcome, retry, dismiss } = continuation;
  const completed = !continuing && !problem && outcome === "completed";

  // A clean finish is only a receipt: it closes itself. Problems and failures stay until the person acts.
  useEffect(() => {
    if (!completed) return undefined;
    const timer = setTimeout(dismiss, COMPLETED_AUTO_CLOSE_MS);
    return () => clearTimeout(timer);
  }, [completed, dismiss]);

  if (continuing) {
    return <ContinuingLine continuation={continuation} className={className} />;
  }
  if (completed) {
    return (
      <p
        role="status"
        data-testid="approval-continuation-completed"
        className={className ?? "flex items-center gap-2 text-xs text-muted-foreground"}
      >
        <CheckCircle2 size={12} className="size-3 shrink-0" aria-hidden="true" />
        {tq("continuationCompleted")}
      </p>
    );
  }
  const kind = problem ?? (outcome === "failed" ? "failed" : null);
  if (!kind) return null;
  return (
    <div
      role="alert"
      data-testid={`approval-continuation-${kind}`}
      className={
        className ??
        "flex items-start gap-2 rounded-lg border border-border bg-card px-3 py-2 text-xs text-muted-foreground"
      }
    >
      <span className="min-w-0 flex-1">
        {kind === "timedOut" && t("continueTimedOut")}
        {kind === "notStarted" && t("continueNotStarted")}
        {kind === "failed" && tq("continuationFailed")}
        {retry && (
          <>
            {" "}
            <button
              type="button"
              data-testid="approval-continuation-retry"
              onClick={retry}
              className="font-medium text-foreground underline underline-offset-2 hover:no-underline"
            >
              {tq("continuationRetry")}
            </button>
          </>
        )}
      </span>
      <button
        type="button"
        aria-label={t("close")}
        title={t("close")}
        data-testid="approval-continuation-dismiss"
        onClick={dismiss}
        className="-mr-1 shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
      >
        <X size={14} className="size-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}

/**
 * The live half: core runs the continuation turn detached and streams it to nobody, so the browser cannot show
 * its tool steps or text as they happen. What it can show honestly: how long it has been going, which of the
 * three stages the stored conversation proves (decision recorded -> agent restarted -> working on it), and, as
 * time passes, that a slow model is still expected to answer. The timer is hidden from screen readers (it would
 * be read out every second); the stage line is a polite live region that changes a few times at most.
 */
function ContinuingLine({
  continuation,
  className,
}: {
  continuation: Partial<Pick<ApprovalContinuationWatch, "since" | "phase">>;
  className?: string;
}) {
  const t = useTranslations("toolApproval");
  const { since, phase } = continuation;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const elapsed = since != null ? Math.max(0, now - since) : null;
  const running = phase === "running";
  const hint =
    elapsed != null && elapsed >= LONG_WAIT_MS
      ? t("continuingLongWait")
      : elapsed != null && elapsed >= STILL_WORKING_MS
        ? t("continuingStill")
        : running
          ? t("continuingRunning")
          : t("continuingWaiting");
  const stages: Array<{ key: string; state: "done" | "active" | "todo" }> = [
    { key: "continuingStageDecided", state: "done" },
    { key: "continuingStageRestarted", state: running ? "done" : "active" },
    { key: "continuingStageWorking", state: running ? "active" : "todo" },
  ];
  return (
    <div
      data-testid="approval-continuing"
      data-phase={phase ?? undefined}
      className={
        className ??
        "w-full max-w-[85%] rounded-xl border border-border bg-card/70 px-3 py-2 text-xs text-muted-foreground"
      }
    >
      <div className="flex items-center gap-2">
        <span className="relative flex size-2.5 shrink-0" aria-hidden="true">
          <span className="absolute inline-flex size-full rounded-full bg-primary/40 motion-safe:animate-ping" />
          <span className="relative inline-flex size-2.5 rounded-full bg-primary" />
        </span>
        <span role="status" className="min-w-0 flex-1 font-medium text-foreground">
          {t("continuing")}
        </span>
        {elapsed != null && (
          <span className="shrink-0 tabular-nums" aria-hidden="true" data-testid="approval-continuing-elapsed">
            {formatElapsed(elapsed)}
          </span>
        )}
      </div>
      <ol className="mt-1.5 flex flex-wrap items-center gap-1" aria-label={t("continuingStagesLabel")}>
        {stages.map((stage) => (
          <li
            key={stage.key}
            data-state={stage.state}
            className={cn(
              "inline-flex items-center gap-1 rounded-full border px-2 py-0.5",
              stage.state === "done" && "border-border text-muted-foreground",
              stage.state === "active" && "border-primary/40 bg-primary/10 text-foreground",
              stage.state === "todo" && "border-dashed border-border text-muted-foreground/70"
            )}
          >
            {stage.state === "done" && <Check size={11} className="size-3 shrink-0" aria-hidden="true" />}
            {stage.state === "active" && (
              <Loader2 size={11} className="size-3 shrink-0 motion-safe:animate-spin" aria-hidden="true" />
            )}
            {t(stage.key)}
          </li>
        ))}
      </ol>
      <p className="mt-1.5" aria-live="polite" data-testid="approval-continuing-hint">
        {hint}
      </p>
    </div>
  );
}
