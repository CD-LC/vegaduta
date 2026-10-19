"use client";

// "In the last 7 days: blocked N, sent for approval M, redacted K" - what an agent's hooks or tool argument
// policies actually DID, read from the audit log through GET /api/audit-log/counts (exact COUNTs over the
// window, not a tally of a truncated page).
//
// Where the numbers come from (verified against core):
//   - Hooks: HookDecisionSink writes one `agent.hook.decision` row per decision with targetId = the agent id.
//     Outcome REJECTED = a call or turn was blocked or hit a rate limit; PENDING_APPROVAL = sent for approval;
//     FAILURE = the hook itself failed. A redaction is recorded as SUCCESS like a plain log line, so it is
//     counted by its detail text ("decision=redacted") in a second call over the SAME window.
//   - Tool argument policies: ToolArgPolicyService writes one `tool.arg_policy.denied` row per refused call
//     with actorId = "agent:<id>" (the targetId there is the tool name).
//
// The endpoint needs VIEW_AUDIT_LOG, and a 403 on it is itself written to the audit log - so without the
// permission this component makes no request and renders nothing. It also renders nothing while loading or
// when the call fails (older core): an absent optional summary must not look like an error, and a number is
// only ever shown when it is real.
import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { useAuth } from "../AuthProvider";
import { ChartFrame, StackedStatusBar, isFiniteNumber, toMagnitude } from "../charts";
import type { ChartTableData } from "../charts";
import BrandText from "../BrandText";

export const HOOK_AUDIT_ACTION = "agent.hook.decision";
export const TOOL_POLICY_AUDIT_ACTION = "tool.arg_policy.denied";
const HOOK_REDACTED_DETAIL = "decision=redacted";
const WINDOW_DAYS = 7;
const DAY_MS = 86_400_000;

type Translate = (key: string, values?: Record<string, string | number>) => string;

interface HookCounts {
  kind: "hooks";
  days: number;
  blocked: number;
  approval: number;
  redacted: number;
  errors: number;
}
interface PolicyCounts {
  kind: "toolPolicy";
  days: number;
  refused: number;
}
type Counts = HookCounts | PolicyCounts;

function outcome(raw: unknown, name: string): number | null {
  const by = raw && typeof raw === "object" ? (raw as { byOutcome?: Record<string, unknown> }).byOutcome : null;
  if (!by || typeof by !== "object") return null;
  const value = by[name];
  return isFiniteNumber(value) ? Math.round(toMagnitude(value)) : 0;
}

function total(raw: unknown): number | null {
  const value = raw && typeof raw === "object" ? (raw as { total?: unknown }).total : null;
  return isFiniteNumber(value) ? Math.round(toMagnitude(value)) : null;
}

/** Whole days in the window the server echoed; falls back to what was asked for. */
function echoedDays(raw: unknown): number {
  const r = (raw && typeof raw === "object" ? raw : {}) as { from?: unknown; to?: unknown };
  const from = typeof r.from === "string" ? Date.parse(r.from) : NaN;
  const to = typeof r.to === "string" ? Date.parse(r.to) : NaN;
  const days = Number.isFinite(from) && Number.isFinite(to) && to > from ? Math.round((to - from) / DAY_MS) : 0;
  return days > 0 ? days : WINDOW_DAYS;
}

async function loadCounts(kind: "hooks" | "toolPolicy", agentId: string): Promise<Counts | null> {
  // One explicit window for every call, so two counts can never straddle different ranges.
  const to = new Date();
  const window = { from: new Date(to.getTime() - WINDOW_DAYS * DAY_MS).toISOString(), to: to.toISOString() };
  if (kind === "toolPolicy") {
    const raw = await api.getAuditLogCounts({ actionType: TOOL_POLICY_AUDIT_ACTION, actorId: `agent:${agentId}`, ...window });
    const refused = outcome(raw, "REJECTED");
    return refused == null ? null : { kind, days: echoedDays(raw), refused };
  }
  const [all, redactedRaw] = await Promise.all([
    api.getAuditLogCounts({ actionType: HOOK_AUDIT_ACTION, targetId: agentId, ...window }),
    api.getAuditLogCounts({ actionType: HOOK_AUDIT_ACTION, targetId: agentId, detailContains: HOOK_REDACTED_DETAIL, ...window }),
  ]);
  const blocked = outcome(all, "REJECTED");
  const approval = outcome(all, "PENDING_APPROVAL");
  const errors = outcome(all, "FAILURE");
  const redacted = total(redactedRaw);
  if (blocked == null || approval == null || errors == null || redacted == null) return null;
  return { kind, days: echoedDays(all), blocked, approval, redacted, errors };
}

export default function GuardrailActivity({ kind, agentId }: { kind: "hooks" | "toolPolicy"; agentId: string }) {
  const t = useTranslations("guardrailActivity") as Translate;
  const { isAdmin, hasPermission } = useAuth();
  const allowed = !!isAdmin || hasPermission("VIEW_AUDIT_LOG");
  const [loaded, setLoaded] = useState<{ key: string; counts: Counts | null } | null>(null);
  const key = `${kind}:${agentId}`;

  useEffect(() => {
    if (!allowed) return undefined;
    let cancelled = false;
    loadCounts(kind, agentId)
      .then((counts) => {
        if (!cancelled) setLoaded({ key: `${kind}:${agentId}`, counts });
      })
      .catch(() => {
        if (!cancelled) setLoaded({ key: `${kind}:${agentId}`, counts: null });
      });
    return () => {
      cancelled = true;
    };
  }, [allowed, kind, agentId]);

  const counts = allowed && loaded && loaded.key === key ? loaded.counts : null;
  if (!counts) return null;

  const days = counts.days;
  const columns: ChartTableData["columns"] = [
    { key: "what", header: t("tableWhat") },
    { key: "count", header: t("tableCount"), align: "right" },
  ];

  if (counts.kind === "toolPolicy") {
    const summary = counts.refused > 0 ? t("policySummary", { days, refused: counts.refused }) : t("policyNone", { days });
    return (
      <div className="mb-2 min-w-0 rounded-md border border-border p-2" data-testid="guardrail-activity-toolPolicy">
        <p className="m-0 text-xs font-medium text-foreground"><BrandText text={t("title")} /></p>
        <p className="m-0 mt-0.5 text-xs text-muted-foreground">{summary}</p>
      </div>
    );
  }

  const sum = counts.blocked + counts.approval + counts.redacted + counts.errors;
  const summary =
    sum > 0
      ? t(counts.errors > 0 ? "hooksSummaryWithErrors" : "hooksSummary", {
          days,
          blocked: counts.blocked,
          approval: counts.approval,
          redacted: counts.redacted,
          errors: counts.errors,
        })
      : t("hooksNone", { days });

  if (sum === 0) {
    return (
      <div className="mt-3 min-w-0 rounded-md border border-border p-3" data-testid="guardrail-activity-hooks">
        <p className="m-0 text-xs font-medium text-foreground"><BrandText text={t("title")} /></p>
        <p className="m-0 mt-0.5 text-xs text-muted-foreground">{summary}</p>
      </div>
    );
  }

  const table: ChartTableData = {
    columns,
    rows: [
      { id: "blocked", what: t("blocked"), count: counts.blocked },
      { id: "approval", what: t("approval"), count: counts.approval },
      { id: "redacted", what: t("redacted"), count: counts.redacted },
      { id: "errors", what: t("errors"), count: counts.errors },
    ],
    caption: t("tableCaption", { days }),
  };

  return (
    <div className="mt-3 min-w-0 rounded-md border border-border p-3" data-testid="guardrail-activity-hooks">
      <ChartFrame title={t("title")} summary={summary} table={table}>
        <StackedStatusBar
          segments={[
            { key: "blocked", label: t("blocked"), count: counts.blocked, state: "error" },
            { key: "approval", label: t("approval"), count: counts.approval, state: "warning" },
            { key: "redacted", label: t("redacted"), count: counts.redacted, state: "running" },
            ...(counts.errors > 0 ? [{ key: "errors", label: t("errors"), count: counts.errors, state: "idle" as const }] : []),
          ]}
        />
      </ChartFrame>
    </div>
  );
}
