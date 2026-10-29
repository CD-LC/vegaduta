"use client";

import { timeAgo as sharedTimeAgo } from "../../lib/format";
import { useEffect, useMemo, useState } from "react";
import { RefreshCw, CheckCircle2, AlertTriangle, AlertOctagon, Search } from "lucide-react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState } from "../../components/ax/page-kit";
import { Input } from "../../components/ui/input";
import { Skeleton } from "../../components/ui/skeleton";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import PaginationControls from "../../components/PaginationControls";
import { usePagedList } from "./pagedLoad";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";

// Admin Issues portal (#425/#426): deduplicated failure records, recorded
// SYNCHRONOUSLY at three points (GlobalExceptionHandler's catch-all,
// WorkflowExecutionEngine's FAILED runs, LoggingToolCallback's tool errors)
// rather than through the Kafka/outbox event pipeline the rest of this
// platform's domain events use - see PlatformIssueService's javadoc for why
// "keep sync where needed" applies here: an issue must still be captured
// even if the broker itself is down at the exact moment something fails.
// Recurring failures accumulate onto one row (occurrenceCount/lastSeenAt)
// instead of flooding this list, so what's shown here is "distinct
// problems", not a raw error firehose.
const STATUS_TABS = [
  { value: "", labelKey: "statusAll" },
  { value: "OPEN", labelKey: "statusOpen" },
  { value: "ACKNOWLEDGED", labelKey: "statusAcknowledged" },
  { value: "RESOLVED", labelKey: "statusResolved" },
];

// GET /api/admin/issues pages on the server (limit/offset + X-Total-Count). An older core that
// does not is detected by usePagedList, which then loads up to this many rows and pages them here.
const LEGACY_CAP = 200;

interface Issue {
  id: string;
  status: string;
  severity: string;
  title: string;
  message?: string;
  source: string;
  occurrenceCount: number;
  firstSeenAt?: string | null;
  lastSeenAt?: string | null;
  acknowledgedBy?: string | null;
}

const SOURCE_LABEL_KEYS: Record<string, string> = {
  WORKFLOW_RUN: "sourceWorkflow",
  TOOL_CALL: "sourceToolCall",
  EXCEPTION: "sourceException",
  // Gap 2 - sampled async LLM-as-judge signal (LlmQualityJudgeService),
  // always WARNING severity, already renders correctly via the existing
  // issue.severity === "ERROR" ? AlertOctagon : AlertTriangle icon check
  // below - only the badge label needed a mapping.
  LLM_QUALITY: "sourceQualityCheck",
  EVENT_OUTBOX: "sourceEventDelivery",
  // Fallback observability follow-up (#954) - AgentChatService.java.
  // WARNING severity when the fallback rescued the turn, ERROR when it
  // couldn't - same existing severity-icon check handles both.
  PROVIDER_FALLBACK: "sourceProviderFallback",
};

function SourceBadge({ source }: { source: string }) {
  const t = useTranslations("adminIssuesPage");
  const labelKey = SOURCE_LABEL_KEYS[source];
  const label = labelKey ? t(labelKey) : source;
  return <Badge variant="secondary">{label}</Badge>;
}

function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("adminIssuesPage");
  if (status === "RESOLVED")
    return (
      <Badge
        variant="outline"
        className="border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
      >
        {t("statusResolved")}
      </Badge>
    );
  if (status === "ACKNOWLEDGED") return <Badge variant="secondary">{t("statusAcknowledged")}</Badge>;
  return (
    <Badge variant="outline" className="border-[var(--danger-border)] bg-[var(--danger-bg)] text-[var(--danger-text)]">
      {t("statusOpen")}
    </Badge>
  );
}

function timeAgo(iso: string | null | undefined, t: (key: string, values?: Record<string, unknown>) => string) {
  return sharedTimeAgo(iso, {
    rounding: "floor",
    maxDays: Infinity,
    labels: {
      none: t("timeAgoNone"),
      justNow: t("timeAgoJustNow"),
      minutes: (mins) => t("timeAgoMinutes", { mins }),
      hours: (hours) => t("timeAgoHours", { hours }),
      days: (days) => t("timeAgoDays", { days }),
    },
  });
}

export default function IssuesPage() {
  // VIEW_AUDIT_LOG scope (granular RBAC, 2026-09-23) - the issues feed is
  // the same /api/admin/** observability surface as the audit trail, gated
  // by the same backend permission.
  const allowed = useRequireScope("VIEW_AUDIT_LOG");
  const t = useTranslations("adminIssuesPage");
  const [statusFilter, setStatusFilter] = useState("OPEN");
  const [busyId, setBusyId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const tn = useTranslations("auditNotifications");

  // Server paging: only the visible page is fetched. Search narrows the rows of
  // the current page (the endpoint has no text filter).
  const list = usePagedList<Issue>(
    allowed ? `/api/admin/issues${statusFilter ? `?status=${encodeURIComponent(statusFilter)}` : ""}` : null,
    LEGACY_CAP
  );
  const { rows: issues, loading, error: loadError, reload: load, page, pageSize, setPage, setPageSize } = list;

  const filteredIssues = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return issues;
    return issues.filter((i) =>
      [i.title, i.message, i.source, i.acknowledgedBy].some((f) => (f || "").toLowerCase().includes(q))
    );
  }, [issues, search]);

  // A resolved/acknowledged row can empty the last page while viewing it: step back.
  useEffect(() => {
    if (!loading && issues.length === 0 && page > 1 && (list.total ?? 0) > 0) setPage(page - 1);
  }, [loading, issues.length, page, list.total, setPage]);

  const reportError = loadError;
  useEffect(() => {
    if (reportError) toast.error(t("loadIssuesError", { error: reportError }));
  }, [reportError, t]);

  useEffect(() => {
    if (!allowed) return undefined;
    const interval = setInterval(() => {
      void load(true);
    }, 10000);
    return () => clearInterval(interval);
  }, [allowed, load]);
  // Hold the transition cover until the first issue load resolves (or the page
  // redirects a non-scoped caller away).
  usePageReady(!loading || !allowed);

  if (!allowed) return null;

  async function handleAcknowledge(id: string) {
    setBusyId(id);
    try {
      await api.acknowledgeIssue(id);
      toast.success(t("acknowledgedSuccessToast"));
      await load();
    } catch (e) {
      toast.error(t("acknowledgeErrorToast", { error: errorMessage(e) }));
    } finally {
      setBusyId(null);
    }
  }

  async function handleResolve(id: string) {
    setBusyId(id);
    try {
      await api.resolveIssue(id);
      toast.success(t("resolvedSuccessToast"));
      await load();
    } catch (e) {
      toast.error(t("resolveErrorToast", { error: errorMessage(e) }));
    } finally {
      setBusyId(null);
    }
  }

  // Real count for the OPEN tab (the server total); other tabs show no number.
  const openCount = statusFilter === "OPEN" ? (list.total ?? issues.length) : 0;
  const atCap = list.total === undefined && list.hasMore;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={t("hintText")}
        actions={
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="rounded-full"
            onClick={() => void load()}
            style={{ marginBottom: 0 }}
          >
            <RefreshCw size={14} aria-hidden="true" /> {t("refreshButton")}
          </Button>
        }
      />

      {/* Filter pills (same shared .ax-filter-pill pattern as the Templates
       * gallery) instead of the old solid `primary` button on the active
       * tab - a filter should never be the loudest element on a monitoring
       * screen; the severity colors in the list below are the real signal. */}
      <div className="ax-filter-pills" style={{ marginBottom: 16 }}>
        {STATUS_TABS.map((tab) => (
          <Button
            key={tab.value}
            type="button"
            variant={statusFilter === tab.value ? "default" : "outline"}
            size="sm"
            className="rounded-full"
            onClick={() => {
              setStatusFilter(tab.value);
              list.resetPage();
            }}
            aria-pressed={statusFilter === tab.value}
            style={{ marginBottom: 0 }}
          >
            {t(tab.labelKey)}
            {tab.value === "OPEN" && openCount > 0 ? ` (${openCount}${atCap ? "+" : ""})` : ""}
          </Button>
        ))}
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="relative w-full sm:w-80">
          <Search
            size={14}
            aria-hidden="true"
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={tn("issuesSearchPlaceholder")}
            aria-label={tn("issuesSearchPlaceholder")}
            className="ps-9"
          />
        </div>
      </div>

      <div className="panel" style={{ padding: 0, overflow: "hidden" }}>
        {loading && issues.length === 0 ? (
          <div className="space-y-3 p-5" aria-busy="true" aria-label={t("loadingText")}>
            {[0, 1, 2, 3].map((n) => (
              <Skeleton key={n} className="h-14 w-full" />
            ))}
          </div>
        ) : loadError && issues.length === 0 ? (
          <EmptyState
            icon={AlertOctagon}
            title={t("loadIssuesError", { error: loadError })}
            action={
              <Button type="button" variant="outline" size="sm" onClick={() => void load()}>
                {tn("retry")}
              </Button>
            }
          />
        ) : filteredIssues.length === 0 ? (
          <EmptyState
            icon={CheckCircle2}
            title={
              search.trim()
                ? tn("issuesNoMatch")
                : t("emptyIssuesText", { status: statusFilter ? statusFilter.toLowerCase() : "" })
            }
          />
        ) : (
          <div style={{ display: "flex", flexDirection: "column" }}>
            {filteredIssues.map((issue: Issue) => (
              <div
                key={issue.id}
                className="list-item"
                style={{
                  display: "flex",
                  alignItems: "flex-start",
                  gap: 12,
                  margin: 0,
                  padding: "16px 20px",
                  border: 0,
                  borderRadius: 0,
                  // Severity rail: a 3px inset color bar per row (plus the
                  // hairline row divider, both drawn as one inset shadow so
                  // the .list-item base border can stay fully off) - makes
                  // ERROR (red) vs WARNING (amber) scannable down the whole
                  // list at a glance without reading a single icon.
                  boxShadow:
                    issue.severity === "ERROR"
                      ? "inset 3px 0 0 var(--ax-red), inset 0 -1px 0 var(--ax-border)"
                      : "inset 3px 0 0 var(--ax-amber), inset 0 -1px 0 var(--ax-border)",
                  // flexWrap (not a media query) so the Acknowledge/Resolve
                  // action buttons drop to their own line automatically
                  // whenever the title+badges content column doesn't have
                  // room to sit beside them - happens naturally around
                  // phone widths without needing a breakpoint, and costs
                  // nothing on desktop where there's always room.
                  flexWrap: "wrap",
                }}
              >
                <div
                  aria-hidden="true"
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    justifyContent: "center",
                    width: 28,
                    height: 28,
                    flexShrink: 0,
                    borderRadius: "var(--radius-lg)",
                    color: issue.severity === "ERROR" ? "var(--ax-red)" : "var(--ax-amber)",
                    background:
                      issue.severity === "ERROR"
                        ? "color-mix(in srgb, var(--ax-red) 12%, transparent)"
                        : "color-mix(in srgb, var(--ax-amber) 12%, transparent)",
                  }}
                >
                  {issue.severity === "ERROR" ? (
                    <AlertOctagon size={15} aria-hidden="true" />
                  ) : (
                    <AlertTriangle size={15} aria-hidden="true" />
                  )}
                </div>
                <span className="sr-only">
                  {issue.severity === "ERROR" ? t("severityError") : t("severityWarning")}
                </span>
                {/* flex-basis 220px (not the bare "flex: 1" == 0% basis this
                 * used to have) so the row's flex-wrap above actually has
                 * something to wrap on - a 0%-basis item never contributes
                 * enough hypothetical width to trigger a wrap, it just gets
                 * squeezed down to nothing by the Acknowledge/Resolve
                 * buttons' flexShrink:0 instead, which is how title+badges
                 * were quietly getting crushed into a sliver on phone
                 * widths rather than the button pair dropping to its own
                 * line underneath. */}
                <div style={{ flex: "1 1 220px", minWidth: 0 }}>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", flexWrap: "wrap" }}>
                    <strong style={{ fontSize: 13 }}>{issue.title}</strong>
                    <SourceBadge source={issue.source} />
                    <StatusBadge status={issue.status} />
                    {issue.occurrenceCount > 1 && (
                      <Badge variant="secondary">{t("occurrenceCountBadge", { count: issue.occurrenceCount })}</Badge>
                    )}
                  </div>
                  {issue.message && (
                    <p
                      className="hint"
                      style={{
                        margin: "4px 0 0",
                        fontFamily: "var(--font-mono), monospace",
                        fontSize: "var(--fs-caption)",
                        wordBreak: "break-word",
                        whiteSpace: "pre-wrap",
                      }}
                    >
                      {issue.message}
                    </p>
                  )}
                  <p className="hint" style={{ margin: "6px 0 0", fontSize: "var(--fs-caption)" }}>
                    {t("firstLastSeenText", {
                      firstSeen: timeAgo(issue.firstSeenAt, t),
                      lastSeen: timeAgo(issue.lastSeenAt, t),
                    })}
                    {issue.acknowledgedBy ? t("acknowledgedBySuffix", { name: issue.acknowledgedBy }) : ""}
                  </p>
                </div>
                {/* Quiet neutral secondary buttons (base `button` styling),
                 * not accent-tinted pills - with dozens of rows, colored
                 * actions on every row out-shout the severity colors that
                 * actually matter here. */}
                <div style={{ display: "flex", gap: 8, flexShrink: 0, alignItems: "center" }}>
                  {issue.status === "OPEN" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={busyId === issue.id}
                      onClick={() => handleAcknowledge(issue.id)}
                      style={{ marginBottom: 0 }}
                    >
                      {t("acknowledgeButton")}
                    </Button>
                  )}
                  {issue.status !== "RESOLVED" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={busyId === issue.id}
                      onClick={() => handleResolve(issue.id)}
                      style={{ marginBottom: 0 }}
                    >
                      {t("resolveButton")}
                    </Button>
                  )}
                </div>
              </div>
            ))}
            <div style={{ padding: "8px 20px" }}>
              <PaginationControls
                page={page}
                totalItems={list.total}
                hasMore={list.hasMore}
                pageItemCount={issues.length}
                pageSize={pageSize}
                onPageChange={setPage}
                onPageSizeChange={setPageSize}
                itemLabel={t("recordsLabel")}
                loading={loading}
              />
            </div>
          </div>
        )}
      </div>
    </AppShell>
  );
}
