"use client";

import { useCallback, useEffect, useState } from "react";
import { RefreshCw, CheckCircle2, ChevronDown, ChevronRight } from "lucide-react";
import { api } from "../lib/api";
import { fetchPaged } from "../lib/pagedFetch";
import PaginationControls from "../components/PaginationControls";
import { pageForSizeChange } from "../lib/usePagination";
import { Skeleton } from "../components/ui/skeleton";
import AdminShell from "../components/AdminShell";
import { PageHint, InfoNote } from "../components/sections/common";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Textarea } from "../components/ui/textarea";
import { Card, CardContent } from "../components/ui/card";
import { AdminErrorState } from "../components/admin/AdminKit";

// Store compliance (2026-09-29, docs/plans/STORE-COMPLIANCE-CONTRACT.md §1) -
// Google Play's AI-Generated Content policy requires users to be able to
// report offensive AI output "to developers" from inside the app. Those
// reports land in control_plane.ai_content_report (every tenant) and are
// triaged here: read the reported output (and the prompt, when the app sent
// it), then mark it REVIEWED / ACTIONED / DISMISSED with a note.
//
// Same plain, dense list+action posture as the MCP Review Queue. Content is
// collapsed by default - it is, by definition, content somebody found
// offensive.

type Report = {
  id: string;
  tenantId: string | null;
  reporterId: string | null;
  surface: string;
  reason: string;
  comment: string | null;
  content: string;
  prompt: string | null;
  agentId: string | null;
  sessionId: string | null;
  messageRef: string | null;
  onDevice: boolean;
  appPlatform: string | null;
  appVersion: string | null;
  status: string;
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string | null;
};

const FILTERS = ["OPEN", "REVIEWED", "ACTIONED", "DISMISSED", ""] as const;
const REVIEW_STATUSES = ["REVIEWED", "ACTIONED", "DISMISSED"] as const;

function filterLabel(status: string): string {
  return status ? status.charAt(0) + status.slice(1).toLowerCase() : "All";
}

function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function statusVariant(status: string): "default" | "secondary" | "destructive" | "outline" {
  if (status === "OPEN") return "destructive";
  if (status === "ACTIONED") return "default";
  return "secondary";
}

export default function AiReportsPage() {
  const [filter, setFilter] = useState<string>("OPEN");
  const [rows, setRows] = useState<Report[]>([]);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);

  const refresh = useCallback(
    async (isRefresh = false) => {
      if (isRefresh) setRefreshing(true);
      else setLoading(true);
      try {
        const qs = new URLSearchParams({ limit: String(pageSize), offset: String((page - 1) * pageSize) });
        if (filter) qs.set("status", filter);
        const res = await fetchPaged<any>(`/api/platform/ai-reports?${qs}`);
        setRows(Array.isArray(res.body?.reports) ? res.body.reports : []);
        setTotal(res.total ?? undefined);
        setError(null);
      } catch (err) {
        setRows([]);
        setError((err instanceof Error && err.message) || "Could not load AI content reports.");
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [filter, page, pageSize]
  );

  useEffect(() => {
    refresh().catch((e) => console.error("Could not load AI content reports", e));
  }, [refresh]);

  async function handleReview(row: Report, status: string) {
    setBusyId(row.id);
    try {
      const updated: Report = await api.reviewAiReport(row.id, status, (notes[row.id] ?? row.reviewNote ?? "").trim());
      setRows((prev) =>
        filter && updated.status !== filter
          ? prev.filter((r) => r.id !== row.id)
          : prev.map((r) => (r.id === row.id ? updated : r))
      );
      toast.success(`Report marked ${status.toLowerCase()}.`);
    } catch (err) {
      toast.error((err instanceof Error && err.message) || "Could not update this report.");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <AdminShell
      title="AI Content Reports"
      actions={
        <Button variant="outline" size="sm" onClick={() => refresh(true)} disabled={refreshing || loading}>
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Reports of offensive or harmful AI output, filed from the Report button in the apps, across every tenant. Review
        each one and record what was done.
      </PageHint>
      <InfoNote>
        Content is shown exactly as the reporter&rsquo;s app sent it and may be offensive. &ldquo;On-device&rdquo;
        reports came from the phone&rsquo;s local model, not a cloud provider.
      </InfoNote>

      <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Filter by status">
        {FILTERS.map((s) => (
          <Button
            key={s || "ALL"}
            size="sm"
            variant={filter === s ? "default" : "outline"}
            onClick={() => {
              setPage(1);
              setFilter(s);
            }}
            aria-pressed={filter === s}
          >
            {filterLabel(s)}
          </Button>
        ))}
      </div>

      {error && <AdminErrorState className="mb-4" message={error} onRetry={() => refresh(true)} />}

      <Card>
        <CardContent className="p-0">
          {loading ? (
            <div className="space-y-2 p-4">
              <Skeleton className="h-12 w-full" />
              <Skeleton className="h-12 w-full" />
              <Skeleton className="h-12 w-full" />
            </div>
          ) : rows.length === 0 ? (
            <div className="flex flex-col items-center gap-2 p-8 text-center">
              <CheckCircle2 size={20} className="text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                No {filter ? filterLabel(filter).toLowerCase() : ""} reports.
              </p>
            </div>
          ) : (
            <div className="divide-y divide-border">
              {rows.map((row) => {
                const open = expanded === row.id;
                const busy = busyId === row.id;
                return (
                  <div key={row.id} className="space-y-2 p-3">
                    <button
                      type="button"
                      className="flex w-full flex-wrap items-center gap-2 text-left"
                      onClick={() => setExpanded(open ? null : row.id)}
                      aria-expanded={open}
                    >
                      {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                      <Badge variant={statusVariant(row.status)} className="text-[10px] uppercase">
                        {row.status}
                      </Badge>
                      <span className="font-medium text-foreground">{row.reason.replace(/_/g, " ")}</span>
                      <span className="text-xs text-muted-foreground">
                        {row.surface}
                        {row.appPlatform ? ` · ${row.appPlatform}` : ""}
                        {row.appVersion ? ` ${row.appVersion}` : ""}
                        {row.onDevice ? " · on-device" : ""}
                      </span>
                      <span className="ml-auto text-xs text-muted-foreground">{formatDateTime(row.createdAt)}</span>
                    </button>
                    <div className="text-xs text-muted-foreground">
                      Tenant {row.tenantId || "—"} · Reporter {row.reporterId || "(deleted account)"}
                      {row.agentId ? ` · Agent ${row.agentId}` : ""}
                      {row.sessionId ? ` · Session ${row.sessionId}` : ""}
                      {row.messageRef ? ` · Message ${row.messageRef}` : ""}
                    </div>
                    {row.comment && <p className="text-sm text-foreground">&ldquo;{row.comment}&rdquo;</p>}
                    {open && (
                      <div className="space-y-2">
                        {row.prompt && (
                          <div>
                            <div className="text-xs font-medium text-muted-foreground">Prompt</div>
                            <pre className="max-h-60 overflow-auto whitespace-pre-wrap break-words rounded bg-muted px-2 py-1.5 text-xs">
                              {row.prompt}
                            </pre>
                          </div>
                        )}
                        <div>
                          <div className="text-xs font-medium text-muted-foreground">Reported AI output</div>
                          <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded bg-muted px-2 py-1.5 text-xs">
                            {row.content}
                          </pre>
                        </div>
                        {row.reviewedAt && (
                          <div className="text-xs text-muted-foreground">
                            Last reviewed {formatDateTime(row.reviewedAt)} by {row.reviewedBy || "—"}
                          </div>
                        )}
                        <Textarea
                          value={notes[row.id] ?? row.reviewNote ?? ""}
                          onChange={(e) => setNotes((prev) => ({ ...prev, [row.id]: e.target.value }))}
                          placeholder="Review note (what you checked or did)"
                          maxLength={2000}
                          rows={2}
                          aria-label="Review note"
                        />
                        <div className="flex flex-wrap gap-2">
                          {REVIEW_STATUSES.map((s) => (
                            <Button
                              key={s}
                              size="sm"
                              variant={s === "ACTIONED" ? "default" : "outline"}
                              disabled={busy}
                              onClick={() => handleReview(row, s)}
                            >
                              Mark {s.toLowerCase()}
                            </Button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>
      <PaginationControls
        page={page}
        pageSize={pageSize}
        totalItems={total}
        hasMore={total === undefined && rows.length === pageSize}
        pageItemCount={rows.length}
        loading={loading || refreshing}
        itemLabel="reports"
        onPageChange={setPage}
        onPageSizeChange={(n) => {
          setPage(pageForSizeChange(page, pageSize, n));
          setPageSize(n);
        }}
      />
    </AdminShell>
  );
}
