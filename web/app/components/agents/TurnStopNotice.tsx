"use client";

import { OctagonAlert } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { stopReasonKind, turnStopKind, type ActivityStep } from "./AgentActivityTrace";

/**
 * One always-visible line under a reply whose run was STOPPED by a limit the
 * person can act on - the per-run spending cap or the tool-call ceiling.
 *
 * The activity trace is collapsed by default, so before this a stopped run
 * looked like an ordinary (often odd, truncated) answer and the reason sat
 * behind a chevron as a raw token. The detection lives in AgentActivityTrace
 * (stopReasonKind / turnStopKind) so the trace row and this notice can never
 * disagree; unknown reasons render nothing here.
 *
 * `message` covers the stop that never becomes a step event: the tool-call
 * ceiling fails the request itself, so its text arrives as the turn's error.
 */
export default function TurnStopNotice({
  steps,
  message,
  className,
}: {
  steps?: readonly ActivityStep[] | null;
  message?: string | null;
  className?: string;
}) {
  const t = useTranslations("runCost");
  const kind = turnStopKind(steps) ?? stopReasonKind(message);
  if (!kind) return null;
  return (
    <p
      role="status"
      data-testid={`turn-stop-${kind}`}
      className={cn(
        "flex w-full max-w-[85%] items-start gap-1.5 rounded-md border border-border bg-secondary/40 px-2.5 py-1.5 text-xs text-foreground",
        className
      )}
    >
      <OctagonAlert size={12} className="mt-0.5 size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
      <span>{kind === "run_budget" ? t("stopRunBudget") : t("stopStepLimit")}</span>
    </p>
  );
}
