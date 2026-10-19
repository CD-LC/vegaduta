"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Loader2, Sparkles } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { LearnLink } from "../../learn/LearnThisButton";
import { EmptyState } from "../../components/ax/page-kit";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import ProofLedger from "../../components/brief/ProofLedger";
import RungBadge from "../../components/brief/RungBadge";
import { deepLink } from "../../components/brief/logic";
import { primaryAgent, useBriefLedger, useEnvoyFor } from "../../components/brief/useBrief";
import { Select } from "../../components/ui/select";

/**
 * "Solutions" tab of /agents/quality: the proof ledger of an applied envoy,
 * next to the per-agent quality panels. The full envoy view (graph, trust
 * ladder, rehearsal) is one click away at /build/[id].
 */
export default function SolutionsTab() {
  const t = useTranslations("brief");
  const router = useRouter();
  const { status, envoys, selectedId, select, detail } = useEnvoyFor(true, null);
  const { ledger, error, loading, reload } = useBriefLedger(detail ? selectedId : null);
  const agent = detail ? primaryAgent(detail) : null;
  const experimentHref = agent?.entityId ? deepLink(`/agents?agentId=${encodeURIComponent(agent.entityId)}`, "workbench-versions") : null;

  if (status === "loading" || status === "idle") {
    return (
      <p className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 animate-spin" aria-hidden="true" />
        {t("coach.loading")}
      </p>
    );
  }
  if (status === "error") {
    return (
      <Alert variant="destructive">
        <AlertDescription>{t("coach.loadFailed")}</AlertDescription>
      </Alert>
    );
  }
  if (envoys.length === 0) {
    return (
      <EmptyState
        icon={Sparkles}
        title={t("solutions.emptyTitle")}
        description={t("solutions.emptyBody")}
        action={
          <Button asChild size="sm">
            <Link href="/build">{t("solutions.goBuild")}</Link>
          </Button>
        }
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-2 text-sm font-medium text-foreground">
          {t("solutions.pick")}
          <Select
            className="w-auto min-w-48 font-normal"
            aria-label={t("solutions.pick")}
            value={selectedId ?? ""}
            onValueChange={select}
            options={envoys.map((r) => ({ value: r.item.id, label: r.item.title }))}
          />
        </label>
        {detail && <RungBadge rung={detail.rung} />}
        <div className="ml-auto flex items-center gap-2">
          {/* l16-9 teaches the brief/trust-ladder machinery this ledger shows. */}
          <LearnLink lessonId="l16-9" fromPath="/agents/quality" label={t("solutions.learn")} />
          {selectedId && (
            <Button asChild variant="outline" size="sm">
              <Link href={`/build/${encodeURIComponent(selectedId)}`}>{t("solutions.openEnvoy")}</Link>
            </Button>
          )}
        </div>
      </div>
      <ProofLedger
        ledger={ledger}
        loading={loading}
        error={error}
        onRetry={reload}
        onRunExperiment={experimentHref ? () => router.push(experimentHref) : undefined}
      />
    </div>
  );
}
