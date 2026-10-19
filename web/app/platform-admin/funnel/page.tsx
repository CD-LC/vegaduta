"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { RefreshCw, TrendingDown, Eye, MousePointerClick, Info, Archive } from "lucide-react";
import { api } from "../lib/api";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard } from "../components/sections/common";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { SECTIONS, UNATTRIBUTED_CLICKS, RETIRED_EVENTS } from "./sections";

// Landing funnel - the read-out for LandingEventController's counters.
//
// Two things about this screen's history are worth knowing before trusting it.
// First: the beacon endpoint shipped 2026-07-28 and NOTHING in the repo called
// its read side until this page existed, so six weeks of counters accumulated
// unseen. Second: until 2026-09-11 every event except landing_view was a
// CLICK, which meant a band with few clicks was indistinguishable from a band
// nobody ever scrolled to. The `sec_*` reach events fix exactly that, and the
// waterfall below is the reason they exist.
//
// Honesty rules inherited from the Demo Sandbox screen, because every number
// here is a real aggregate and none of it is seeded:
//   - a rate with a zero denominator renders "n/a", never "0%";
//   - a band with no CTA renders an em dash for engage, not 0%;
//   - a counter that CANNOT increment (its component is unmounted) is listed
//     as retired, never rendered as a 0 next to live bands.
// The bars are hand-rolled divs; no charting dependency is worth adding.

const WINDOWS = [7, 14, 30, 90];

type Totals = Record<string, number>;

function pct(n: number, d: number) {
  if (!d) return null;
  return n / d;
}

function fmtPct(v: number | null) {
  if (v === null || !Number.isFinite(v)) return "n/a";
  return `${(v * 100).toFixed(1)}%`;
}

function fmtInt(v: number | undefined) {
  return Number(v ?? 0).toLocaleString();
}

export default function FunnelPage() {
  const [days, setDays] = useState(14);
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async (window: number) => {
    setLoading(true);
    setError(null);
    try {
      setData(await api.getLandingMetrics(window));
    } catch (e: any) {
      setError(e?.message || "Could not load landing metrics.");
      setData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(days);
  }, [days, load]);

  // Sum every day in the window into one total per event. The endpoint returns
  // oldest-day-first with a zero for days it has no key for, so a plain sum is
  // correct and needs no gap handling.
  const totals: Totals = useMemo(() => {
    const out: Totals = {};
    for (const day of data?.days ?? []) {
      for (const [event, count] of Object.entries(day.events ?? {})) {
        out[event] = (out[event] ?? 0) + Number(count ?? 0);
      }
    }
    return out;
  }, [data]);

  const views = totals.landing_view ?? 0;

  const rows = useMemo(() => {
    let previousReach: number | null = null;
    return SECTIONS.map((section) => {
      const reach = totals[`sec_${section.key}`] ?? 0;
      const clicks = section.clicks.reduce((sum, e) => sum + (totals[e] ?? 0), 0);
      const lost = previousReach === null ? 0 : Math.max(0, previousReach - reach);
      const lostPct = previousReach === null ? null : pct(lost, views);
      previousReach = reach;
      return {
        section,
        reach,
        reachPct: pct(reach, views),
        clicks,
        engagePct: section.clicks.length ? pct(clicks, reach) : null,
        hasCta: section.clicks.length > 0,
        lost,
        lostPct,
      };
    });
  }, [totals, views]);

  const reachTotal = rows.reduce((sum, r) => sum + r.reach, 0);

  // The single most important guard on this screen. `sec_*` counters only
  // start moving once the instrumented web build is actually DEPLOYED. Until
  // then every reach reads 0 - which, rendered as a waterfall, looks exactly
  // like "100% of visitors bounce off the hero": the most alarming possible
  // chart, and completely false. Merged is not deployed; say so plainly.
  const awaitingDeploy = views > 0 && reachTotal === 0;

  // Biggest single drop between consecutive bands, ignoring the first row
  // (which has no predecessor) - the honest answer to "where do we lose them".
  const biggestDrop = useMemo(() => {
    if (awaitingDeploy || !views) return null;
    let worst: (typeof rows)[number] | null = null;
    for (const r of rows) {
      if (r.lostPct === null) continue;
      if (!worst || r.lost > worst.lost) worst = r;
    }
    return worst && worst.lost > 0 ? worst : null;
  }, [rows, views, awaitingDeploy]);

  const biggestDropPrevLabel = biggestDrop
    ? (SECTIONS[SECTIONS.findIndex((s) => s.key === biggestDrop.section.key) - 1]?.label ?? "the top")
    : null;

  return (
    <AdminShell
      title="Landing Funnel"
      actions={
        <div className="flex items-center gap-2">
          <div className="flex overflow-hidden rounded-md border border-border">
            {WINDOWS.map((w) => (
              <button
                key={w}
                type="button"
                aria-pressed={days === w}
                onClick={() => setDays(w)}
                className={`px-2.5 py-1 text-xs font-medium transition-colors ${
                  days === w
                    ? "bg-primary text-primary-foreground"
                    : "bg-background text-muted-foreground hover:bg-muted"
                }`}
              >
                {w}d
              </button>
            ))}
          </div>
          <Button variant="outline" size="sm" onClick={() => void load(days)} disabled={loading}>
            <RefreshCw size={14} className={loading ? "animate-spin" : undefined} /> Refresh
          </Button>
        </div>
      }
    >
      <PageHint>
        Where attention actually stops on the public landing page. Every figure is a real Redis counter from{" "}
        <code className="font-mono text-xs">LandingEventController</code> &mdash; no sampling, no estimation, no
        third-party script, and nothing stored about any visitor beyond <code className="font-mono text-xs">+1</code> on
        an event name. <strong>Reach</strong> means a visitor scrolled to that band and held it on screen for a
        continuous second; <strong>engage</strong> is that band&rsquo;s own CTA clicks divided by its reach &mdash; so
        it measures the band on the people who actually saw it, which a raw click count never could.
      </PageHint>

      {error && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {awaitingDeploy && (
        <Alert className="mb-5">
          <AlertDescription>
            <strong>No section-reach data yet &mdash; this is not a 100% bounce rate.</strong> There are {fmtInt(views)}{" "}
            landing views in this window but zero <code className="font-mono text-xs">sec_*</code> events, which means
            the instrumented web build has not reached this environment yet. Merged is not deployed. The waterfall below
            will stay empty, and must not be read as visitor behaviour, until a web image containing{" "}
            <code className="font-mono text-xs">SectionReach.tsx</code> is live and has taken traffic.
          </AlertDescription>
        </Alert>
      )}

      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">Landing views</div>
            <div className="mt-1 font-mono text-2xl font-semibold tabular-nums text-primary">{fmtInt(views)}</div>
            <div className="mt-1 text-[11px] leading-snug text-muted-foreground">
              The denominator for every rate here. Counted once per <em>browser session</em>, not per person &mdash; one
              visitor on a phone and a laptop is two. There is no visitor id in this system by design, so this is the
              closest honest proxy for &ldquo;unique visitors&rdquo; and should not be quoted as anything stronger.
            </div>
          </CardContent>
        </Card>
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">Reached pricing</div>
            <div className="mt-1 font-mono text-2xl font-semibold tabular-nums">
              {awaitingDeploy ? "n/a" : fmtPct(pct(totals.sec_pricing ?? 0, views))}
            </div>
            <div className="mt-1 text-[11px] leading-snug text-muted-foreground">
              Share of visitors who got far enough down the page to see what it costs.
            </div>
          </CardContent>
        </Card>
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">Reached the closing CTA</div>
            <div className="mt-1 font-mono text-2xl font-semibold tabular-nums">
              {awaitingDeploy ? "n/a" : fmtPct(pct(totals.sec_closing ?? 0, views))}
            </div>
            <div className="mt-1 text-[11px] leading-snug text-muted-foreground">
              Share who read the whole page. The final ask can only work on these.
            </div>
          </CardContent>
        </Card>
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">Biggest single drop</div>
            <div className="mt-1 font-mono text-2xl font-semibold tabular-nums text-destructive">
              {biggestDrop ? fmtPct(biggestDrop.lostPct) : "n/a"}
            </div>
            <div className="mt-1 text-[11px] leading-snug text-muted-foreground">
              {biggestDrop
                ? `of all visitors stop between ${biggestDropPrevLabel} and ${biggestDrop.section.label}.`
                : "Needs section-reach data before this can be computed."}
            </div>
          </CardContent>
        </Card>
      </div>

      <SectionCard
        title="Scroll waterfall"
        subtitle="Bands in page order. Reach can only fall as you go down - a rise would mean an instrumentation bug."
        icon={Eye}
      >
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted-foreground">
                <th className="py-2 pr-3 font-medium">Band</th>
                <th className="py-2 pr-3 font-medium">Reach</th>
                <th className="py-2 pr-3 font-medium">% of views</th>
                <th className="py-2 pr-3 font-medium">Lost here</th>
                <th className="py-2 pr-3 font-medium">CTA clicks</th>
                <th className="py-2 font-medium">Engage</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const worst = biggestDrop?.section.key === row.section.key;
                return (
                  <tr
                    key={row.section.key}
                    className={`border-b border-border/50 align-top ${worst ? "bg-destructive/5" : ""}`}
                  >
                    <td className="py-2.5 pr-3">
                      <div className="font-medium">{row.section.label}</div>
                      <div className="text-[11px] leading-snug text-muted-foreground">{row.section.job}</div>
                      {row.section.caveat && (
                        <div className="mt-1 flex gap-1 text-[11px] leading-snug text-[var(--warning-text)]">
                          <Info size={12} className="mt-0.5 shrink-0" />
                          <span>{row.section.caveat}</span>
                        </div>
                      )}
                    </td>
                    <td className="py-2.5 pr-3 font-mono tabular-nums">{fmtInt(row.reach)}</td>
                    <td className="py-2.5 pr-3">
                      <div className="font-mono tabular-nums">{awaitingDeploy ? "n/a" : fmtPct(row.reachPct)}</div>
                      <div className="mt-1 h-1.5 w-24 overflow-hidden rounded-full bg-muted">
                        <div
                          className="h-full rounded-full bg-primary"
                          style={{ width: `${Math.min(100, (row.reachPct ?? 0) * 100)}%` }}
                        />
                      </div>
                    </td>
                    <td className="py-2.5 pr-3 font-mono tabular-nums">
                      {row.lostPct === null || awaitingDeploy ? (
                        <span className="text-muted-foreground">&mdash;</span>
                      ) : (
                        <span className={worst ? "font-semibold text-destructive" : undefined}>
                          {fmtPct(row.lostPct)}
                        </span>
                      )}
                    </td>
                    <td className="py-2.5 pr-3 font-mono tabular-nums">
                      {row.hasCta ? fmtInt(row.clicks) : <span className="text-muted-foreground">&mdash;</span>}
                    </td>
                    <td className="py-2.5 font-mono tabular-nums">
                      {!row.hasCta ? (
                        <span className="text-muted-foreground" title="This band has no call to action.">
                          &mdash;
                        </span>
                      ) : awaitingDeploy ? (
                        "n/a"
                      ) : (
                        fmtPct(row.engagePct)
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </SectionCard>

      <SectionCard
        title="Page-level clicks"
        subtitle="Reachable from any scroll position, so they belong to no single band and get no engage rate."
        icon={MousePointerClick}
        delay={0.05}
      >
        <div className="flex flex-wrap gap-4">
          {UNATTRIBUTED_CLICKS.map((c) => (
            <div key={c.event} className="min-w-[150px]">
              <div className="text-xs text-muted-foreground">{c.label}</div>
              <div className="font-mono text-lg font-semibold tabular-nums">{fmtInt(totals[c.event])}</div>
              <div className="text-[11px] text-muted-foreground">
                {fmtPct(pct(totals[c.event] ?? 0, views))} of views
              </div>
            </div>
          ))}
        </div>
      </SectionCard>

      <SectionCard
        title="Retired counters"
        subtitle="Whitelisted server-side, but no mounted component can fire them any more."
        icon={Archive}
        delay={0.1}
      >
        <p className="mb-3 max-w-3xl text-xs leading-relaxed text-muted-foreground">
          These belong to bands removed in the 2026-08-14 content-reduction pass. They are shown apart from the
          waterfall on purpose: rendered inline they would read as a band that converts nobody, when the truth is that
          no code fires them. Historical counts stay real until the 90-day TTL rolls them off.
        </p>
        <div className="flex flex-wrap gap-4">
          {RETIRED_EVENTS.map((r) => (
            <div key={r.event} className="min-w-[190px]">
              <div className="flex items-center gap-1.5">
                <code className="font-mono text-xs">{r.event}</code>
                <Badge variant="outline" className="text-[10px]">
                  retired
                </Badge>
              </div>
              <div className="font-mono text-lg font-semibold tabular-nums text-muted-foreground">
                {fmtInt(totals[r.event])}
              </div>
              <div className="text-[11px] text-muted-foreground">was {r.from}</div>
            </div>
          ))}
        </div>
      </SectionCard>

      <SectionCard title="What this screen can and cannot tell you" icon={TrendingDown} delay={0.15}>
        <ul className="max-w-3xl list-disc space-y-2 pl-5 text-sm leading-relaxed text-muted-foreground">
          <li>
            <strong className="text-foreground">It can tell you where attention stops.</strong> The waterfall is
            measured, per band, from real visitors.
          </li>
          <li>
            <strong className="text-foreground">It cannot tell you why.</strong> There is no visitor id, no session
            replay and no path data in this system &mdash; deliberately. A drop between two bands says the second band
            did not hold people; it does not say which word lost them. Any explanation, including one written by a
            model, is a hypothesis until a copy test or a real customer conversation settles it.
          </li>
          <li>
            <strong className="text-foreground">Reach is per browser session, not per person.</strong> Rates here are
            directionally sound and should not be quoted as unique-human conversion.
          </li>
          <li>
            A band whose reach is high and engage is low has a <em>copy</em> problem. A band whose reach is low has a{" "}
            <em>position</em> problem, and rewriting its copy cannot fix it. Telling those two apart is the entire
            reason this screen exists.
          </li>
        </ul>
      </SectionCard>
    </AdminShell>
  );
}
