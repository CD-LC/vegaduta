"use client";

// One applied envoy, everything in one place: what it is made of (graph), what
// it still needs (rail), how it is scoring (ledger), how far it is trusted
// (ladder), a rehearsal before real traffic, and two ways to try it now. The
// ledger, ladder and coach all read the same ledger load, so a promotion or a
// tune refreshes every panel together.

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Loader2, MessageSquare, Play } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { useAuth } from "../AuthProvider";
import CoachButton from "./CoachDrawer";
import EnvoyGraph from "./EnvoyGraph";
import NarrativeCard from "./NarrativeCard";
import ProofLedger from "./ProofLedger";
import ReadinessRail from "./ReadinessRail";
import RehearsalPanel from "./RehearsalPanel";
import RungBadge from "./RungBadge";
import TrustLadder from "./TrustLadder";
import { bindingEntityId } from "./coach";
import { deepLink } from "./logic";
import { primaryAgent, useBriefLedger } from "./useBrief";
import type { BriefDetail } from "./types";

export default function EnvoyDetail({ id }: { id: string }) {
  const t = useTranslations("brief");
  const { hasPermission } = useAuth();
  const router = useRouter();
  const [detail, setDetail] = useState<BriefDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const { ledger, error: ledgerError, loading, reload } = useBriefLedger(id);
  const [runState, setRunState] = useState<{ status: "idle" | "starting" | "started" | "failed"; workflowId?: string }>(
    { status: "idle" }
  );

  const loadDetail = useCallback(async () => {
    try {
      setDetail(await api.getBrief(id));
      setError(null);
    } catch (e: any) {
      setError(e?.message || t("detail.loadFailed"));
    }
  }, [id, t]);

  useEffect(() => {
    void loadDetail();
  }, [loadDetail]);

  const agent = useMemo(() => (detail ? primaryAgent(detail) : null), [detail]);
  const workflow = useMemo(() => {
    if (!detail) return null;
    const wf = detail.brief.workflows.find((w) => w.sampleInput) ?? detail.brief.workflows[0];
    return wf ? { wf, entityId: bindingEntityId(detail.bindings, wf.ref) } : null;
  }, [detail]);

  const closeNarrative = useCallback(() => setSelected(null), []);
  const changed = useCallback(() => {
    void loadDetail();
    void reload();
  }, [loadDetail, reload]);

  async function runWorkflow() {
    if (!workflow?.entityId) return;
    setRunState({ status: "starting" });
    try {
      await api.runWorkflow(workflow.entityId, workflow.wf.sampleInput ?? "", undefined, undefined, true);
      setRunState({ status: "started", workflowId: workflow.entityId });
    } catch {
      setRunState({ status: "failed" });
    }
  }

  if (error) {
    return (
      <div className="space-y-3">
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
        <Button variant="outline" size="sm" onClick={loadDetail}>
          {t("apply.retry")}
        </Button>
      </div>
    );
  }
  if (!detail) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 animate-spin" aria-hidden="true" />
        {t("detail.loading")}
      </p>
    );
  }

  const { brief, readiness } = detail;
  const applied = detail.status === "APPLIED";
  const rung = detail.rung ?? brief.trust.rung;
  const experimentHref = agent?.entityId
    ? deepLink(`/agents?agentId=${encodeURIComponent(agent.entityId)}`, "workbench-versions")
    : null;

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link href="/build">
            <ArrowLeft className="size-4" aria-hidden="true" />
            {t("workspace.back")}
          </Link>
        </Button>
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-xl font-semibold tracking-tight text-foreground">{brief.title}</h2>
          <RungBadge rung={rung} />
          <div className="ml-auto flex items-center gap-2">
            {/* l16-9 teaches the brief an envoy is applied from — the trust
                ladder and proof ledger below are that lesson's machinery. */}
            <CoachButton entityId={agent?.entityId} onChanged={changed} />
          </div>
        </div>
        <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground">{brief.narrative.whyAgentic}</p>
      </div>

      <section
        className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4"
        aria-label={t("detail.tryTitle")}
      >
        <h3 className="mr-2 text-sm font-semibold text-foreground">{t("detail.tryTitle")}</h3>
        {agent?.entityId && applied ? (
          <Button asChild size="sm" variant="outline">
            <Link href={`/agents?agentId=${encodeURIComponent(agent.entityId)}`}>
              <MessageSquare className="size-4" aria-hidden="true" />
              {t("detail.openPlayground", { agent: agent.name })}
            </Link>
          </Button>
        ) : (
          <span className="text-xs text-muted-foreground">{t("detail.tryNeedsApply")}</span>
        )}
        {workflow?.entityId && applied && (
          <Button size="sm" variant="outline" onClick={runWorkflow} disabled={runState.status === "starting"}>
            {runState.status === "starting" ? (
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            ) : (
              <Play className="size-4" aria-hidden="true" />
            )}
            {workflow.wf.sampleInput
              ? t("detail.runSample", { workflow: workflow.wf.name })
              : t("detail.runWorkflow", { workflow: workflow.wf.name })}
          </Button>
        )}
        {runState.status === "started" && runState.workflowId && (
          <Link
            className="text-sm text-primary underline"
            href={`/workflows/${encodeURIComponent(runState.workflowId)}/runs`}
          >
            {t("detail.runStarted")}
          </Link>
        )}
        {runState.status === "failed" && <span className="text-sm text-destructive">{t("detail.runFailed")}</span>}
      </section>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <EnvoyGraph brief={brief} readiness={readiness} selectedRef={selected} onSelect={setSelected} />
        <ReadinessRail rows={readiness} onSelect={setSelected} />
      </div>

      <div className="grid gap-6 xl:grid-cols-2">
        <ProofLedger
          ledger={ledger}
          loading={loading}
          error={ledgerError}
          onRetry={reload}
          onRunExperiment={experimentHref ? () => router.push(experimentHref) : undefined}
        />
        <TrustLadder briefId={id} rung={rung} events={ledger?.trustEvents ?? []} onChanged={changed} />
      </div>

      <RehearsalPanel
        briefId={id}
        brief={brief}
        agentId={agent?.entityId ?? null}
        canRun={hasPermission("MANAGE_AGENTS")}
      />

      {selected && <NarrativeCard brief={brief} refKey={selected} detail={detail} onClose={closeNarrative} />}
    </div>
  );
}
