"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { RefreshCw, Star, Info, Radio, Clock3, FlaskConical, CalendarDays } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, timeAgo } from "../components/sections/common";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import { northStarApi, type NorthStarResponse, type WeekPoint } from "../lib/adminApi/northStar";
import {
  HIDDEN_LABEL,
  cellText,
  chartGeometry,
  fmtCount,
  fmtMinutes,
  fmtPct,
  hasAnyActivity,
  latestCompleteWeek,
  linePath,
  weekBefore,
  weekLabel,
} from "./format";

// North Star - the platform owner's headline numbers.
//
// Every figure is a real aggregate from NorthStarController; nothing is
// estimated client-side. Two honesty rules shape the screen:
//   - a cell the server withheld (fewer than 5 contributing users) reads
//     "Hidden (<5 users)", never 0 - and a value that is simply not defined
//     (a rate over zero runs) reads "n/a", so the two can never be confused;
//   - a metric the platform cannot measure is not shown as a tile with a
//     made-up number: the server explains why in the notes list below.
// The charts are hand-rolled SVG on semantic tokens (same approach as the
// Demo Sandbox screen); no charting dependency.

const WINDOWS = [8, 12, 26, 52];

type Tone = "default" | "muted";

function Tile({
  label,
  value,
  sub,
  definition,
  tone = "default",
  testId,
}: {
  label: string;
  value: string;
  sub?: string;
  definition: string;
  tone?: Tone;
  testId: string;
}) {
  return (
    <Card className="gap-0 py-0" data-testid={testId}>
      <CardContent className="p-4">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div
          className={`mt-1 font-mono text-2xl font-semibold tabular-nums ${
            tone === "muted" ? "text-muted-foreground" : "text-primary"
          }`}
        >
          {value}
        </div>
        {sub && <div className="mt-0.5 text-[11px] text-muted-foreground">{sub}</div>}
        <div className="mt-2 text-[11px] leading-snug text-muted-foreground">{definition}</div>
      </CardContent>
    </Card>
  );
}

function deltaText(current: number | null | undefined, previous: number | null | undefined): string | undefined {
  if (current == null || previous == null) return undefined;
  const d = current - previous;
  if (d === 0) return "unchanged vs previous week";
  return `${d > 0 ? "+" : ""}${fmtCount(d)} vs previous week`;
}

function MiniChart({
  title,
  weeks,
  pick,
  kind,
  fixedMax,
  fmt,
  testId,
}: {
  title: string;
  weeks: WeekPoint[];
  pick: (w: WeekPoint) => number | null;
  kind: "bar" | "line";
  fixedMax?: number;
  fmt: (n: number) => string;
  testId: string;
}) {
  const width = 360;
  const height = 120;
  const padTop = 14;
  const padBottom = 6;
  const values = useMemo(() => weeks.map(pick), [weeks, pick]);
  const geo = useMemo(
    () => chartGeometry(values, { width, height, padTop, padBottom, fixedMax }),
    [values, fixedMax]
  );
  const barW = Math.max(3, Math.min(18, geo.slot * 0.6));
  const baseline = height - padBottom;
  const hiddenCount = values.filter((v) => v == null).length;
  const summary = `${title}, ${weeks.length} weeks, ${hiddenCount} without a published value`;

  return (
    <div data-testid={testId}>
      <div className="mb-1 flex items-baseline justify-between gap-2">
        <h3 className="text-xs font-medium text-foreground">{title}</h3>
        <span className="font-mono text-[11px] text-muted-foreground">
          {fixedMax != null ? `scale 0-${fmt(fixedMax)}` : `peak ${fmt(geo.max)}`}
        </span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} className="h-[120px] w-full" role="img" aria-label={summary}>
        <line x1={0} y1={baseline} x2={width} y2={baseline} className="stroke-border" strokeWidth={1} />
        {kind === "line" && <path d={linePath(geo.points)} className="fill-none stroke-primary" strokeWidth={2} />}
        {geo.points.map((p) => {
          const w = weeks[p.index];
          const text = p.value == null ? cellText(null, w.suppressed, fmt) : fmt(p.value);
          const label = `${weekLabel(w.weekStart)}${w.partial ? " (in progress)" : ""}: ${text}`;
          return (
            <g key={w.weekStart}>
              <title>{label}</title>
              <rect x={p.x - geo.slot / 2} y={padTop} width={geo.slot} height={baseline - padTop} fill="transparent" />
              {kind === "bar" && p.value != null && (
                <rect
                  x={p.x - barW / 2}
                  y={p.y}
                  width={barW}
                  height={Math.max(p.h, p.value > 0 ? 1 : 0)}
                  rx={1}
                  className="fill-primary"
                  opacity={w.partial ? 0.45 : 1}
                />
              )}
              {kind === "bar" && p.value == null && (
                <rect
                  x={p.x - barW / 2}
                  y={baseline - 10}
                  width={barW}
                  height={10}
                  rx={1}
                  className="fill-transparent stroke-muted-foreground"
                  strokeDasharray="2 2"
                />
              )}
              {kind === "line" && p.value != null && (
                <circle
                  cx={p.x}
                  cy={p.y}
                  r={3}
                  className={w.partial ? "fill-background stroke-primary" : "fill-primary stroke-primary"}
                  strokeWidth={1.5}
                />
              )}
            </g>
          );
        })}
      </svg>
      <div className="flex justify-between font-mono text-[11px] text-muted-foreground">
        <span>{weekLabel(weeks[0]?.weekStart ?? "")}</span>
        <span>{weekLabel(weeks[weeks.length - 1]?.weekStart ?? "")}</span>
      </div>
    </div>
  );
}

const pickWau = (w: WeekPoint) => w.wau;
const pickRuns = (w: WeekPoint) => w.runs;
const pickSuccess = (w: WeekPoint) => w.successRate;
const pickShare = (w: WeekPoint) => w.scheduledShare;

export default function NorthStarPage() {
  const [weeks, setWeeks] = useState(12);
  const [data, setData] = useState<NorthStarResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // Only the newest request may write state: switching the window while a
  // slow sweep is still running must not let the old answer overwrite it.
  const seq = useRef(0);

  const load = useCallback(async (w: number) => {
    const mine = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const res = await northStarApi.get(w);
      if (mine === seq.current) setData(res);
    } catch (e: any) {
      if (mine === seq.current) {
        setError(e?.message || "Could not load the north-star metrics.");
        setData(null);
      }
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(weeks);
  }, [weeks, load]);

  const headline = useMemo(() => (data ? latestCompleteWeek(data.weeks) : null), [data]);
  const previous = useMemo(() => (data ? weekBefore(data.weeks, headline) : null), [data, headline]);
  const headlineLabel = headline ? `Week of ${weekLabel(headline.weekStart)}` : undefined;
  const active = hasAnyActivity(data);

  return (
    <AdminShell
      title="North Star"
      actions={
        <div className="flex items-center gap-2">
          <div className="flex overflow-hidden rounded-md border border-border" role="group" aria-label="Window">
            {WINDOWS.map((w) => (
              <button
                key={w}
                type="button"
                aria-pressed={weeks === w}
                onClick={() => setWeeks(w)}
                className={`px-2.5 py-1 text-xs font-medium transition-colors ${
                  weeks === w
                    ? "bg-primary text-primary-foreground"
                    : "bg-background text-muted-foreground hover:bg-muted"
                }`}
              >
                {w}w
              </button>
            ))}
          </div>
          <Button variant="outline" size="sm" onClick={() => void load(weeks)} disabled={loading}>
            <RefreshCw size={14} className={loading ? "animate-spin" : undefined} /> Refresh
          </Button>
        </div>
      }
    >
      <PageHint>
        The numbers that say whether people actually get agents to do work. Everything is an aggregate across every
        active tenant &mdash; no user, tenant or agent is ever named &mdash; and any cell fewer than 5 people
        contributed to is hidden, not shown as zero. The server caches the sweep for an hour
        {data ? `; this one was generated ${timeAgo(data.generatedAt)}` : ""}.
      </PageHint>

      {error && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>
            {error}{" "}
            <button type="button" className="underline" onClick={() => void load(weeks)}>
              Try again
            </button>
          </AlertDescription>
        </Alert>
      )}

      {loading && !data && (
        <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" aria-busy="true" data-testid="north-star-loading">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-36" />
          ))}
        </div>
      )}

      {data && (
        <>
          {data.coverage.truncated && (
            <Alert variant="warning" className="mb-5">
              <AlertDescription>
                Partial sweep: {data.coverage.tenantsScanned} of {data.coverage.tenantsTotal} active tenants were
                scanned. Every figure below covers only those tenants.
              </AlertDescription>
            </Alert>
          )}

          {!active && (
            <div
              className="mb-5 rounded-lg border border-dashed border-border p-6 text-center"
              data-testid="north-star-empty"
            >
              <div className="text-sm font-medium">No agent runs recorded in this window yet</div>
              <p className="mx-auto mt-1 max-w-xl text-xs text-muted-foreground">
                Nothing has completed an agent job or Quick Task in the last {weeks} weeks across{" "}
                {data.coverage.tenantsScanned} scanned tenants. The notes below say which metrics cannot be measured
                at all.
              </p>
            </div>
          )}

          {active && (
            <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="north-star-tiles">
              <Tile
                testId="tile-wau"
                label="Weekly active users"
                value={cellText(headline?.wau, headline?.suppressed ?? false)}
                sub={[headlineLabel, deltaText(headline?.wau, previous?.wau)].filter(Boolean).join(" · ")}
                definition="Weekly active = signed-in users with at least one completed agent run in the ISO week. Counts only runs a person started (an API-submitted agent job or a Quick Task); scheduled and event-triggered runs never count."
              />
              <Tile
                testId="tile-runs"
                label="Agent runs"
                value={cellText(headline?.runs, headline?.suppressed ?? false)}
                sub={[headlineLabel, deltaText(headline?.runs, previous?.runs)].filter(Boolean).join(" · ")}
                definition="Finished runs of every kind (succeeded or failed): agent jobs and Quick Task runs. Interactive chat turns are not recorded against a user, so they are not included."
              />
              <Tile
                testId="tile-success"
                label="Run success rate"
                value={cellText(headline?.successRate, headline?.suppressed ?? false, fmtPct)}
                sub={headlineLabel}
                definition="Succeeded ÷ (succeeded + failed). Cancelled, queued, running and awaiting-approval runs are left out of both sides."
              />
              <Tile
                testId="tile-share"
                label="Runs without a human present"
                value={cellText(headline?.scheduledShare, headline?.suppressed ?? false, fmtPct)}
                sub={headlineLabel}
                definition="Share of runs that were scheduled, event-triggered or delivered over WhatsApp, SMS or a call, rather than started interactively."
              />
              <Tile
                testId="tile-ttfr"
                label="Median time to first run"
                value={fmtMinutes(data.timeToFirstRunMedianMinutes)}
                sub={
                  data.timeToFirstRunMedianMinutes == null
                    ? "n/a: none yet, or fewer than 5 users (see notes)"
                    : `Users who signed up in the last ${weeks} weeks`
                }
                tone={data.timeToFirstRunMedianMinutes == null ? "muted" : "default"}
                definition="Median time from account creation to the start of a user's first successful run, over users who signed up in the window and have reached one. Needs 5 or more such users."
              />
              <Tile
                testId="tile-passk"
                label="Average pass^k"
                value={cellText(data.passK?.average, data.passK != null, fmtPct)}
                sub={data.passK?.cases != null ? `${fmtCount(data.passK.cases)} eval cases` : undefined}
                tone={data.passK?.average == null ? "muted" : "default"}
                definition="Share of eval cases where all k repetitions passed (k of 2 or more), over eval runs in the window. Measures reliability, not best-case accuracy."
              />
            </div>
          )}

          {active && (
            <SectionCard
              title="Weekly trend"
              subtitle="One bar or point per ISO week (Monday start, UTC). The faded last week is still in progress. A dashed bar or a break in the line is a hidden week (fewer than 5 users), not zero."
              icon={Star}
            >
              <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                <MiniChart testId="chart-wau" title="Weekly active users" weeks={data.weeks} pick={pickWau} kind="bar" fmt={fmtCount} />
                <MiniChart testId="chart-runs" title="Agent runs" weeks={data.weeks} pick={pickRuns} kind="bar" fmt={fmtCount} />
                <MiniChart
                  testId="chart-success"
                  title="Run success rate"
                  weeks={data.weeks}
                  pick={pickSuccess}
                  kind="line"
                  fixedMax={1}
                  fmt={fmtPct}
                />
                <MiniChart
                  testId="chart-share"
                  title="Runs without a human present"
                  weeks={data.weeks}
                  pick={pickShare}
                  kind="line"
                  fixedMax={1}
                  fmt={fmtPct}
                />
              </div>
            </SectionCard>
          )}

          {active && (
            <SectionCard title="Weekly detail" subtitle="The exact figures behind the charts." icon={CalendarDays} delay={0.05}>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[720px] text-sm" data-testid="weekly-table">
                  <thead>
                    <tr className="border-b border-border text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3 font-medium">Week of</th>
                      <th className="py-2 pr-3 font-medium">Active users</th>
                      <th className="py-2 pr-3 font-medium">Runs</th>
                      <th className="py-2 pr-3 font-medium">Success</th>
                      <th className="py-2 pr-3 font-medium">No human present</th>
                      <th className="py-2 pr-3 font-medium">Interactive</th>
                      <th className="py-2 pr-3 font-medium">Scheduled</th>
                      <th className="py-2 pr-3 font-medium">Triggered</th>
                      <th className="py-2 font-medium">Channel</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...data.weeks].reverse().map((w) => (
                      <tr key={w.weekStart} className="border-b border-border/50">
                        <td className="py-2 pr-3 font-medium">
                          {weekLabel(w.weekStart)}
                          {w.partial && (
                            <Badge variant="outline" className="ml-2 text-[10px]">
                              in progress
                            </Badge>
                          )}
                        </td>
                        <td className="py-2 pr-3 font-mono tabular-nums">{cellText(w.wau, w.suppressed)}</td>
                        <td className="py-2 pr-3 font-mono tabular-nums">{cellText(w.runs, w.suppressed)}</td>
                        <td className="py-2 pr-3 font-mono tabular-nums">{cellText(w.successRate, w.suppressed, fmtPct)}</td>
                        <td className="py-2 pr-3 font-mono tabular-nums">{cellText(w.scheduledShare, w.suppressed, fmtPct)}</td>
                        {(["INTERACTIVE", "SCHEDULED", "TRIGGERED", "CHANNEL"] as const).map((k) => (
                          <td key={k} className="py-2 pr-3 font-mono tabular-nums last:pr-0">
                            {cellText(w.runsByKind ? w.runsByKind[k] : null, w.suppressed)}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          <SectionCard
            title="Time to first run, by signup week"
            subtitle="Users who signed up in each week, how many have since completed a first successful run, and the median time it took."
            icon={Clock3}
            delay={0.1}
          >
            <div className="overflow-x-auto">
              <table className="w-full min-w-[480px] text-sm" data-testid="cohort-table">
                <thead>
                  <tr className="border-b border-border text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3 font-medium">Signup week</th>
                    <th className="py-2 pr-3 font-medium">Signed up</th>
                    <th className="py-2 pr-3 font-medium">Reached a first run</th>
                    <th className="py-2 font-medium">Median time</th>
                  </tr>
                </thead>
                <tbody>
                  {[...data.timeToFirstRunCohorts].reverse().map((c) => (
                    <tr key={c.weekStart} className="border-b border-border/50">
                      <td className="py-2 pr-3 font-medium">{weekLabel(c.weekStart)}</td>
                      <td className="py-2 pr-3 font-mono tabular-nums">{cellText(c.signups, c.suppressed)}</td>
                      <td className="py-2 pr-3 font-mono tabular-nums">{cellText(c.activated, c.suppressed)}</td>
                      <td className="py-2 font-mono tabular-nums">{cellText(c.medianMinutes, c.suppressed, fmtMinutes)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          <SectionCard
            title="Channel-delivered runs"
            subtitle="Quick Task runs whose result is delivered outside the app, by channel, over the whole window."
            icon={Radio}
            delay={0.15}
          >
            {data.channels.length === 0 ? (
              <p className="text-sm text-muted-foreground" data-testid="channels-empty">
                No channel-delivered runs in this window.
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[320px] text-sm" data-testid="channel-table">
                  <thead>
                    <tr className="border-b border-border text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3 font-medium">Channel</th>
                      <th className="py-2 pr-3 font-medium">Runs</th>
                      <th className="py-2 font-medium">Success</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.channels.map((c) => (
                      <tr key={c.channel} className="border-b border-border/50">
                        <td className="py-2 pr-3 font-medium">{c.channel}</td>
                        <td className="py-2 pr-3 font-mono tabular-nums">{cellText(c.runs, c.suppressed)}</td>
                        <td className="py-2 font-mono tabular-nums">{cellText(c.successRate, c.suppressed, fmtPct)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {data.passK && (
            <SectionCard
              title="Eval reliability (pass^k)"
              subtitle="Eval cases replayed k times; pass^k is the share where every one of the k repetitions passed."
              icon={FlaskConical}
              delay={0.2}
            >
              <div className="overflow-x-auto">
                <table className="w-full min-w-[320px] text-sm" data-testid="passk-table">
                  <thead>
                    <tr className="border-b border-border text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3 font-medium">Repetitions (k)</th>
                      <th className="py-2 pr-3 font-medium">Cases</th>
                      <th className="py-2 font-medium">pass^k</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.passK.byK.map((g) => (
                      <tr key={g.k} className="border-b border-border/50">
                        <td className="py-2 pr-3 font-medium">{g.k}</td>
                        <td className="py-2 pr-3 font-mono tabular-nums">{cellText(g.cases, g.suppressed)}</td>
                        <td className="py-2 font-mono tabular-nums">{cellText(g.passPowK, g.suppressed, fmtPct)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          <SectionCard
            title="What this screen cannot tell you"
            subtitle={`Coverage: ${data.coverage.tenantsScanned} of ${data.coverage.tenantsTotal} active tenants scanned. "${HIDDEN_LABEL}" means withheld, not zero.`}
            icon={Info}
            delay={0.25}
          >
            {data.notes.length === 0 ? (
              <p className="text-sm text-muted-foreground">No caveats for this window.</p>
            ) : (
              <ul className="max-w-3xl list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground" data-testid="notes-list">
                {data.notes.map((n, i) => (
                  <li key={i}>{n}</li>
                ))}
              </ul>
            )}
          </SectionCard>
        </>
      )}
    </AdminShell>
  );
}
