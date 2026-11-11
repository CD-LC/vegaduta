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
import { useTranslations } from "../../lib/i18n";

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
  const tHC = useTranslations("hcPlatformadminFunnelPage");
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
      title={tHC("s39")}
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
        {tHC("s38")} <code className="font-mono text-xs">LandingEventController</code> {tHC("s37")}{" "}
        <code className="font-mono text-xs">+1</code> {tHC("s36")} <strong>Reach</strong> {tHC("s35")}{" "}
        <strong>engage</strong> {tHC("s34")}
      </PageHint>

      {error && (
        <Alert variant="destructive" className="mb-5">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {awaitingDeploy && (
        <Alert className="mb-5">
          <AlertDescription>
            <strong>{tHC("s33")}</strong> {tHC("s32")} {fmtInt(views)} {tHC("s31")}{" "}
            <code className="font-mono text-xs">sec_*</code> {tHC("s30")}{" "}
            <code className="font-mono text-xs">SectionReach.tsx</code> {tHC("s29")}
          </AlertDescription>
        </Alert>
      )}

      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">{tHC("s28")}</div>
            <div className="mt-1 font-mono text-2xl font-semibold tabular-nums text-primary">{fmtInt(views)}</div>
            <div className="mt-1 text-[11px] leading-snug text-muted-foreground">
              {tHC("s27")} <em>{tHC("s26")}</em>
              {tHC("s25")}
            </div>
          </CardContent>
        </Card>
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">{tHC("s24")}</div>
            <div className="mt-1 font-mono text-2xl font-semibold tabular-nums">
              {awaitingDeploy ? "n/a" : fmtPct(pct(totals.sec_pricing ?? 0, views))}
            </div>
            <div className="mt-1 text-[11px] leading-snug text-muted-foreground">{tHC("s23")}</div>
          </CardContent>
        </Card>
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">{tHC("s22")}</div>
            <div className="mt-1 font-mono text-2xl font-semibold tabular-nums">
              {awaitingDeploy ? "n/a" : fmtPct(pct(totals.sec_closing ?? 0, views))}
            </div>
            <div className="mt-1 text-[11px] leading-snug text-muted-foreground">{tHC("s21")}</div>
          </CardContent>
        </Card>
        <Card className="gap-0 py-0">
          <CardContent className="p-4">
            <div className="text-xs text-muted-foreground">{tHC("s20")}</div>
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
        title={tHC("s19")}
        subtitle="Bands in page order. Reach can only fall as you go down - a rise would mean an instrumentation bug."
        icon={Eye}
      >
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-sm">
            <thead>
              <tr className="border-b border-border text-start text-xs text-muted-foreground">
                <th className="py-2 pe-3 font-medium">Band</th>
                <th className="py-2 pe-3 font-medium">Reach</th>
                <th className="py-2 pe-3 font-medium">{tHC("s18")}</th>
                <th className="py-2 pe-3 font-medium">{tHC("s17")}</th>
                <th className="py-2 pe-3 font-medium">{tHC("s16")}</th>
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
                    <td className="py-2.5 pe-3">
                      <div className="font-medium">{row.section.label}</div>
                      <div className="text-[11px] leading-snug text-muted-foreground">{row.section.job}</div>
                      {row.section.caveat && (
                        <div className="mt-1 flex gap-1 text-[11px] leading-snug text-[var(--warning-text)]">
                          <Info size={12} className="mt-0.5 shrink-0" />
                          <span>{row.section.caveat}</span>
                        </div>
                      )}
                    </td>
                    <td className="py-2.5 pe-3 font-mono tabular-nums">{fmtInt(row.reach)}</td>
                    <td className="py-2.5 pe-3">
                      <div className="font-mono tabular-nums">{awaitingDeploy ? "n/a" : fmtPct(row.reachPct)}</div>
                      <div className="mt-1 h-1.5 w-24 overflow-hidden rounded-full bg-muted">
                        <div
                          className="h-full rounded-full bg-primary"
                          style={{ width: `${Math.min(100, (row.reachPct ?? 0) * 100)}%` }}
                        />
                      </div>
                    </td>
                    <td className="py-2.5 pe-3 font-mono tabular-nums">
                      {row.lostPct === null || awaitingDeploy ? (
                        <span className="text-muted-foreground">&mdash;</span>
                      ) : (
                        <span className={worst ? "font-semibold text-destructive" : undefined}>
                          {fmtPct(row.lostPct)}
                        </span>
                      )}
                    </td>
                    <td className="py-2.5 pe-3 font-mono tabular-nums">
                      {row.hasCta ? fmtInt(row.clicks) : <span className="text-muted-foreground">&mdash;</span>}
                    </td>
                    <td className="py-2.5 font-mono tabular-nums">
                      {!row.hasCta ? (
                        <span className="text-muted-foreground" title={tHC("s15")}>
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
        title={tHC("s14")}
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
                {fmtPct(pct(totals[c.event] ?? 0, views))} {tHC("s13")}
              </div>
            </div>
          ))}
        </div>
      </SectionCard>

      <SectionCard
        title={tHC("s12")}
        subtitle="Whitelisted server-side, but no mounted component can fire them any more."
        icon={Archive}
        delay={0.1}
      >
        <p className="mb-3 max-w-3xl text-xs leading-relaxed text-muted-foreground">{tHC("s11")}</p>
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

      <SectionCard title={tHC("s10")} icon={TrendingDown} delay={0.15}>
        <ul className="max-w-3xl list-disc space-y-2 ps-5 text-sm leading-relaxed text-muted-foreground">
          <li>
            <strong className="text-foreground">{tHC("s9")}</strong> {tHC("s8")}
          </li>
          <li>
            <strong className="text-foreground">{tHC("s7")}</strong> {tHC("s6")}
          </li>
          <li>
            <strong className="text-foreground">{tHC("s5")}</strong> {tHC("s4")}
          </li>
          <li>
            {tHC("s3")} <em>copy</em> {tHC("s2")} <em>position</em> {tHC("s1")}
          </li>
        </ul>
      </SectionCard>
    </AdminShell>
  );
}
