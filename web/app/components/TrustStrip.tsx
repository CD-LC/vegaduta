"use client";

import { useEffect, useState } from "react";
import { ShieldCheck, Clock, ExternalLink, CheckCircle2 } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import { useAuth } from "./AuthProvider";
import { auth } from "../lib/auth";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import type { SystemStatusSummary } from "./dashboard/SystemStatusPanel";
import { isLocalAuth } from "../lib/authMode";

function formatCountdown(totalMs: number | null): string | null {
  if (totalMs === null) return null;
  const s = Math.max(0, Math.floor(totalMs / 1000));
  const mins = Math.floor(s / 60);
  const secs = s % 60;
  // A countdown is only useful while there is something to count down to. A
  // realm whose refresh-token exp is effectively unbounded renders as
  // multi-million-minute noise ("29843540:05") and stretches the pill wide
  // enough to shove the rest of the topbar off-screen. Past a day, say
  // nothing - the popover still lists the raw expiry state below.
  if (mins >= 24 * 60) return null;
  if (mins >= 100) return `${Math.floor(mins / 60)}h ${String(mins % 60).padStart(2, "0")}m`;
  return `${mins}:${String(secs).padStart(2, "0")}`;
}

interface AuditRow {
  id: string | number;
  actionType?: string;
  targetType?: string;
  detail?: string;
  createdAt?: string;
}

/**
 * Replaces the plain "healthy/offline" state-pill with a clickable strip:
 * identity + a real link to Keycloak's Account Console (where MFA/sessions
 * actually live - this app never renders its own security settings, see
 * AppShell's accountUrl comment), the same coreHealthy signal but expandable
 * into the real per-subsystem breakdown SystemStatusPanel already reads, and
 * a persistent session-expiry countdown using the exact same
 * auth.getSessionExpiryMs() IdleLogout.tsx already relies on - just shown
 * continuously instead of only in the final 120s.
 *
 * Deliberately does NOT claim TLS version, MFA state, or a compliance badge -
 * none of that is exposed anywhere in this app today (see the Console Bridge
 * design session's data-availability audit). A security indicator asserting
 * something nobody verified is worse than none at all.
 */
export default function TrustStrip({
  coreHealthy,
  status,
}: {
  coreHealthy: boolean;
  status: SystemStatusSummary | null | undefined;
}) {
  const tNav = useTranslations("nav");
  const { username, hasPermission, accountUrl } = useAuth();
  // VIEW_AUDIT_LOG scope (granular RBAC, 2026-09-23) - the strip's
  // last-3-audit-rows fetch needs the same permission the audit pages do.
  const canViewAudit = hasPermission("VIEW_AUDIT_LOG");
  const [open, setOpen] = useState(false);
  const [countdown, setCountdown] = useState<string | null>(null);
  const [auditRows, setAuditRows] = useState<AuditRow[] | null>(null);

  useEffect(() => {
    function tick() {
      setCountdown(formatCountdown(auth.getSessionExpiryMs()));
    }
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    if (!open || !canViewAudit || auditRows !== null) return undefined;
    let cancelled = false;
    api
      .listAuditLog()
      .then((rows: AuditRow[]) => {
        if (!cancelled) setAuditRows(Array.isArray(rows) ? rows.slice(0, 3) : []);
      })
      .catch(() => {
        if (!cancelled) setAuditRows([]);
      });
    return () => {
      cancelled = true;
    };
  }, [open, canViewAudit, auditRows]);

  const profileHref = accountUrl?.() ?? null;

  // A subsystem this deployment does not run (NOT_CONFIGURED - e.g. Kafka and the sidecar on a
  // personal install) is left out rather than painted "down".
  const upOrNull = (c?: { status?: string } | null) => (!c || c.status === "NOT_CONFIGURED" ? null : c.status === "UP");
  const subsystems: Array<{ label: string; ok: boolean | null }> = [
    { label: "Database", ok: upOrNull(status?.database) },
    { label: "Redis", ok: upOrNull(status?.redis) },
    { label: "Kafka", ok: upOrNull(status?.kafka) },
    { label: "Sidecar", ok: upOrNull(status?.sidecar) },
  ].filter((s) => s.ok !== null);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="state-pill topbar-status hidden sm:inline-flex"
          title={coreHealthy ? tNav("systemHealthy") : tNav("systemOffline")}
          style={{
            ["--state" as string]: coreHealthy ? "var(--state-success)" : "var(--state-error)",
            border: "none",
            cursor: "pointer",
          }}
        >
          <span className={`state-dot ${coreHealthy ? "state-dot-live" : ""}`} aria-hidden="true" />
          <span className="topbar-label topbar-status-label">
            {coreHealthy ? tNav("systemHealthy") : tNav("systemOffline")}
          </span>
          {countdown && (
            <span
              className="topbar-label topbar-status-label ml-1.5 font-mono text-[10.5px] tabular-nums"
              style={{ color: "var(--text-faint)" }}
            >
              {countdown}
            </span>
          )}
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-3">
        <div className="mb-2.5 flex items-center gap-2 border-b border-[var(--surface-border)] pb-2.5">
          <ShieldCheck aria-hidden="true" size={15} style={{ color: "var(--state-success)" }} />
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold" style={{ color: "var(--text-primary)" }}>
              {username || tNav("railHome")}
            </p>
            <p className="text-[11px]" style={{ color: "var(--text-faint)" }}>
              {isLocalAuth() ? "Signed in on this computer" : "Signed in via SSO"}
            </p>
          </div>
        </div>

        <div className="mb-2.5 flex items-center justify-between text-xs" style={{ color: "var(--text-secondary)" }}>
          <span className="flex items-center gap-1.5">
            <Clock aria-hidden="true" size={12} style={{ color: "var(--text-faint)" }} />
            Session expires in
          </span>
          <span className="font-mono tabular-nums" style={{ color: "var(--text-primary)" }}>
            {countdown ?? "—"}
          </span>
        </div>

        {subsystems.length > 0 && (
          <div className="mb-2.5 space-y-1 border-t border-[var(--surface-border)] pt-2.5">
            <p
              className="mb-1 text-[10px] font-semibold uppercase tracking-wide"
              style={{ color: "var(--text-faint)" }}
            >
              Systems
            </p>
            {subsystems.map((s) => (
              <div
                key={s.label}
                className="flex items-center justify-between text-xs"
                style={{ color: "var(--text-secondary)" }}
              >
                <span>{s.label}</span>
                <span
                  className="flex items-center gap-1 font-medium"
                  style={{ color: s.ok ? "var(--state-success)" : "var(--state-error)" }}
                >
                  <CheckCircle2 aria-hidden="true" size={11} />
                  {s.ok ? "Up" : "Down"}
                </span>
              </div>
            ))}
          </div>
        )}

        {canViewAudit && auditRows && auditRows.length > 0 && (
          <div className="mb-2.5 space-y-1 border-t border-[var(--surface-border)] pt-2.5">
            <p
              className="mb-1 text-[10px] font-semibold uppercase tracking-wide"
              style={{ color: "var(--text-faint)" }}
            >
              Recent activity
            </p>
            {auditRows.map((row) => (
              <div
                key={row.id}
                className="flex items-center justify-between gap-2 text-[11px]"
                style={{ color: "var(--text-secondary)" }}
              >
                <span className="truncate">{row.actionType || row.detail || "—"}</span>
              </div>
            ))}
            <a
              href="/admin/audit-tail"
              className="mt-1 flex items-center gap-1 text-[11px] font-medium"
              style={{ color: "var(--accent-500, #6d5bff)" }}
            >
              View full trace <ExternalLink aria-hidden="true" size={10} />
            </a>
          </div>
        )}

        {profileHref && (
          <a
            href={profileHref}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1.5 border-t border-[var(--surface-border)] pt-2.5 text-[11px] font-medium"
            style={{ color: "var(--accent-500, #6d5bff)" }}
          >
            Manage security (2FA, sessions) <ExternalLink aria-hidden="true" size={11} />
          </a>
        )}
      </PopoverContent>
    </Popover>
  );
}
