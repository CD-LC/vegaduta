"use client";

import { Check, Circle, Loader2, Undo2, XCircle } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { kindOfRef, labelOfRef, summariseApply } from "./logic";
import type { ApplyJob, ApplyStep, Brief } from "./types";

function StepIcon({ status, running }: { status: ApplyStep["status"]; running: boolean }) {
  if (status === "DONE") return <Check className="size-4 text-[var(--state-success)]" aria-hidden="true" />;
  if (status === "FAILED") return <XCircle className="size-4 text-[var(--state-error)]" aria-hidden="true" />;
  if (status === "COMPENSATED") return <Undo2 className="size-4 text-muted-foreground" aria-hidden="true" />;
  return running ? <Loader2 className="size-4 animate-spin text-muted-foreground" aria-hidden="true" /> : <Circle className="size-4 text-muted-foreground" aria-hidden="true" />;
}

/**
 * Per-step status of an apply job (polled by the parent). A failed job names
 * the offending step and says whether anything was kept - only "nothing was
 * kept" when the step ledger shows every earlier step rolled back.
 */
export default function ApplyProgress({
  brief,
  job,
  onRetry,
}: {
  brief: Brief;
  job: ApplyJob | null;
  onRetry?: () => void;
}) {
  const t = useTranslations("brief");
  const p = summariseApply(job);
  const steps = job?.steps ?? [];
  // The first PENDING step of a live job is the one in flight.
  const activeSeq = !p.terminal ? steps.find((s) => s.status === "PENDING")?.seq : undefined;
  return (
    <section aria-label={t("apply.title")} aria-live="polite" className="space-y-3">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold text-foreground">{p.failed ? t("apply.failedTitle") : p.terminal ? t("apply.doneTitle") : t("apply.title")}</h3>
        {p.total > 0 && <span className="text-xs text-muted-foreground tabular-nums">{t("apply.count", { done: p.done, total: p.total })}</span>}
      </div>

      {steps.length === 0 ? (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {t("apply.starting")}
        </p>
      ) : (
        <ol className="space-y-1.5">
          {steps.map((s) => {
            const kind = kindOfRef(s.ref);
            return (
              <li
                key={s.seq}
                className={cn("flex items-start gap-2.5 rounded-lg border border-border px-3 py-2 text-sm", s.status === "FAILED" && "border-[var(--state-error)]")}
                data-status={s.status}
              >
                <span className="mt-0.5 shrink-0">
                  <StepIcon status={s.status} running={s.seq === activeSeq} />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-foreground">
                    {kind ? <span className="text-muted-foreground">{t(`kind.${kind}`)} </span> : null}
                    {labelOfRef(brief, s.ref)}
                  </div>
                  {s.error && (s.status === "FAILED" || /^(SKIPPED|NOTE):/.test(s.error)) && (
                    <div className="mt-0.5 text-xs text-muted-foreground">{s.error.replace(/^(SKIPPED|NOTE):\s*/, "")}</div>
                  )}
                </div>
                <span className="shrink-0 text-xs text-muted-foreground">{t(`apply.step.${s.status}`)}</span>
              </li>
            );
          })}
        </ol>
      )}

      {p.failed && (
        <div role="alert" className="space-y-2 rounded-lg border border-[var(--state-error)] bg-card p-3 text-sm">
          <p className="font-medium text-foreground">
            {p.failedStep
              ? t("apply.failedAt", { step: p.failedStep.seq, name: labelOfRef(brief, p.failedStep.ref) })
              : t("apply.failedGeneric")}
          </p>
          {(p.failedStep?.error || job?.error) && <p className="text-muted-foreground">{p.failedStep?.error || job?.error}</p>}
          <p className="text-muted-foreground">{p.nothingKept ? t("apply.nothingKept") : t("apply.someKept")}</p>
          {onRetry && (
            <Button size="sm" variant="outline" onClick={onRetry}>
              {t("apply.retry")}
            </Button>
          )}
        </div>
      )}
    </section>
  );
}
