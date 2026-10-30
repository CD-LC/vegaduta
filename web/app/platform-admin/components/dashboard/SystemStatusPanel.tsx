"use client";

import { extraStatusComponents } from "../../lib/statusComponents";
import { useTranslations } from "../../../lib/i18n";

// Straight copy of web/app/components/dashboard/SystemStatusPanel.js - every
// reading comes from whatever SystemStatusResponse-shaped object the caller
// passes in as `status` (this app's page.js passes the platform-wide
// getPlatformHealth() reading, not getSystemStatus() - see PlatformController's
// javadoc: same shape, cross-tenant-safe passthrough). Nothing here invents a
// number.

const DOT_CLASS: Record<string, string> = {
  UP: "mc-status-dot-up",
  DOWN: "mc-status-dot-down",
  UNKNOWN: "mc-status-dot-unknown",
  NOT_CONFIGURED: "mc-status-dot-unknown",
};

function StatusDot({ status }: { status: string }) {
  return <span className={`mc-status-dot ${DOT_CLASS[status] || DOT_CLASS.UNKNOWN}`} aria-hidden="true" />;
}

function formatUptime(seconds: number) {
  if (typeof seconds !== "number") return "—";
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function microserviceDetail(component: { status?: string } | null | undefined) {
  if (!component?.status) return "—";
  if (component.status === "NOT_CONFIGURED") return "not configured";
  return component.status;
}

function microserviceDotStatus(component: { status?: string } | null | undefined) {
  if (!component?.status || component.status === "NOT_CONFIGURED") return "UNKNOWN";
  return component.status;
}

function Readout({ label, status, detail, hint }: { label: any; status: any; detail: any; hint?: any }) {
  return (
    <div className="mc-status-row" title={hint}>
      <span className="mc-status-row-label">
        <StatusDot status={status} />
        {label}
      </span>
      <span className="mc-status-row-detail">{detail}</span>
    </div>
  );
}

export default function SystemStatusPanel({
  status,
  error,
  hideHeader = false,
}: {
  status?: any;
  error?: any;
  hideHeader?: boolean;
}) {
  const tHC = useTranslations("hcPlatformadminComponentsDashboardSystemStatusPanel");
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

      {/* hideHeader (2026-09-28): lets a caller that already wraps this panel
          in its own titled section (Diagnostics & Observability's SectionCard
          title="System status") suppress this internal heading instead of
          showing "System status" twice, in two different styles, in a row. */}
      <div className="mc-status-header">
        {!hideHeader && (
          <h3>
            {tHC("s6")} <span className="mc-status-header-id">ECOSYSTEM</span>
          </h3>
        )}
        {status && (
          <span className={`mc-status-badge mc-status-badge-${status.overallStatus?.toLowerCase()}`}>
            {status.overallStatus}
          </span>
        )}
      </div>

      {!status ? (
        <p className="mc-status-nosignal">{error ? `Telemetry unavailable - ${error}` : "Reading telemetry…"}</p>
      ) : (
        <div className="mc-status-grid">
          <Readout
            label="Database"
            status={status.database?.status}
            detail={
              status.database?.activeConnections != null
                ? `${status.database.activeConnections}/${status.database.maxPoolSize ?? "—"} conns`
                : status.database?.status || "—"
            }
          />
          <Readout label="Redis" status={status.redis?.status} detail={status.redis?.status || "—"} />
          <Readout
            label={tHC("s5")}
            status={status.kafka?.status}
            detail={status.kafka?.status === "UNKNOWN" ? "not active" : status.kafka?.status || "—"}
          />
          <Readout
            label={tHC("s4")}
            status={status.mcp ? "UP" : "UNKNOWN"}
            detail={status.mcp ? `${status.mcp.enabledServers}/${status.mcp.totalServers} enabled` : "—"}
          />
          <Readout
            label={tHC("s3")}
            status="UP"
            detail={
              status.jvm ? `${status.jvm.heapUsedMb}${status.jvm.heapMaxMb ? `/${status.jvm.heapMaxMb}` : ""} MB` : "—"
            }
          />
          <Readout label="Uptime" status="UP" detail={formatUptime(status.jvm?.uptimeSeconds)} />
          <Readout
            label={tHC("s2")}
            status={status.llm?.totalCalls > 0 ? "UP" : "UNKNOWN"}
            detail={
              status.llm
                ? `${status.llm.totalCalls}${status.llm.errorCount > 0 ? ` (${status.llm.errorCount} errors)` : ""}`
                : "—"
            }
            hint="Each count is one full agent turn (ChatClient call), not one raw model completion."
          />
          <Readout
            label={tHC("s1")}
            status={status.llm?.avgLatencyMs != null ? "UP" : "UNKNOWN"}
            detail={status.llm?.avgLatencyMs != null ? `${Math.round(status.llm.avgLatencyMs)}ms avg` : "no calls yet"}
            hint="Whole-turn latency, including any tool calls the model made - not raw model completion time."
          />
          <Readout
            label="Sidecar"
            status={microserviceDotStatus(status.sidecar)}
            detail={microserviceDetail(status.sidecar)}
          />
          {/* Any component core adds beyond the named ones above renders
              generically under its own name (see lib/statusComponents.ts). */}
          {extraStatusComponents(status).map((c) => (
            <Readout key={c.key} label={c.label} status={microserviceDotStatus(c)} detail={c.detail} />
          ))}
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
