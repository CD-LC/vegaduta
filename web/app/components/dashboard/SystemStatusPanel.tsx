"use client";

import { Activity } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { StatusDot, type StatusDotStatus } from "../ui/status-dot";
import BrandText from "../BrandText";

// "System health like a spaceship data center" + "database details, how
// MCP gets connected... system health of the ecosystem" - every reading
// here comes straight from GET /api/system/status (SystemStatusController's
// javadoc explains exactly where each field is read from - live actuator
// health contributors, Hikari's own pool MXBean, the JVM's management
// beans, the existing MCP registry). Nothing in this component invents a
// number: a field that's null/absent renders as "—" or a "no signal" row,
// never a fabricated value.

// Design-system contract (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md §3): a
// STATE is a circle (.state-dot) that also animates when live, never a bare
// coloured dot - readable in greyscale/CVD by shape+motion, not hue alone.
// Routed through the shared <StatusDot system="state" .../> (ui/status-dot.tsx)
// rather than hand-rolling .state-dot markup here - that component exists
// specifically to stop a sixth drifting status-dot implementation from
// appearing alongside the five it already consolidated. Its default
// up/down/unknown -> success/error/idle mapping is exactly this panel's own
// UP/DOWN/UNKNOWN/NOT_CONFIGURED semantics, so no stateColor override needed -
// just translate this panel's vocabulary into the shared component's.
const STATUS_MAP: Record<string, StatusDotStatus> = {
  UP: "up",
  DOWN: "down",
  UNKNOWN: "unknown",
  NOT_CONFIGURED: "unknown",
};

// TS mirror of SystemStatusResponse.java (core/app systemstatus package) -
// api.getSystemStatus() is untyped (.then(toJson)), so the contract is
// declared here, at the consumer, until api.ts grows a shared export. Only
// the fields this panel actually renders are listed; nullability follows
// that record's own javadoc ("a real value that genuinely can't be
// determined is null, never a fabricated zero"), and every top-level
// section is optional for the same reason. `status` strings stay plain
// `string` rather than a union: STATUS_MAP already funnels anything
// unrecognized to "unknown", so a new server-side status must degrade
// gracefully here, not become a compile error in a deployed frontend.
interface ComponentStatusReading {
  status: string; // UP | DOWN | NOT_CONFIGURED | UNKNOWN
}

export interface SystemStatusSummary {
  overallStatus: string; // NOMINAL | DEGRADED | CRITICAL
  database?:
    | (ComponentStatusReading & {
        product?: string | null;
        activeConnections?: number | null;
        maxPoolSize?: number | null;
      })
    | null;
  redis?: ComponentStatusReading | null;
  kafka?: ComponentStatusReading | null;
  mcp?: { totalServers: number; enabledServers: number } | null;
  jvm?: {
    heapUsedMb: number;
    heapMaxMb?: number | null; // null when the JVM reports no defined max (-1)
    uptimeSeconds: number;
    availableProcessors?: number | null;
  } | null;
  llm?: { totalCalls: number; errorCount: number; avgLatencyMs?: number | null } | null;
  sidecar?: ComponentStatusReading | null;
}

function ReadoutDot({ status }: { status?: string }) {
  const mapped = STATUS_MAP[status] || "unknown";
  return <StatusDot status={mapped} system="state" live={mapped === "up"} />;
}

// Overall NOMINAL/DEGRADED/CRITICAL banner (SystemStatusController's own
// overallStatus() javadoc) as a .state-pill rather than a bespoke badge -
// same contract, one fewer bespoke color rule to maintain in dashboard.css.
const OVERALL_STATE_TOKEN: Record<string, string> = {
  NOMINAL: "var(--state-success)",
  DEGRADED: "var(--state-warning)",
  CRITICAL: "var(--state-error)",
};

// Takes `number | undefined` (jvm may be absent), keeps the runtime typeof
// guard anyway - it's also what renders "—" for the undefined case.
function formatUptime(seconds: number | undefined) {
  if (typeof seconds !== "number") return "—";
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

// "NOT_CONFIGURED" (the sidecar base-url property is simply
// unset - this platform's normal "feature absent" state, see
// SystemStatusController's javadoc) reads as a neutral "not configured"
// detail, distinct from a real "DOWN" outage.
function microserviceDetail(component: ComponentStatusReading | null | undefined, notConfiguredLabel: string) {
  if (!component?.status) return "—";
  if (component.status === "NOT_CONFIGURED") return notConfiguredLabel;
  return component.status;
}

function microserviceDotStatus(component: ComponentStatusReading | null | undefined) {
  if (!component?.status || component.status === "NOT_CONFIGURED") return "UNKNOWN";
  return component.status;
}

// Every value a Readout renders is a pre-formatted string by the time it
// gets here (the callers do all the "value or —" fallbacks inline), so the
// props are plain strings - not ReactNode - on purpose.
function Readout({ label, status, detail, hint }: { label: string; status?: string; detail?: string; hint?: string }) {
  return (
    <div className="mc-status-row" title={hint}>
      <span className="mc-status-row-label">
        <ReadoutDot status={status} />
        {label}
      </span>
      <span className="mc-status-row-detail">{detail}</span>
    </div>
  );
}

// `status` is null until the caller's first getSystemStatus() resolves (both
// admin pages seed it with useState(null)); `error` is that fetch's failure
// message, also null until one actually happens.
export default function SystemStatusPanel({
  status,
  error,
}: {
  status: SystemStatusSummary | null;
  error: string | null;
}) {
  const t = useTranslations("systemStatusPanel");
  return (
    <div className="mc-status-panel mc-hud-panel">
      <svg
        className="mc-bento-corners mc-status-corners"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        fill="none"
        aria-hidden="true"
      >
        <path d="M4 20 V6 a2 2 0 0 1 2-2 H20" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M80 4 H94 a2 2 0 0 1 2 2 V20" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M96 80 V94 a2 2 0 0 1-2 2 H80" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M20 96 H6 a2 2 0 0 1-2-2 V80" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
      </svg>

      <div className="mc-status-header">
        <h3>
          <span className="cat-chip cat-chip-sm" style={{ ["--cat" as string]: "var(--cat-data)" }} aria-hidden="true">
            <Activity size={13} aria-hidden="true" />
          </span>
          <BrandText text={t("title")} /> <span className="mc-status-header-id">{t("ecosystemBadge")}</span>
        </h3>
        {status && (
          <span
            className="state-pill"
            style={{ ["--state" as string]: OVERALL_STATE_TOKEN[status.overallStatus] || "var(--state-idle)" }}
          >
            <span className="state-dot" />
            {status.overallStatus}
          </span>
        )}
      </div>

      {!status ? (
        <p className="mc-status-nosignal">{error ? t("telemetryUnavailable", { error }) : t("readingTelemetry")}</p>
      ) : (
        <div className="mc-status-grid">
          <Readout
            label={t("databaseLabel")}
            status={status.database?.status}
            detail={
              status.database?.activeConnections != null
                ? `${status.database.activeConnections}/${status.database.maxPoolSize ?? "—"} conns`
                : status.database?.status || "—"
            }
          />
          <Readout label={t("redisLabel")} status={status.redis?.status} detail={status.redis?.status || "—"} />
          {/* A subsystem this deployment does not run (NOT_CONFIGURED: Kafka and the sidecar on a
              personal install) gets no tile rather than a grey "not active" one. */}
          {status.kafka?.status !== "NOT_CONFIGURED" && (
            <Readout
              label={t("kafkaLabel")}
              status={status.kafka?.status}
              detail={status.kafka?.status === "UNKNOWN" ? t("kafkaNotActive") : status.kafka?.status || "—"}
            />
          )}
          <Readout
            label={t("mcpRegistryLabel")}
            status={status.mcp ? "UP" : "UNKNOWN"}
            detail={status.mcp ? `${status.mcp.enabledServers}/${status.mcp.totalServers} enabled` : "—"}
          />
          <Readout
            label={t("jvmHeapLabel")}
            status="UP"
            detail={
              status.jvm ? `${status.jvm.heapUsedMb}${status.jvm.heapMaxMb ? `/${status.jvm.heapMaxMb}` : ""} MB` : "—"
            }
          />
          <Readout label={t("uptimeLabel")} status="UP" detail={formatUptime(status.jvm?.uptimeSeconds)} />
          <Readout
            label={t("llmCallsLabel")}
            status={status.llm?.totalCalls > 0 ? "UP" : "UNKNOWN"}
            detail={
              status.llm
                ? `${status.llm.totalCalls}${status.llm.errorCount > 0 ? ` (${status.llm.errorCount} errors)` : ""}`
                : "—"
            }
            hint={t("llmCallsHint")}
          />
          <Readout
            label={t("llmLatencyLabel")}
            status={status.llm?.avgLatencyMs != null ? "UP" : "UNKNOWN"}
            detail={
              status.llm?.avgLatencyMs != null
                ? `${Math.round(status.llm.avgLatencyMs)}ms avg`
                : t("llmLatencyNoCallsYet")
            }
            hint={t("llmLatencyHint")}
          />
          {status.sidecar?.status !== "NOT_CONFIGURED" && (
            <Readout
              label={t("sidecarLabel")}
              status={microserviceDotStatus(status.sidecar)}
              detail={microserviceDetail(status.sidecar, t("notConfigured"))}
            />
          )}
        </div>
      )}

      {status?.database?.product && (
        <p className="mc-status-footnote">
          {status.database.product}
          {status.jvm?.availableProcessors ? ` · ${status.jvm.availableProcessors} vCPU` : ""}
        </p>
      )}
    </div>
  );
}
