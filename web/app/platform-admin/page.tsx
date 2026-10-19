"use client";

import { useEffect, useState } from "react";
import { motion } from "motion/react";
import Link from "next/link";
import {
  RefreshCw,
  Boxes,
  Wallet,
  BarChart3,
  ShieldCheck,
  Gauge,
  Siren,
  ScrollText,
  Users2,
  Activity,
  AlertOctagon,
  Settings2,
  LayoutTemplate,
  Power,
  Wrench,
  ArrowRight,
} from "lucide-react";
import "./components/dashboard/dashboardStyles";
import { api } from "./lib/api";
import AdminShell from "./components/AdminShell";
import { PageHint } from "./components/sections/common";
import { SystemStatusSection } from "./components/sections/health";
import { Button } from "./components/ui/button";
import { Badge } from "./components/ui/badge";
import { Card, CardContent } from "./components/ui/card";
import { demoSandboxSummary, normalizeTenantCounts, type TenantCounts } from "./lib/tenantCounts";
import { getSpendSummary, type SpendSummary } from "./lib/adminApi/spend";
import { formatUsdOrUnavailable } from "./lib/platformFormat";

// A tile value is "loading" until its fetch settles, then either a measured
// value or "unavailable" - a failed fetch is never shown as 0 / $0.
type TileValue =
  { state: "loading" } | { state: "ok"; value: string | number; sub?: string } | { state: "unavailable" };
const LOADING: TileValue = { state: "loading" };
const UNAVAILABLE_TILE: TileValue = { state: "unavailable" };

async function settle<T>(p: Promise<T>): Promise<{ ok: true; value: T } | { ok: false }> {
  try {
    return { ok: true, value: await p };
  } catch {
    return { ok: false };
  }
}

// Overview §3.1 - the console's landing: platform health, live spend, active
// tenants, and open alerts at a glance, then jump-off cards to every section.
// Every number here is a glance-level summary of the same real data the
// dedicated sections render in full - nothing is invented or independently
// sourced. Rebuilt on shadcn tokens to match the rest of the app.
const SECTION_CARDS = [
  {
    href: "/platform-admin/ops",
    label: "Operations Hub",
    icon: Wrench,
    desc: "Dependencies, versions, maintenance, jobs, migrations",
  },
  {
    href: "/platform-admin/llm-providers",
    label: "LLM & Providers",
    icon: Boxes,
    desc: "Hosted pool, task routing, model catalog",
  },
  { href: "/platform-admin/budgets", label: "Budgets & Limits", icon: Wallet, desc: "Per-user dollar budgets & caps" },
  {
    href: "/platform-admin/spend",
    label: "Spend & Reports",
    icon: BarChart3,
    desc: "Spend by tenant / model / modality",
  },
  {
    href: "/platform-admin/safety",
    label: "Safety & Moderation",
    icon: ShieldCheck,
    desc: "Policy packs, review queue, blocklists",
  },
  {
    href: "/platform-admin/abuse",
    label: "Abuse & Rate Control",
    icon: Gauge,
    desc: "Rate limits, flood protection, breakers",
  },
  {
    href: "/platform-admin/fraud",
    label: "Fraud & Anomaly",
    icon: Siren,
    desc: "Anomaly signals, risk scores, alerts",
  },
  {
    href: "/platform-admin/audit",
    label: "Audit & Compliance",
    icon: ScrollText,
    desc: "Audit log, residency, kill switch",
  },
  {
    href: "/platform-admin/tenants",
    label: "Tenants & Users",
    icon: Users2,
    desc: "Plans, roster, residency, flags, promos",
  },
  {
    href: "/platform-admin/templates",
    label: "Community Templates",
    icon: LayoutTemplate,
    desc: "Submission review queue",
  },
  {
    href: "/platform-admin/settings",
    label: "Platform Settings",
    icon: Settings2,
    desc: "Every runtime knob in one editor",
  },
  {
    href: "/platform-admin/diagnostics",
    label: "Diagnostics & Observability",
    icon: Activity,
    desc: "Native panels + external tools",
  },
];

export default function OverviewPage() {
  const [health, setHealth] = useState<any>(null);
  const [healthError, setHealthError] = useState<any>(null);
  const [openAlerts, setOpenAlerts] = useState<TileValue>(LOADING);
  const [hostedSpend, setHostedSpend] = useState<TileValue>(LOADING);
  const [allProviderSpend, setAllProviderSpend] = useState<TileValue>(LOADING);
  const [activeTenants, setActiveTenants] = useState<TileValue>(LOADING);
  const [tenantCounts, setTenantCounts] = useState<TenantCounts | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [lastChecked, setLastChecked] = useState<Date | null>(null);
  // "Needs attention" signals - each is a real pending-count from the same
  // endpoint the owning page reads, so the strip never invents a number.
  const [platformStopped, setPlatformStopped] = useState<any>(null);
  const [pendingMcp, setPendingMcp] = useState<number | null>(null);
  const [pendingTemplates, setPendingTemplates] = useState<number | null>(null);
  const [throttledCount, setThrottledCount] = useState<number | null>(null);

  async function refresh() {
    setRefreshing(true);
    try {
      const [h, issues, spend, counts, ks, mcp, templates, throttled] = await Promise.all([
        api.getPlatformHealth().catch((): null => null),
        settle<any>(api.getPlatformIssuesOpenCount()),
        // Server default range = trailing 30 days (PlatformSpendController).
        settle<SpendSummary>(getSpendSummary({})),
        api.getPlatformTenantCounts().catch((): null => null),
        api.getKillSwitchStatus().catch((): null => null),
        api.listPendingMcpReviews().catch((): null => null),
        api.getAdminTemplatesPendingQuiet().catch((): null => null),
        api.getAbuseThrottled().catch((): null => null),
      ]);
      setHealth(h);
      setHealthError(h ? null : "unreachable");
      if (issues.ok && typeof issues.value?.open === "number") {
        const failed = Number(issues.value.failedTenants) || 0;
        setOpenAlerts({
          state: "ok",
          value: issues.value.open,
          sub: failed > 0 ? `${failed} tenant${failed === 1 ? "" : "s"} unreadable - count may be low` : undefined,
        });
      } else {
        setOpenAlerts(UNAVAILABLE_TILE);
      }
      setPlatformStopped(ks?.stopped ? ks : null);
      setPendingMcp(Array.isArray(mcp) ? mcp.length : null);
      setPendingTemplates(Array.isArray(templates) ? templates.length : null);
      setThrottledCount(Array.isArray(throttled) ? throttled.length : null);
      const meta = spend.ok ? spend.value?.meta : undefined;
      const partial =
        meta && (meta.truncated || meta.tenantsFailed > 0) ? "partial - some tenants not read" : undefined;
      const hostedUsd = spend.ok ? spend.value?.hostedPool?.estimatedCostUsd : undefined;
      const allUsd = spend.ok ? spend.value?.allProviders?.estimatedCostUsd : undefined;
      setHostedSpend(
        typeof hostedUsd === "number"
          ? { state: "ok", value: formatUsdOrUnavailable(hostedUsd), sub: partial || "platform-paid" }
          : UNAVAILABLE_TILE
      );
      setAllProviderSpend(
        typeof allUsd === "number"
          ? { state: "ok", value: formatUsdOrUnavailable(allUsd), sub: partial || "incl. tenants' BYOK keys" }
          : UNAVAILABLE_TILE
      );
      // Registered tenants only - from the count endpoint; unavailable if it fails.
      const normalized = normalizeTenantCounts(counts);
      setTenantCounts(normalized);
      setActiveTenants(normalized ? { state: "ok", value: normalized.registeredActive } : UNAVAILABLE_TILE);
    } finally {
      setLastChecked(new Date());
      setRefreshing(false);
    }
  }

  useEffect(() => {
    refresh().catch((e) => console.error("Overview initial load failed", e));
  }, []);

  const tiles: { label: string; value: TileValue; href: string }[] = [
    { label: "Open alerts", value: openAlerts, href: "/platform-admin/fraud" },
    { label: "Hosted-pool spend (30d)", value: hostedSpend, href: "/platform-admin/spend" },
    { label: "All-provider usage (30d)", value: allProviderSpend, href: "/platform-admin/spend" },
    { label: "Registered tenants (active)", value: activeTenants, href: "/platform-admin/tenants" },
    // Anonymous /try sandboxes - real tenant rows, never added to the figure
    // above. Absent entirely when core cannot report them.
    ...(tenantCounts
      ? [
          {
            label: "Demo sandboxes (not tenants)",
            value: { state: "ok", value: demoSandboxSummary(tenantCounts) } as TileValue,
            href: "/platform-admin/demo-sandbox",
          },
        ]
      : []),
  ];

  return (
    <AdminShell
      title="Overview"
      actions={
        <Button variant="outline" size="sm" onClick={refresh} disabled={refreshing} title="Refresh">
          <RefreshCw size={14} aria-hidden="true" className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Governance &amp; Operations Console - platform health, live spend, active tenants and open alerts at a glance.
        Every control is at most two clicks away in the rail on the left.
        {lastChecked && ` Last checked ${lastChecked.toLocaleTimeString()}.`}
      </PageHint>

      {/* Ecosystem status hero - same gradient treatment as before, reading the
          same real health state the System status panel below expands. */}
      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1], delay: 0.05 }}
      >
        <Card className="mb-4 gap-0 border-primary/25 bg-gradient-to-br from-primary/10 to-cyan-400/5 py-0">
          <CardContent className="flex flex-wrap items-center justify-between gap-3 p-5">
            <div className="flex items-center gap-3">
              <span className="font-mono text-[11px] tracking-[0.12em] text-primary">ECOSYSTEM STATUS</span>
              <Badge variant="outline" className="gap-1.5">
                <span
                  className={`size-1.5 rounded-full ${health ? "bg-[var(--state-success)]" : "bg-muted-foreground"}`}
                />
                {health?.overallStatus || (healthError ? "UNREACHABLE" : "CHECKING…")}
              </Badge>
            </div>
            <span className="text-xs text-muted-foreground">Full breakdown in System status below.</span>
          </CardContent>
        </Card>
      </motion.div>

      {/* Operations hub - the "is anything wrong right now" screen, linked
          prominently so it is one click from landing. */}
      <Link href="/platform-admin/ops" className="group mb-4 block">
        <Card className="gap-0 border-primary/30 py-0 transition-colors hover:border-primary/60">
          <CardContent className="flex flex-wrap items-center gap-3 p-4">
            <Wrench size={18} className="shrink-0 text-primary" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold text-foreground">Operations hub</div>
              <div className="text-xs text-muted-foreground">
                Dependency health, deployed versions, maintenance &amp; banner, failed jobs, schema drift and open
                alerts on one screen.
              </div>
            </div>
            <span className="inline-flex items-center gap-1 text-xs font-medium text-primary">
              Open <ArrowRight size={13} className="transition-transform group-hover:translate-x-0.5" />
            </span>
          </CardContent>
        </Card>
      </Link>

      {/* Glance-level stat tiles */}
      <div className={`mb-4 grid gap-3 sm:grid-cols-2 ${tiles.length > 4 ? "lg:grid-cols-5" : "lg:grid-cols-4"}`}>
        {tiles.map((tile) => (
          <Link key={tile.label} href={tile.href}>
            <Card className="gap-0 py-0 transition-colors hover:border-primary/40">
              <CardContent className="p-4">
                <div className="text-xs text-muted-foreground">{tile.label}</div>
                {tile.value.state === "unavailable" ? (
                  <div
                    className="mt-1 text-sm font-medium text-muted-foreground"
                    title="Could not be measured right now"
                  >
                    unavailable
                  </div>
                ) : (
                  <div className="mt-1 font-mono text-2xl font-semibold text-foreground">
                    {tile.value.state === "loading" ? "…" : tile.value.value}
                  </div>
                )}
                {tile.value.state === "ok" && tile.value.sub && (
                  <div className="mt-0.5 text-[11px] text-muted-foreground">{tile.value.sub}</div>
                )}
              </CardContent>
            </Card>
          </Link>
        ))}
      </div>

      {/* Needs-attention strip - only renders when a real queue/signal is
          non-empty, so a quiet overview stays quiet. Each tile is the same
          count the owning page shows, deep-linked. */}
      {(platformStopped || (pendingMcp ?? 0) > 0 || (pendingTemplates ?? 0) > 0 || (throttledCount ?? 0) > 0) && (
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35, delay: 0.06, ease: [0.16, 1, 0.3, 1] }}
        >
          <Card className="mb-4 gap-0 border-[var(--warning-border)] bg-[var(--warning-bg)] py-0">
            <CardContent className="p-4">
              <h2 className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-[var(--warning-text)]">
                <AlertOctagon size={14} /> Needs attention
              </h2>
              <div className="flex flex-wrap gap-2">
                {platformStopped && (
                  <Link href="/platform-admin/audit">
                    <Badge
                      variant="outline"
                      className="gap-1.5 border-destructive/40 bg-destructive/10 text-destructive"
                    >
                      <Power size={11} /> Platform stopped{platformStopped.reason ? `: ${platformStopped.reason}` : ""}
                    </Badge>
                  </Link>
                )}
                {(pendingMcp ?? 0) > 0 && (
                  <Link href="/platform-admin/mcp-review">
                    <Badge
                      variant="outline"
                      className="gap-1.5 border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]"
                    >
                      {pendingMcp} MCP review{pendingMcp === 1 ? "" : "s"} pending
                    </Badge>
                  </Link>
                )}
                {(pendingTemplates ?? 0) > 0 && (
                  <Link href="/platform-admin/templates">
                    <Badge
                      variant="outline"
                      className="gap-1.5 border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]"
                    >
                      {pendingTemplates} template submission{pendingTemplates === 1 ? "" : "s"} pending
                    </Badge>
                  </Link>
                )}
                {(throttledCount ?? 0) > 0 && (
                  <Link href="/platform-admin/fraud">
                    <Badge
                      variant="outline"
                      className="gap-1.5 border-[var(--danger-border)] bg-[var(--danger-bg)] text-[var(--danger-text)]"
                    >
                      {throttledCount} principal{throttledCount === 1 ? "" : "s"} throttled
                    </Badge>
                  </Link>
                )}
              </div>
            </CardContent>
          </Card>
        </motion.div>
      )}

      <motion.div
        className="mb-4"
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, delay: 0.08, ease: [0.16, 1, 0.3, 1] }}
      >
        <Card>
          <CardContent className="p-4">
            <h2 className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              <Activity size={14} /> Shared infrastructure
            </h2>
            <SystemStatusSection pollMs={15000} />
          </CardContent>
        </Card>
      </motion.div>

      {/* Jump-off cards to every section */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {SECTION_CARDS.map((c) => {
          const Icon = c.icon;
          return (
            <Link key={c.href} href={c.href}>
              <Card className="h-full gap-0 py-0 transition-colors hover:border-primary/40">
                <CardContent className="flex items-start gap-3 p-4">
                  <Icon size={18} className="mt-0.5 shrink-0 text-primary" />
                  <div className="min-w-0">
                    <div className="text-sm font-semibold text-foreground">{c.label}</div>
                    <div className="mt-0.5 text-xs text-muted-foreground">{c.desc}</div>
                  </div>
                </CardContent>
              </Card>
            </Link>
          );
        })}
      </div>
    </AdminShell>
  );
}
