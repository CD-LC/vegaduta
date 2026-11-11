"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { ListChecks, RefreshCw, Search } from "lucide-react";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { EmptyState } from "../ax/page-kit";
import PaginationControls from "../PaginationControls";
import { pageForSizeChange } from "../../lib/usePagination";
import { useTranslations } from "../../lib/i18n";
import { ListError, ListSkeleton } from "../tenantLists/ListStates";
import { isLiveStatus, jobStatusTone, jobStatusText, formatDuration, formatWhen } from "./jobStatus";
import { listTenantJobsPage } from "./jobsPaged";

const LIVE_REFRESH_MS = 5000;

// Filters map to what the SERVER understands. "live" is its own sentinel,
// expanded server-side to AgentJobStatus.live() - see jobStatus.ts for why the
// three live states are never enumerated client-side.
const FILTERS: Array<{ key: string; labelKey: string; status?: string[] }> = [
  { key: "live", labelKey: "jobsFilterLive", status: ["live"] },
  { key: "all", labelKey: "jobsFilterAll" },
  { key: "succeeded", labelKey: "jobsFilterSucceeded", status: ["SUCCEEDED"] },
  { key: "failed", labelKey: "jobsFilterFailed", status: ["FAILED"] },
  { key: "cancelled", labelKey: "jobsFilterCancelled", status: ["CANCELLED"] },
];

export default function JobsList({ agentId }: { agentId?: string | null }) {
  const [filter, setFilter] = useState("live");
  const t = useTranslations("tenantLists");
  const tj = useTranslations("jobsPage");
  const tg = useTranslations("f2GapsTenant");
  const [search, setSearch] = useState("");
  // X-Total-Count from GET /api/agent-jobs; undefined on a core that does not send it yet.
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const offset = (page - 1) * pageSize;
  const [rows, setRows] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    async (opts: { quiet?: boolean } = {}) => {
      if (!opts.quiet) setLoading(true);
      try {
        const status = FILTERS.find((f) => f.key === filter)?.status;
        const data = await listTenantJobsPage({
          ...(status ? { status } : {}),
          ...(agentId ? { agentId } : {}),
          limit: pageSize,
          offset,
        });
        setRows(data.rows);
        setTotal(data.total);
        setError(null);
      } catch (err: any) {
        setError(err?.message || "");
      } finally {
        setLoading(false);
      }
    },
    [filter, offset, pageSize, agentId]
  );

  useEffect(() => {
    void load();
  }, [load]);

  // Keep refreshing only while something is actually in flight, and only when
  // the tab is visible - the same posture as the dashboard's telemetry poll.
  // A list of finished jobs is static; polling it forever is pure waste.
  const hasLive = rows.some((r) => isLiveStatus(r.status));
  useEffect(() => {
    if (!hasLive) return undefined;
    const id = setInterval(() => {
      if (!document.hidden) void load({ quiet: true });
    }, LIVE_REFRESH_MS);
    return () => clearInterval(id);
  }, [hasLive, load]);

  // The endpoint has no text search, so the box narrows the page that is loaded (not the whole history).
  const needle = search.trim().toLowerCase();
  const visibleRows = useMemo(
    () =>
      needle
        ? rows.filter((r) =>
            [r.goalPreview, r.agentName, r.progressNote, r.errorCode].some(
              (v) => typeof v === "string" && v.toLowerCase().includes(needle)
            )
          )
        : rows,
    [rows, needle]
  );

  return (
    <div className="space-y-4">
      {total !== undefined ? (
        <p className="text-sm text-muted-foreground" role="status">
          {tg("jobsTotal", { count: total })}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center gap-2">
        {FILTERS.map((f) => (
          <Button
            key={f.key}
            size="sm"
            variant={filter === f.key ? "default" : "outline"}
            aria-pressed={filter === f.key}
            onClick={() => {
              setFilter(f.key);
              setPage(1);
            }}
          >
            {t(f.labelKey)}
          </Button>
        ))}
        <div className="relative ms-auto min-w-0 basis-52">
          <Search
            size={14}
            aria-hidden="true"
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            className="ps-8"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={tj("searchPlaceholder")}
            aria-label={tj("searchLabel")}
          />
        </div>
        <Button size="sm" variant="ghost" onClick={() => load()} aria-label={t("jobsRefresh")}>
          <RefreshCw className={loading ? "size-4 animate-spin" : "size-4"} aria-hidden="true" />
        </Button>
      </div>
      {needle && rows.length > 0 && (
        <p role="note" className="text-xs text-muted-foreground">
          {tj("searchScope", { shown: visibleRows.length, loaded: rows.length })}
        </p>
      )}

      {error !== null && <ListError message={error || t("jobsLoadFailed")} onRetry={() => void load()} />}

      {loading && rows.length === 0 && error === null && <ListSkeleton rows={5} label={t("jobsLoading")} />}

      {rows.length === 0 && !loading && error === null ? (
        <EmptyState
          icon={ListChecks}
          title={page > 1 ? t("jobsPageEmptyTitle") : t("jobsEmptyTitle")}
          description={page > 1 ? t("jobsPageEmptyHint") : t("jobsEmptyHint")}
        />
      ) : rows.length > 0 && visibleRows.length === 0 ? (
        <EmptyState icon={Search} title={tj("noMatchTitle")} description={tj("noMatchBody")} />
      ) : rows.length > 0 ? (
        <Card className="gap-0 overflow-hidden p-0">
          <ul className="divide-y divide-border">
            {visibleRows.map((job) => {
              const tone = jobStatusTone(job.status);
              const duration = formatDuration(job.startedAt, job.finishedAt);
              return (
                <li key={job.id}>
                  <Link
                    href={`/jobs/${job.agentId}/${job.id}`}
                    className="flex flex-col gap-2 px-4 py-3 transition-colors hover:bg-muted/40 sm:flex-row sm:items-center sm:gap-4"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium text-foreground">
                        {job.goalPreview || t("jobsNoGoal")}
                      </p>
                      <p className="mt-0.5 truncate text-xs text-muted-foreground">
                        {job.agentName || t("jobsUnknownAgent")}
                        {job.progressNote ? ` · ${job.progressNote}` : ""}
                        {job.errorCode ? ` · ${job.errorCode}` : ""}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-3">
                      {duration && <span className="tabular-nums text-xs text-muted-foreground">{duration}</span>}
                      <span className="hidden text-xs text-muted-foreground sm:inline">
                        {formatWhen(job.createdAt)}
                      </span>
                      <Badge variant="outline" className={tone.className}>
                        {jobStatusText(tone, tj)}
                      </Badge>
                    </div>
                  </Link>
                </li>
              );
            })}
          </ul>
        </Card>
      ) : null}

      {/* The endpoint filters rows the caller may not view AFTER paging. When it sends X-Total-Count (a count of
          rows matching the filters, before that visibility filter) the pager shows real totals; on an older core
          there is no total, so it offers Next whenever this page returned rows and never strands an operator with
          partial visibility. An empty page shows an empty state with Previous. */}
      <PaginationControls
        page={page}
        pageSize={pageSize}
        {...(total !== undefined ? { totalItems: total } : { hasMore: rows.length > 0 })}
        pageItemCount={rows.length}
        loading={loading}
        itemLabel={t("jobsItemLabel")}
        onPageChange={setPage}
        onPageSizeChange={(size) => {
          setPage(pageForSizeChange(page, pageSize, size));
          setPageSize(size);
        }}
      />
    </div>
  );
}
