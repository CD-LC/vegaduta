"use client";

import { useEffect } from "react";
import { CheckCircle2, Loader2, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { ApprovalContinuationWatch } from "./useApprovalContinuation";

/** How long the "finished carrying on" line stays before it closes itself. */
export const COMPLETED_AUTO_CLOSE_MS = 8_000;

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
    Partial<Pick<ApprovalContinuationWatch, "outcome" | "retry">>;
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
    return (
      <p
        role="status"
        data-testid="approval-continuing"
        className={className ?? "flex items-center gap-2 text-xs text-muted-foreground"}
      >
        <Loader2 size={12} className="size-3 shrink-0 animate-spin" aria-hidden="true" />
        {t("continuing")}
      </p>
    );
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
