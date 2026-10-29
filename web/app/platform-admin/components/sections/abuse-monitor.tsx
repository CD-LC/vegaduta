"use client";

import { useState } from "react";
import Link from "next/link";
import { AnimatePresence, motion } from "motion/react";
import { Radar, ShieldAlert, Gauge, Sliders, ChevronDown, ExternalLink, Undo2 } from "lucide-react";
import { api } from "../../lib/api";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { toast } from "../../lib/toastBus";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Alert, AlertDescription } from "../ui/alert";
import { timeAgo, timeUntil, SectionCard } from "./common";
import { useSectionData } from "./observability";
import { useTranslations } from "../../../lib/i18n";

// Fraud & Anomaly's real backend - AbuseMonitorController
// (/api/platform/abuse-monitor/**), which the AbuseSignalService scores behind:
// spend spikes, token burn, message floods, signup velocity, tool-error abuse
// and geo-velocity roll into a 0..100 risk score per (tenant, user) principal;
// crossing WATCH writes a fingerprinted anomaly issue, THROTTLE rate-limits the
// principal, SUSPEND emergency-stops the tenant (when autoSuspend is on).
// Thresholds are platform_setting rows (migration V120) - displayed read-only
// here and edited on the Platform Settings page.

// text-*-600 measured ~2.95-3.43:1 on this badge's own light bg tint - under
// the 4.5:1 AA minimum at this text size. -700 clears it comfortably (dark
// mode's -400 pairing was already AA and is untouched).
const TIER_BADGE: Record<string, string> = {
  WATCH: "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]",
  THROTTLE: "border-[var(--danger-border)] bg-[var(--danger-bg)] text-[var(--danger-text)]",
  SUSPEND: "border-destructive/40 bg-destructive/10 text-destructive",
  NORMAL: "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]",
};

const META_CLS = "font-mono text-xs text-muted-foreground";

function TierBadge({ tier }: { tier: string }) {
  return (
    <Badge variant="outline" className={TIER_BADGE[tier] || ""}>
      {tier}
    </Badge>
  );
}

// Score bar - 0..100 with the tier's colour.
function ScoreBar({ score, tier }: { score: number; tier: string }) {
  const pct = Math.max(0, Math.min(100, Number(score || 0)));
  const colour =
    tier === "SUSPEND"
      ? "bg-destructive"
      : tier === "THROTTLE"
        ? "bg-[var(--state-error)]"
        : tier === "WATCH"
          ? "bg-[var(--warning)]"
          : "bg-[var(--state-success)]";
  return (
    <div className="flex items-center gap-2">
      <div className="h-1.5 w-24 overflow-hidden rounded-full bg-secondary/60">
        <div className={`h-full rounded-full ${colour}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="font-mono text-xs text-foreground">{pct.toFixed(0)}</span>
    </div>
  );
}

// ── Risk scores (WATCH+ principals across every active tenant) ─────────────
export function AbuseScoresSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsAbusemonitor");
  const {
    data: scores,
    error,
    loading,
  } = useSectionData<any[]>(() => api.getAbuseScores().then((r: any) => (Array.isArray(r) ? r : [])), [], pollMs);
  const [expanded, setExpanded] = useState<string | null>(null);
  const pg = usePagination(scores, 10);

  return (
    <SectionCard
      title={tHC("hcPlatformadminComponentsSectionsAbusemonitor.s13")}
      subtitle="Per-principal anomaly score across every active tenant - only principals at WATCH or above are listed, highest first"
      icon={Radar}
      delay={delay}
      actions={
        scores.length > 0 ? (
          <Badge variant="outline" className={TIER_BADGE.WATCH}>
            {scores.length} flagged
          </Badge>
        ) : undefined
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsAbusemonitor.s2")}</p>
      ) : scores.length === 0 ? (
        <p className="py-1 text-sm text-muted-foreground">
          {tHC("hcPlatformadminComponentsSectionsAbusemonitor.s12")}
        </p>
      ) : (
        <AnimatePresence initial={false}>
          {pg.pageItems.map((row) => {
            const key = `${row.tenantId}:${row.userId || "*"}`;
            const isOpen = expanded === key;
            const signals = Array.isArray(row.signals) ? row.signals : [];
            return (
              <motion.div
                key={key}
                className="rounded-lg border border-border p-3"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
              >
                <div className="flex flex-wrap items-center gap-3">
                  <div className="min-w-[160px] flex-1">
                    <strong className="text-sm text-foreground">{row.tenantName || row.tenantId}</strong>
                    <span className={META_CLS}> · {row.userId || "whole tenant"}</span>
                  </div>
                  <ScoreBar score={row.score} tier={row.tier} />
                  <TierBadge tier={row.tier} />
                  {signals.length > 0 && (
                    <Button variant="ghost" size="sm" onClick={() => setExpanded(isOpen ? null : key)}>
                      <ChevronDown
                        size={14}
                        className={isOpen ? "rotate-180 transition-transform" : "transition-transform"}
                      />
                      {signals.length} signal{signals.length === 1 ? "" : "s"}
                    </Button>
                  )}
                </div>
                {isOpen && signals.length > 0 && (
                  <div className="mt-2 space-y-1 border-t border-border pt-2">
                    {signals.map((s: any, i: number) => (
                      <div key={i} className="flex flex-wrap items-center gap-2 text-xs">
                        <Badge variant="secondary" className="font-mono text-[10px]">
                          {s.name}
                        </Badge>
                        {!s.available && <span className="text-muted-foreground">unavailable</span>}
                        {s.detail && <span className="text-muted-foreground">{s.detail}</span>}
                        {s.available && (
                          <span className={META_CLS}>
                            score {Number(s.normalizedScore || 0).toFixed(2)} {tHC("hcPlatformadminComponentsSectionsAbusemonitor.s11")} {s.weight}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
                <div className={`mt-1.5 ${META_CLS}`}>computed {timeAgo(row.computedAt)}</div>
              </motion.div>
            );
          })}
        </AnimatePresence>
      )}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="principals"
      />
    </SectionCard>
  );
}

// ── Anomaly issues raised by the detector ──────────────────────────────────
export function AbuseAnomaliesSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsAbusemonitor");
  const {
    data: anomalies,
    error,
    loading,
  } = useSectionData<any[]>(() => api.getAbuseAnomalies().then((r: any) => (Array.isArray(r) ? r : [])), [], pollMs);
  const pg = usePagination(anomalies, 10);

  return (
    <SectionCard
      title={tHC("hcPlatformadminComponentsSectionsAbusemonitor.s10")}
      subtitle="Fingerprinted ABUSE_ANOMALY issues the detector raised, newest first - repeats fold into the same row via occurrenceCount"
      icon={ShieldAlert}
      delay={delay}
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsAbusemonitor.s2")}</p>
      ) : anomalies.length === 0 ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsAbusemonitor.s9")}</p>
      ) : (
        <AnimatePresence initial={false}>
          {pg.pageItems.map((a) => (
            <motion.div
              key={a.issueId}
              className="flex items-start gap-3 rounded-lg border border-border p-3"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
            >
              <ShieldAlert size={16} className="mt-0.5 shrink-0 text-[var(--warning)]" />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <strong className="text-sm text-foreground">{a.title}</strong>
                  <Badge variant="secondary">{a.severity}</Badge>
                  <Badge variant="outline">{a.status}</Badge>
                  {a.occurrenceCount > 1 && <Badge variant="secondary">×{a.occurrenceCount}</Badge>}
                </div>
                <p className={`mt-1 ${META_CLS}`}>
                  {a.tenantName} · {a.principal} · first {timeAgo(a.firstSeenAt)} · last {timeAgo(a.lastSeenAt)}
                </p>
              </div>
            </motion.div>
          ))}
        </AnimatePresence>
      )}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="anomalies"
      />
    </SectionCard>
  );
}

// ── Currently-throttled principals + the reversible clear action ────────────
export function ThrottledSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsAbusemonitor");
  const {
    data: throttled,
    error,
    loading,
    refresh,
  } = useSectionData<any[]>(() => api.getAbuseThrottled().then((r: any) => (Array.isArray(r) ? r : [])), [], pollMs);
  const [clearing, setClearing] = useState<string | null>(null);
  const pg = usePagination(throttled, 10);

  async function handleClear(entry: any) {
    // principal is "tenantId:userId" or "tenantId:*" for a tenant-wide throttle
    const principal = String(entry.principal || "");
    const sep = principal.indexOf(":");
    const tenantId = sep === -1 ? principal : principal.slice(0, sep);
    const userId = sep === -1 ? "*" : principal.slice(sep + 1);
    setClearing(principal);
    try {
      if (userId === "*" || userId === "") {
        await api.clearAbuseTenantThrottle(tenantId);
      } else {
        await api.clearAbuseThrottle(tenantId, userId);
      }
      toast.success(`Throttle cleared for ${principal}.`);
      refresh().catch(() => {});
    } catch (err: any) {
      toast.error(err.message || `Could not clear the throttle for ${principal}.`);
    } finally {
      setClearing(null);
    }
  }

  return (
    <SectionCard
      title={tHC("hcPlatformadminComponentsSectionsAbusemonitor.s8")}
      subtitle="Principals currently rate-limited by the detector (self-expiring, TTL-bounded) - clearing is audited and reversible"
      icon={Gauge}
      delay={delay}
      actions={
        throttled.length > 0 ? (
          <Badge variant="outline" className={TIER_BADGE.THROTTLE}>
            {throttled.length} active
          </Badge>
        ) : undefined
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsAbusemonitor.s2")}</p>
      ) : throttled.length === 0 ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsAbusemonitor.s7")}</p>
      ) : (
        pg.pageItems.map((entry) => (
          <div
            key={entry.principal}
            className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border px-4 py-3"
          >
            <div className="min-w-0">
              <div className="font-mono text-sm font-semibold text-foreground">{entry.principal}</div>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {entry.reason} {tHC("hcPlatformadminComponentsSectionsAbusemonitor.s6")} {timeUntil(entry.expiresAt)}
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              disabled={clearing === entry.principal}
              onClick={() => handleClear(entry)}
              title={tHC("hcPlatformadminComponentsSectionsAbusemonitor.s5")}
            >
              <Undo2 size={13} /> {clearing === entry.principal ? "Clearing…" : "Clear"}
            </Button>
          </div>
        ))
      )}
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="principals"
      />
    </SectionCard>
  );
}

// ── Detector thresholds (read-only; edited via platform_setting keys) ───────
export function AbuseThresholdsSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsAbusemonitor");
  const { data: cfg, error, loading } = useSectionData<any>(() => api.getAbuseConfig(), null, pollMs);

  const rows: Array<[string, any]> = cfg
    ? [
        ["Scanner running", cfg.scanEnabled ? "on" : "off"],
        ["Auto-suspend at SUSPEND tier", cfg.autoSuspendEnabled ? "on" : "off"],
        ["Windows", `${cfg.recentWindowMinutes}m recent vs ${cfg.baselineWindowMinutes}m baseline`],
        [
          "Tier boundaries",
          `watch ≥ ${cfg.watchThreshold} · throttle ≥ ${cfg.throttleThreshold} · suspend ≥ ${cfg.suspendThreshold}`,
        ],
        ["Throttle TTL", `${cfg.throttleTtlMinutes} min`],
        [
          "Spend spike",
          `≥ ${cfg.spendSpikeMultiplier}× baseline (min ${cfg.spendSpikeMinUsd != null ? `$${cfg.spendSpikeMinUsd}` : "-"}) · weight ${cfg.spendSpikeWeight}`,
        ],
        ["Token burn", `≥ ${cfg.tokenBurnPerMinThreshold}/min · weight ${cfg.tokenBurnWeight}`],
        ["Message flood", `≥ ${cfg.messageFloodPerMinThreshold}/min · weight ${cfg.messageFloodWeight}`],
        ["Signup velocity", `≥ ${cfg.signupVelocityPerHourThreshold}/hour · weight ${cfg.signupVelocityWeight}`],
        [
          "Tool-error ratio",
          `≥ ${cfg.toolErrorRatioThreshold} (min sample ${cfg.toolErrorMinSample}) · weight ${cfg.toolErrorWeight}`,
        ],
        ["Geo velocity", `weight ${cfg.geoVelocityWeight} (applies only when a provider is wired)`],
      ]
    : [];

  return (
    <SectionCard
      title={tHC("hcPlatformadminComponentsSectionsAbusemonitor.s4")}
      subtitle="The effective AbuseThresholds the scanner is running with right now (seeded by migration V120, tuned via platform_setting keys)"
      icon={Sliders}
      delay={delay}
      actions={
        <Link
          href="/platform-admin/settings"
          className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
        >
          {tHC("hcPlatformadminComponentsSectionsAbusemonitor.s3")} <ExternalLink size={11} />
        </Link>
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsAbusemonitor.s2")}</p>
      ) : cfg == null ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("hcPlatformadminComponentsSectionsAbusemonitor.s1")}</p>
      ) : (
        <div className="divide-y divide-border rounded-lg border border-border">
          {rows.map(([label, value]) => (
            <div key={label} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2">
              <span className="text-xs text-muted-foreground">{label}</span>
              <span className="font-mono text-xs text-foreground">{String(value)}</span>
            </div>
          ))}
        </div>
      )}
    </SectionCard>
  );
}
