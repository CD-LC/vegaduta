"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Activity, AlertTriangle, CheckCircle2, Gauge, HelpCircle, Info, RefreshCw, XCircle } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, timeAgo } from "../components/sections/common";
import { Button } from "../components/ui/button";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import {
  reliabilityApi,
  type DependenciesResponse,
  type DependencyEntry,
  type LatencySlo,
  type SloResponse,
} from "../lib/adminApi/reliability";
import {
  REFRESH_MS,
  WINDOWS_HOURS,
  budgetText,
  fmtBurn,
  fmtCount,
  fmtDuration,
  fmtLatency,
  fmtPct,
  groupByCategory,
  needsCatchUpRefresh,
  overallSummary,
  shouldPoll,
  statusMeta,
  toneClasses,
  verdictMeta,
  windowLabel,
  type IconName,
  type Tone,
} from "./format";
import { useTranslations } from "../../lib/i18n";

// Reliability & SLOs - what is up, and how well it has been working.
//
// Two honest rules shape the screen:
//   - a number the server could not back with data is "n/a" with a reason in
//     the notes, never 0 and never a guess;
//   - status is never colour alone: every tile carries an icon and a word.
// Dependencies are probed live on the server (and cached there for ~30 s);
// SLO figures come from sampled in-memory counters, so after a restart the
// window is shorter than asked and the screen says so. The page refreshes
// itself every 30 s while the tab is visible and catches up when it returns.

function StatusIcon({ icon, className }: { icon: IconName; className?: string }) {
  const props = { size: 16, className, "aria-hidden": true } as const;
  switch (icon) {
    case "check":
      return <CheckCircle2 {...props} />;
    case "alert":
      return <AlertTriangle {...props} />;
    case "x":
      return <XCircle {...props} />;
    default:
      return <HelpCircle {...props} />;
  }
}

/** Icon + word chip; the tone only tints it. */
function Chip({ tone, icon, label, testId }: { tone: Tone; icon: IconName; label: string; testId?: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium ${toneClasses(tone)}`}
      data-testid={testId}
    >
      <StatusIcon icon={icon} className="shrink-0" />
      {label}
    </span>
  );
}

function DependencyTile({ entry }: { entry: DependencyEntry }) {
  const meta = statusMeta(entry.status);
  return (
    <div
      className={`rounded-lg border p-3 ${toneClasses(meta.tone)}`}
      data-testid="dependency-tile"
      data-status={entry.status}
    >
      <div className="flex items-start justify-between gap-2">
        <span className="min-w-0 break-words text-sm font-medium text-foreground">{entry.name}</span>
        <Chip tone={meta.tone} icon={meta.icon} label={meta.label} />
      </div>
      <div className="mt-2 font-mono text-xs tabular-nums text-foreground">
        {entry.latencyMs == null ? "no probe run" : fmtLatency(entry.latencyMs)}
      </div>
      <p className="mt-1 text-xs leading-snug text-muted-foreground">{entry.detail}</p>
      <div className="mt-1 text-[11px] text-muted-foreground">checked {timeAgo(entry.lastCheckedAt)}</div>
    </div>
  );
}

function SloTile({
  testId,
  label,
  value,
  lines,
  verdict,
}: {
  testId: string;
  label: string;
  value: string;
  lines: string[];
  verdict?: string | null;
}) {
  const v = verdictMeta(verdict);
  return (
    <Card className="gap-0 py-0" data-testid={testId}>
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="text-xs text-muted-foreground">{label}</div>
          {v && <Chip tone={v.tone} icon={v.icon} label={v.label} />}
        </div>
        <div className="mt-1 font-mono text-2xl font-semibold tabular-nums text-foreground">{value}</div>
        {lines.map((l, i) => (
          <div key={i} className="mt-0.5 text-[11px] leading-snug text-muted-foreground">
            {l}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

function latencyLines(l: LatencySlo): string[] {
  const lines = [`p50 ${fmtLatency(l.p50Ms)} · p99 ${fmtLatency(l.p99Ms)} · ${fmtCount(l.count)} requests`];
  if (l.targetMs != null) {
    lines.push(`Objective: p95 at or under ${fmtLatency(l.targetMs)}`);
    if (l.slowShare != null) {
      lines.push(
        `${fmtPct(l.slowShare, 1)} slower than ${fmtLatency(l.slowShareBoundMs)} · burn ${fmtBurn(l.burnRate)}`
      );
    }
  } else {
    lines.push("No objective: this is the length of the whole answer");
  }
  return lines;
}

export default function ReliabilityPage() {
  const tHC = useTranslations("hcPlatformadminReliabilityPage");
  const [hours, setHours] = useState(24);
  const [deps, setDeps] = useState<DependenciesResponse | null>(null);
  const [depsError, setDepsError] = useState<string | null>(null);
  const [depsLoading, setDepsLoading] = useState(true);
  const [slo, setSlo] = useState<SloResponse | null>(null);
  const [sloError, setSloError] = useState<string | null>(null);
  const [sloLoading, setSloLoading] = useState(true);
  const [fetchedAt, setFetchedAt] = useState<number | null>(null);
  // Only the newest request of each kind may write state: a slow answer for an
  // old window must not overwrite the answer for the one now selected.
  const depsSeq = useRef(0);
  const sloSeq = useRef(0);
  const lastFetched = useRef<number | null>(null);

  const markFetched = useCallback(() => {
    const now = Date.now();
    lastFetched.current = now;
    setFetchedAt(now);
  }, []);

  const loadDeps = useCallback(async () => {
    const mine = ++depsSeq.current;
    setDepsLoading(true);
    try {
      const res = await reliabilityApi.dependencies();
      if (mine === depsSeq.current) {
        setDeps(res);
        setDepsError(null);
        markFetched();
      }
    } catch (e: any) {
      // Keep the previous reading on screen (its own "checked ... ago" tells the truth) and say the refresh failed.
      if (mine === depsSeq.current) setDepsError(e?.message || "Could not load the dependency readings.");
    } finally {
      if (mine === depsSeq.current) setDepsLoading(false);
    }
  }, [markFetched]);

  const loadSlo = useCallback(
    async (h: number) => {
      const mine = ++sloSeq.current;
      setSloLoading(true);
      try {
        const res = await reliabilityApi.slo(h);
        if (mine === sloSeq.current) {
          setSlo(res);
          setSloError(null);
          markFetched();
        }
      } catch (e: any) {
        if (mine === sloSeq.current) setSloError(e?.message || "Could not load the SLO figures.");
      } finally {
        if (mine === sloSeq.current) setSloLoading(false);
      }
    },
    [markFetched]
  );

  const refreshAll = useCallback(() => {
    void loadDeps();
    void loadSlo(hours);
  }, [loadDeps, loadSlo, hours]);

  useEffect(() => {
    void loadDeps();
  }, [loadDeps]);

  useEffect(() => {
    void loadSlo(hours);
  }, [hours, loadSlo]);

  // 30 s auto-refresh, paused while the tab is hidden, with an immediate catch-up on return.
  useEffect(() => {
    const timer = setInterval(() => {
      if (shouldPoll(document.visibilityState)) refreshAll();
    }, REFRESH_MS);
    const onVisible = () => {
      if (document.visibilityState === "visible" && needsCatchUpRefresh(lastFetched.current, Date.now())) {
        refreshAll();
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [refreshAll]);

  const overall = statusMeta(deps?.overall);
  const groups = deps ? groupByCategory(deps.dependencies) : [];
  const busy = depsLoading || sloLoading;
  const noteGroups: { title: string; notes: string[] }[] = [
    { title: "Dependencies", notes: deps?.notes ?? [] },
    { title: "Service-level objectives", notes: slo?.notes ?? [] },
  ].filter((g) => g.notes.length > 0);

  return (
    <AdminShell
      title={tHC("s16")}
      actions={
        <div className="flex items-center gap-2">
          <div className="flex overflow-hidden rounded-md border border-border" role="group" aria-label={tHC("s15")}>
            {WINDOWS_HOURS.map((h) => (
              <button
                key={h}
                type="button"
                aria-pressed={hours === h}
                onClick={() => setHours(h)}
                className={`px-2.5 py-1 text-xs font-medium transition-colors ${
                  hours === h
                    ? "bg-primary text-primary-foreground"
                    : "bg-background text-muted-foreground hover:bg-muted"
                }`}
              >
                {windowLabel(h)}
              </button>
            ))}
          </div>
          <Button variant="outline" size="sm" onClick={refreshAll} disabled={busy}>
            <RefreshCw size={14} className={busy ? "animate-spin" : undefined} /> Refresh
          </Button>
        </div>
      }
    >
      <PageHint>
        {tHC("s14")} {REFRESH_MS / 1000} {tHC("s13")}
        <span aria-live="polite">{fetchedAt ? `; last updated ${new Date(fetchedAt).toLocaleTimeString()}` : ""}</span>.
      </PageHint>

      {/* ------------------------------------------------------------ Dependencies */}
      <SectionCard
        title="Dependencies"
        subtitle="One row per dependency the platform leans on. Unknown means not configured here, not failing."
        icon={Activity}
      >
        {depsError && (
          <Alert variant="destructive" className="mb-4" data-testid="deps-error">
            <AlertDescription>
              {depsError}
              {deps ? " Showing the last reading below." : ""}{" "}
              <button type="button" className="underline" onClick={() => void loadDeps()}>
                {tHC("s9")}
              </button>
            </AlertDescription>
          </Alert>
        )}

        {depsLoading && !deps && (
          <div
            className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
            aria-busy="true"
            data-testid="deps-loading"
          >
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-28" />
            ))}
          </div>
        )}

        {deps && (
          <>
            <div
              className={`mb-4 flex flex-wrap items-center justify-between gap-2 rounded-lg border px-4 py-3 ${toneClasses(overall.tone)}`}
              data-testid="deps-overall"
              data-status={deps.overall}
            >
              <div className="flex items-center gap-2">
                <StatusIcon icon={overall.icon} />
                <span className="text-sm font-semibold">Overall: {overall.label}</span>
              </div>
              <span className="text-xs">{overallSummary(deps.dependencies)}</span>
            </div>
            <p className="mb-4 text-[11px] text-muted-foreground">
              Probed {timeAgo(deps.generatedAt)}
              {tHC("s12")} {deps.cacheTtlSeconds} s.
            </p>

            {deps.dependencies.length === 0 ? (
              <p className="text-sm text-muted-foreground" data-testid="deps-empty">
                {tHC("s11")}
              </p>
            ) : (
              groups.map((g) => (
                <div key={g.category} className="mb-4 last:mb-0">
                  <h3 className="mb-2 text-xs font-medium text-muted-foreground">{g.label}</h3>
                  <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {g.items.map((e) => (
                      <DependencyTile key={e.name} entry={e} />
                    ))}
                  </div>
                </div>
              ))
            )}
          </>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------------- SLOs */}
      <SectionCard
        title={tHC("s10")}
        subtitle={`Measured over the last ${windowLabel(hours)}. Burn rate 1.00x means errors arrive exactly as fast as the objective allows.`}
        icon={Gauge}
        delay={0.05}
      >
        {sloError && (
          <Alert variant="destructive" className="mb-4" data-testid="slo-error">
            <AlertDescription>
              {sloError}
              {slo ? " Showing the last reading below." : ""}{" "}
              <button type="button" className="underline" onClick={() => void loadSlo(hours)}>
                {tHC("s9")}
              </button>
            </AlertDescription>
          </Alert>
        )}

        {sloLoading && !slo && (
          <div
            className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
            aria-busy="true"
            data-testid="slo-loading"
          >
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-36" />
            ))}
          </div>
        )}

        {slo && (
          <>
            <div
              className="mb-4 flex flex-wrap items-center gap-2 text-xs text-muted-foreground"
              data-testid="slo-scope"
            >
              <span className="rounded-full border border-border px-2 py-0.5">
                {slo.scope === "platform"
                  ? `All replicas combined (${slo.replicas})`
                  : "This replica only: not every replica"}
              </span>
              <span className="rounded-full border border-border px-2 py-0.5">
                {tHC("s8")} {fmtDuration(slo.coveredSeconds)} of {windowLabel(slo.windowHours)}
              </span>
            </div>

            {slo.replicas === 0 ? (
              <p className="text-sm text-muted-foreground" data-testid="slo-empty">
                {tHC("s7")}
              </p>
            ) : (
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="slo-tiles">
                <SloTile
                  testId="tile-availability"
                  label="Availability"
                  value={fmtPct(slo.availability?.availability)}
                  verdict={slo.availability?.verdict}
                  lines={
                    slo.availability
                      ? [
                          `${fmtCount(slo.availability.requests)} requests, ${fmtCount(slo.availability.serverErrors)} server errors (5xx)`,
                          `Objective ${fmtPct(slo.objectives.availabilityTarget, 2)} · burn ${fmtBurn(slo.availability.burnRate)}`,
                          budgetText(slo.availability.budgetRemaining),
                        ]
                      : ["No requests were recorded in this window"]
                  }
                />
                {slo.latency.map((l) => (
                  <SloTile
                    key={l.key}
                    testId={`tile-latency-${l.key}`}
                    label={`${l.label}: p95`}
                    value={fmtLatency(l.p95Ms)}
                    verdict={l.verdict}
                    lines={latencyLines(l)}
                  />
                ))}
                <SloTile
                  testId="tile-jobs"
                  label={tHC("s6")}
                  value={fmtPct(slo.jobs?.successRate, 1)}
                  lines={
                    slo.jobs
                      ? [`${fmtCount(slo.jobs.succeeded)} succeeded, ${fmtCount(slo.jobs.failed)} failed`]
                      : ["No jobs finished on the replicas reporting"]
                  }
                />
              </div>
            )}

            {slo.latency.length === 0 && slo.replicas > 0 && (
              <p className="mt-3 text-xs text-muted-foreground" data-testid="slo-no-latency">
                {tHC("s5")}
              </p>
            )}

            {slo.replicas > 0 && (
              <div className="mt-5">
                <h3 className="mb-2 text-xs font-medium text-muted-foreground">{tHC("s4")}</h3>
                {slo.llmProviders.length === 0 ? (
                  <p className="text-sm text-muted-foreground" data-testid="providers-empty">
                    {tHC("s3")}
                  </p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[360px] text-sm" data-testid="providers-table">
                      <thead>
                        <tr className="border-b border-border text-start text-xs text-muted-foreground">
                          <th className="py-2 pe-3 font-medium">Provider</th>
                          <th className="py-2 pe-3 font-medium">Calls</th>
                          <th className="py-2 pe-3 font-medium">Errors</th>
                          <th className="py-2 font-medium">{tHC("s2")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {slo.llmProviders.map((p) => (
                          <tr key={p.provider} className="border-b border-border/50">
                            <td className="py-2 pe-3 font-medium">{p.provider}</td>
                            <td className="py-2 pe-3 font-mono tabular-nums">{fmtCount(p.calls)}</td>
                            <td className="py-2 pe-3 font-mono tabular-nums">{fmtCount(p.errors)}</td>
                            <td className="py-2 font-mono tabular-nums">{fmtPct(p.errorRate, 1)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------------ Notes */}
      <SectionCard
        title={tHC("s1")}
        subtitle="Every gap, estimate and limit of the readings above, in plain words."
        icon={Info}
        delay={0.1}
      >
        {noteGroups.length === 0 ? (
          <p className="text-sm text-muted-foreground" data-testid="notes-empty">
            {busy ? "Loading…" : "No caveats were reported."}
          </p>
        ) : (
          noteGroups.map((g) => (
            <div key={g.title} className="mb-4 last:mb-0">
              <h3 className="mb-1 text-xs font-medium text-foreground">{g.title}</h3>
              <ul
                className="max-w-3xl list-disc space-y-2 ps-5 text-sm leading-relaxed text-muted-foreground"
                data-testid="notes-list"
              >
                {g.notes.map((n, i) => (
                  <li key={i}>{n}</li>
                ))}
              </ul>
            </div>
          ))
        )}
      </SectionCard>
    </AdminShell>
  );
}
