"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Download, Search, ShieldAlert, ShieldCheck } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Badge } from "../ui/badge";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Skeleton } from "../ui/skeleton";
import { EmptyState, StatCard } from "../ax/page-kit";
import { useLocale, useTranslations } from "../../lib/i18n";
import { getTurnJournal, type TurnJournalView } from "../../lib/api/turns";
import { triggerBrowserDownload } from "../../lib/api/core";
import { errorMessage } from "../../lib/errors";
import { turnTotals, verifyChain, type ChainCheck } from "./journalChain";
import { buildTurnFixture, fixtureFilename, serializeFixture } from "./turnFixture";
import TurnTimeline from "./TurnTimeline";
import CopyTurnId from "./CopyTurnId";
import { buildTimeline, formatCostMinor, timelineStats } from "./timelineModel";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Load =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; view: TurnJournalView }
  | { kind: "notFound" }
  | { kind: "forbidden" }
  | { kind: "error"; message: string };

export default function TurnInspector({ initialAgentId = "", initialTurnId = "" }: { initialAgentId?: string; initialTurnId?: string }) {
  const t = useTranslations("turnInspector");
  const locale = useLocale();
  const [agentId, setAgentId] = useState(initialAgentId);
  const [turnId, setTurnId] = useState(initialTurnId);
  const [load, setLoad] = useState<Load>({ kind: "idle" });
  const [idError, setIdError] = useState(false);
  const [clientCheck, setClientCheck] = useState<ChainCheck | null>(null);
  const [clientDone, setClientDone] = useState(false);
  const [exported, setExported] = useState(false);

  const inspect = useCallback(
    async (a: string, tu: string) => {
      const aid = a.trim();
      const tid = tu.trim();
      if (!UUID.test(aid) || !UUID.test(tid)) {
        setIdError(true);
        return;
      }
      setIdError(false);
      setExported(false);
      setLoad({ kind: "loading" });
      try {
        setLoad({ kind: "ready", view: await getTurnJournal(aid, tid) });
      } catch (e) {
        const status = (e as { status?: number }).status;
        if (status === 404 || status === 405) setLoad({ kind: "notFound" });
        else if (status === 403) setLoad({ kind: "forbidden" });
        else setLoad({ kind: "error", message: errorMessage(e) });
      }
    },
    []
  );

  useEffect(() => {
    if (UUID.test(initialAgentId) && UUID.test(initialTurnId)) void inspect(initialAgentId, initialTurnId);
    // Deep link only: run once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const view = load.kind === "ready" ? load.view : null;
  useEffect(() => {
    setClientCheck(null);
    setClientDone(false);
    if (!view) return undefined;
    let cancelled = false;
    void verifyChain(view.events).then((r) => {
      if (cancelled) return;
      setClientCheck(r);
      setClientDone(true);
    });
    return () => {
      cancelled = true;
    };
  }, [view]);

  const totals = useMemo(() => (view ? turnTotals(view.events) : null), [view]);
  const stats = useMemo(() => (view ? timelineStats(buildTimeline(view.events)) : null), [view]);

  const exportFixture = () => {
    if (!view) return;
    const body = serializeFixture(buildTurnFixture(view, clientCheck));
    triggerBrowserDownload(new Blob([body], { type: "application/json" }), fixtureFilename(view));
    setExported(true);
  };

  // The browser's verdict wins when it can compute one; a disagreement is flagged, never hidden.
  const intact = view ? (clientCheck ? clientCheck.intact : view.chainIntact) : false;
  const disagree = !!view && !!clientCheck && clientCheck.intact !== view.chainIntact;
  const brokenAt = view ? (clientCheck && !clientCheck.intact ? clientCheck.brokenAtSeq : view.chainIntact ? null : view.brokenAtSeq) : null;

  return (
    <div className="space-y-6">
      <form
        className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end"
        onSubmit={(ev) => {
          ev.preventDefault();
          void inspect(agentId, turnId);
        }}
      >
        <div className="space-y-1.5">
          <Label htmlFor="turn-agent-id">{t("agentIdLabel")}</Label>
          <Input id="turn-agent-id" value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder={t("agentIdPlaceholder")} spellCheck={false} autoComplete="off" />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="turn-turn-id">{t("turnIdLabel")}</Label>
          <Input id="turn-turn-id" value={turnId} onChange={(e) => setTurnId(e.target.value)} placeholder={t("turnIdPlaceholder")} spellCheck={false} autoComplete="off" />
        </div>
        <Button type="submit" disabled={load.kind === "loading"}>
          <Search size={14} aria-hidden="true" /> {t("inspect")}
        </Button>
      </form>

      {idError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{t("invalidIds")}</AlertDescription>
        </Alert>
      )}

      {load.kind === "idle" && !idError && <EmptyState icon={Search} title={t("idleTitle")} description={t("idleBody")} />}

      {load.kind === "loading" && (
        <div className="space-y-3" aria-busy="true" aria-label={t("loading")}>
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      )}

      {load.kind === "notFound" && (
        <div className="space-y-4">
          <EmptyState icon={ShieldAlert} title={t("notFoundTitle")} description={t("notFoundBody")} />
          <Alert>
            <AlertTitle>{t("enableTitle")}</AlertTitle>
            <AlertDescription>{t("enableBody")}</AlertDescription>
          </Alert>
        </div>
      )}

      {load.kind === "forbidden" && (
        <Alert variant="destructive" role="alert">
          <AlertTitle>{t("forbiddenTitle")}</AlertTitle>
          <AlertDescription>{t("forbiddenBody")}</AlertDescription>
        </Alert>
      )}

      {load.kind === "error" && (
        <Alert variant="destructive" role="alert">
          <AlertTitle>{t("errorTitle")}</AlertTitle>
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            <span>{load.message}</span>
            <Button type="button" size="sm" variant="outline" onClick={() => void inspect(agentId, turnId)}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {view && totals && stats && (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-sm font-medium text-foreground">{t("turnIdLabel")}</span>
            <CopyTurnId turnId={view.turnId} />
          </div>

          <section aria-label={t("chainTitle")} className="rounded-lg border border-border bg-card p-4">
            <div className="flex flex-wrap items-center gap-3">
              <Badge variant={intact && !disagree ? "default" : "destructive"} data-testid="chain-badge">
                {intact ? <ShieldCheck aria-hidden="true" /> : <ShieldAlert aria-hidden="true" />}
                {intact ? t("chainIntact") : t("chainBroken", { seq: brokenAt ?? view.brokenAtSeq })}
              </Badge>
              <span className="text-sm text-muted-foreground">{t("serverVerdict", { verdict: view.verification })}</span>
            </div>
            <p className="mt-2 text-sm text-muted-foreground" data-testid="client-verdict">
              {!clientDone
                ? t("chainVerifyingClient")
                : clientCheck === null
                  ? t("clientUnavailable")
                  : clientCheck.intact
                    ? t("clientVerified", { count: view.events.length })
                    : t("clientFailed", { seq: clientCheck.brokenAtSeq, reason: clientCheck.reason })}
            </p>
            {disagree && (
              <Alert variant="destructive" role="alert" className="mt-3">
                <AlertDescription>{t("disagree")}</AlertDescription>
              </Alert>
            )}
          </section>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label={t("statEvents")} value={String(view.events.length)} />
            <StatCard label={t("statWall")} value={totals.wallMillis === null ? "-" : `${totals.wallMillis} ms`} />
            <StatCard label={t("statTools")} value={`${totals.toolMillis} ms`} sub={`${totals.toolCalls}`} />
            <StatCard
              label={t("statCost")}
              value={totals.costMinor === null ? t("costNotRecorded") : formatCostMinor(totals.costMinor, locale)}
              sub={`${t("statModelCalls")}: ${totals.modelCalls}`}
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label={t("tlStatFinish")}
              value={stats.finishStatus ?? t("tlStillOpen")}
              sub={stats.problems > 0 ? t("tlProblemCount", { count: stats.problems }) : undefined}
            />
            <StatCard label={t("tlStatRetries")} value={String(stats.retries)} sub={`${t("tlStatContinuations")}: ${stats.continuations}`} />
            <StatCard label={t("tlStatApprovals")} value={String(stats.approvals)} />
            <StatCard
              label={t("tlStatTokens")}
              value={stats.tokensIn === null && stats.tokensOut === null ? "-" : `${stats.tokensIn ?? "-"} / ${stats.tokensOut ?? "-"}`}
              sub={stats.tokensIn === null && stats.tokensOut === null ? t("tlTokensNotRecorded") : t("tlTokensInOut")}
            />
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="max-w-prose text-xs text-muted-foreground">{t("redactedNote")}</p>
            <div className="flex flex-col items-end gap-1">
              <Button type="button" variant="outline" onClick={exportFixture}>
                <Download size={14} aria-hidden="true" /> {t("export")}
              </Button>
              <span className="text-xs text-muted-foreground" role="status">
                {exported ? t("exported") : t("exportHint")}
              </span>
            </div>
          </div>

          <TurnTimeline events={view.events} brokenAtSeq={brokenAt} />
        </>
      )}
    </div>
  );
}
