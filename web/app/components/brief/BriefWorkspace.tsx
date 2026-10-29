"use client";

// One Brief on screen. A draft walks Preview -> Ready -> Apply: see what the
// envoy is made of, see what it needs and what is still missing, then apply and
// watch each step land. An applied envoy skips the stepper and shows the graph
// beside its readiness rail. Nothing here fakes state: every dot, reason and
// step status comes from the readiness rows / apply job it is given.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Loader2, ShieldCheck } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import ApplyProgress from "./ApplyProgress";
import EnvoyGraph from "./EnvoyGraph";
import NarrativeCard from "./NarrativeCard";
import ReadinessRail from "./ReadinessRail";
import RungBadge from "./RungBadge";
import { summariseApply, summariseReadiness } from "./logic";
import type { ApplyJob, Brief, BriefDetail, ReadinessRow } from "./types";

type Step = "preview" | "ready" | "applying" | "done";
const STEPS: Step[] = ["preview", "ready", "applying"];
const POLL_MS = 1500;

export default function BriefWorkspace({
  brief,
  readiness,
  warnings = [],
  detail,
  onBack,
  onAskAdmin,
  detailHref = (id: string) => `/build/${id}`,
}: {
  brief: Brief;
  readiness: readonly ReadinessRow[];
  warnings?: readonly string[];
  /** Set for an already-applied envoy (bindings enable the editor links). */
  detail?: BriefDetail | null;
  onBack: () => void;
  onAskAdmin?: (row: ReadinessRow) => void;
  /** Where the applied envoy's proof ledger, trust ladder and tuning live. */
  detailHref?: (id: string) => string;
}) {
  const t = useTranslations("brief");
  const applied = !!detail && detail.status === "APPLIED";
  const [step, setStep] = useState<Step>("preview");
  const [selected, setSelected] = useState<string | null>(null);
  const [job, setJob] = useState<ApplyJob | null>(null);
  const [error, setError] = useState<string | null>(null);
  const alive = useRef(true);
  // A saved draft opened from the list is applied in place, not saved again as a second brief.
  const briefId = useRef<string | null>(detail?.id ?? null);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const summary = useMemo(() => summariseReadiness(readiness), [readiness]);
  const closeNarrative = useCallback(() => setSelected(null), []);

  const runApply = useCallback(async () => {
    setError(null);
    setJob(null);
    setStep("applying");
    try {
      if (!briefId.current) briefId.current = (await api.createBrief(brief)).id;
      const id = briefId.current;
      const { jobId } = await api.applyBrief(id, { background: true });
      for (;;) {
        const next: ApplyJob = await api.getApplyJob(id, jobId);
        if (!alive.current) return;
        setJob(next);
        if (summariseApply(next).terminal) {
          if (next.status === "SUCCEEDED") setStep("done");
          return;
        }
        await new Promise((r) => setTimeout(r, POLL_MS));
        if (!alive.current) return;
      }
    } catch (e: any) {
      if (alive.current) setError(e?.message || t("apply.failedGeneric"));
    }
  }, [brief, t]);

  const graph = <EnvoyGraph brief={brief} readiness={readiness} selectedRef={selected} onSelect={setSelected} />;
  const rail = <ReadinessRail rows={readiness} onAskAdmin={onAskAdmin} onSelect={setSelected} />;

  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <Button variant="ghost" size="sm" onClick={onBack} className="-ms-2">
          <ArrowLeft className="size-4" aria-hidden="true" />
          {t("workspace.back")}
        </Button>
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-xl font-semibold tracking-tight text-foreground">{brief.title}</h2>
          <RungBadge rung={detail?.rung ?? brief.trust.rung} />
          {applied && detail?.id && (
            <Button asChild size="sm" variant="outline">
              <Link href={detailHref(detail.id)}>
                <ShieldCheck className="size-4" aria-hidden="true" />
                {t("workspace.openDetail")}
              </Link>
            </Button>
          )}
        </div>
        <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground">{brief.narrative.whyAgentic}</p>
      </div>

      {warnings.length > 0 && (
        <Alert>
          <AlertDescription>
            <ul className="list-disc space-y-0.5 ps-4">
              {warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      {applied ? (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
          {graph}
          {rail}
        </div>
      ) : (
        <>
          <ol className="flex flex-wrap items-center gap-2 text-xs font-medium" aria-label={t("workspace.stepsLabel")}>
            {STEPS.map((s, i) => {
              const current = step === s || (step === "done" && s === "applying");
              return (
                <li
                  key={s}
                  aria-current={current ? "step" : undefined}
                  className={cn(
                    "flex items-center gap-1.5 rounded-full border px-3 py-1",
                    current ? "border-primary bg-primary/10 text-foreground" : "border-border text-muted-foreground"
                  )}
                >
                  <span className="tabular-nums">{i + 1}</span>
                  {t(`workspace.step.${s}`)}
                </li>
              );
            })}
          </ol>

          {step === "preview" && (
            <div className="space-y-4">
              {graph}
              <div className="flex justify-end">
                <Button onClick={() => setStep("ready")}>{t("workspace.toReady")}</Button>
              </div>
            </div>
          )}

          {step === "ready" && (
            <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
              <div className="space-y-4">
                <p className="text-sm text-foreground">
                  {summary.needsAttention === 0
                    ? t("workspace.readyAll")
                    : t("workspace.readySome", { count: summary.needsAttention })}
                </p>
                <p className="text-sm text-muted-foreground">{t("workspace.startsUnderWatch")}</p>
                <div className="flex flex-wrap gap-2">
                  <Button variant="outline" onClick={() => setStep("preview")}>
                    {t("workspace.backToPreview")}
                  </Button>
                  <Button onClick={runApply} disabled={(brief.clarifications?.length ?? 0) > 0}>
                    {t("workspace.apply")}
                  </Button>
                </div>
                {(brief.clarifications?.length ?? 0) > 0 && (
                  <p className="text-xs text-muted-foreground">
                    {t("workspace.clarificationsBlockApply", { count: brief.clarifications!.length })}
                  </p>
                )}
              </div>
              {rail}
            </div>
          )}

          {(step === "applying" || step === "done") && (
            <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
              <div className="space-y-4">
                {error && (
                  <Alert variant="destructive">
                    <AlertDescription>{error}</AlertDescription>
                  </Alert>
                )}
                {!error && !job && (
                  <p className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                    {t("apply.starting")}
                  </p>
                )}
                {job && <ApplyProgress brief={brief} job={job} onRetry={runApply} />}
                {error && (
                  <Button size="sm" variant="outline" onClick={runApply}>
                    {t("apply.retry")}
                  </Button>
                )}
                {step === "done" && <p className="text-sm text-foreground">{t("apply.doneBody")}</p>}
              </div>
              {rail}
            </div>
          )}
        </>
      )}

      {selected && <NarrativeCard brief={brief} refKey={selected} detail={detail} onClose={closeNarrative} />}
    </div>
  );
}
