"use client";

import { Check, Loader2, XCircle } from "lucide-react";
import { motion } from "motion/react";
import BuildErrorPanel from "../BuildErrorPanel";
import { stepLabel } from "../../lib/build/explainError";
import { summariseApply } from "../../components/brief/logic";
import type { Blueprint } from "../../lib/build/blueprint";
import type { explainBuildError, RawBuildError } from "../../lib/build/explainError";
import type { ApplyJob } from "../../components/brief/types";
import { useTranslations } from "../../lib/i18n";

/** Stage 3: build progress, or the explained failure. */
export default function BuildingStage({
  bp,
  innerRef,
  buildError,
  explanation,
  job,
  progress,
  pct,
  elapsed,
  reduceMotion,
  onRetry,
  onBack,
}: {
  bp: Blueprint;
  innerRef: React.Ref<HTMLDivElement>;
  buildError: RawBuildError | null;
  explanation: ReturnType<typeof explainBuildError> | null;
  job: ApplyJob | null;
  progress: ReturnType<typeof summariseApply> | null;
  pct: number | null;
  elapsed: number;
  reduceMotion: boolean;
  onRetry: () => void;
  onBack: () => void;
}) {
    const tHC = useTranslations("hcBuildCreateBuildingStage");
  return (
    <motion.div
      key="building"
      ref={innerRef}
      tabIndex={-1}
      aria-label={buildError ? "Build problem" : "Build progress"}
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.2 }}
      className="space-y-4 outline-none"
    >
      {explanation ? (
        <BuildErrorPanel
          explanation={explanation}
          onRetry={onRetry}
          onBack={onBack}
          keptNote={
            job && summariseApply(job).nothingKept
              ? "Nothing was left half-built - the partial work was cleaned up, so it is safe to try again."
              : job && summariseApply(job).failed
                ? "Some pieces were created before it stopped. They are listed under Agents; you can delete them or press Try again."
                : null
          }
        />
      ) : (
        <div className="rounded-2xl border border-border bg-card p-5">
          <div className="flex items-center justify-between gap-3">
            <h2 className="flex items-center gap-2 text-xl font-semibold tracking-tight text-foreground">
              <Loader2 className="size-5 animate-spin text-primary motion-reduce:animate-none" aria-hidden />
              {bp.output === "agent" ? `Building ${bp.agent.name || "your agent"}…` : "Saving your app to Git…"}
            </h2>
            {/* The ticking clock is visual only; announcing it every second would drown the real updates. */}
            <span className="text-xs tabular-nums text-muted-foreground" aria-hidden>
              {elapsed}s
            </span>
          </div>
          <div
            className="mt-3 h-2 overflow-hidden rounded-full bg-muted"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={pct ?? undefined}
            aria-label={tHC("hcBuildCreateBuildingStage.s1")}
          >
            {pct == null ? (
              reduceMotion ? (
                <div className="h-full w-1/3 rounded-full bg-primary/70" />
              ) : (
                <motion.div
                  className="h-full w-1/3 rounded-full bg-primary"
                  animate={{ x: ["-100%", "300%"] }}
                  transition={{ duration: 1.4, repeat: Infinity, ease: "easeInOut" }}
                />
              )
            ) : (
              <motion.div
                className="h-full rounded-full bg-primary"
                initial={false}
                animate={{ width: `${Math.max(pct, 6)}%` }}
                transition={{ duration: 0.4 }}
              />
            )}
          </div>
          <p className="mt-2 text-sm text-muted-foreground" role="status" aria-live="polite">
            {bp.output === "agent"
              ? job
                ? `Step ${Math.min((progress?.done ?? 0) + 1, job.steps.length)} of ${job.steps.length}`
                : "Registering your blueprint and queueing the build…"
              : "Creating the branch, committing the starter, opening the pull request and running the first generation. This can take a minute or two."}
          </p>
          {job && bp.output === "agent" && (
            <ol className="mt-3 space-y-1.5">
              {job.steps.map((s) => (
                <li key={s.seq} className="flex items-center gap-2 text-sm">
                  {s.status === "DONE" ? (
                    <Check aria-hidden="true" className="size-4 text-emerald-500" />
                  ) : s.status === "FAILED" ? (
                    <XCircle aria-hidden="true" className="size-4 text-destructive" />
                  ) : s.status === "PENDING" ? (
                    <span className="ms-1.5 me-0.5 size-2 rounded-full bg-muted-foreground/40" />
                  ) : (
                    <Loader2 aria-hidden="true" className="size-4 animate-spin text-primary" />
                  )}
                  <span className={s.status === "PENDING" ? "text-muted-foreground" : "text-foreground"}>
                    {stepLabel(s.kind)}
                  </span>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </motion.div>
  );
}
