"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Pin, PinOff, RefreshCw, Search } from "lucide-react";
import { api } from "../../lib/api";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { EmptyState } from "../ax/page-kit";
import PaginationControls from "../PaginationControls";
import { pageForSizeChange } from "../../lib/usePagination";
import { ListError, ListSkeleton } from "../tenantLists/ListStates";
import { useTranslations } from "../../lib/i18n";
import { formatDuration, formatWhen, isLiveStatus, jobStatusTone } from "../jobs/jobStatus";
import { failureLabelKey, questionFromGoal, statusLabelKey } from "./researchRun";
import { filterHistory, loadPins, savePins, togglePin, type HistoryFilter } from "./researchHistory";
import { nextPollDelay } from "./pollSchedule";
import { RS } from "./researchStrings";

const FILTERS: ReadonlyArray<{ value: HistoryFilter; label: string }> = [
  { value: "all", label: RS.historyFilterAll },
  { value: "running", label: RS.historyFilterRunning },
  { value: "done", label: RS.historyFilterDone },
  { value: "issues", label: RS.historyFilterIssues },
];

/**
 * The signed-in user's own research runs, newest first (GET
 * /api/research/runs). Each user has their own research agent, so this is
 * never anyone else's questions.
 *
 * Refreshes only while a run is in flight and the tab is visible, backing off
 * after failed refreshes; a slow response for an older page or refresh never
 * overwrites a newer one. Search, the status filter and pins work on the runs
 * loaded for the current page (pins are remembered in this browser only).
 */
export default function ResearchHistoryList({ refreshKey = 0 }: { refreshKey?: number }) {
  const t = useTranslations("researchPage");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const offset = (page - 1) * pageSize;
  const [rows, setRows] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<HistoryFilter>("all");
  const [pins, setPins] = useState<string[]>([]);
  const requestSeq = useRef(0);
  const failures = useRef(0);

  useEffect(() => {
    setPins(loadPins());
  }, []);

  const load = useCallback(
    async (opts: { quiet?: boolean } = {}) => {
      const seq = ++requestSeq.current;
      if (!opts.quiet) setLoading(true);
      try {
        const data = await api.listResearchRuns({ limit: pageSize, offset });
        if (seq !== requestSeq.current) return;
        setRows(Array.isArray(data) ? data : []);
        setError(null);
        failures.current = 0;
      } catch (err: any) {
        if (seq !== requestSeq.current) return;
        failures.current += 1;
        setError(err?.message || t("historyLoadFailed"));
      } finally {
        if (seq === requestSeq.current) setLoading(false);
      }
    },
    [offset, pageSize, t]
  );

  useEffect(() => {
    void load();
  }, [load, refreshKey]);

  const hasLive = rows.some((r) => isLiveStatus(r.status));
  useEffect(() => {
    if (!hasLive) return undefined;
    let stop = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const schedule = () => {
      timer = setTimeout(
        () => {
          if (stop) return;
          const done = document.hidden ? Promise.resolve() : load({ quiet: true });
          void done.then(() => {
            if (!stop) schedule();
          });
        },
        nextPollDelay({ failures: failures.current, hidden: document.hidden })
      );
    };
    const onVisible = () => {
      if (document.hidden || stop) return;
      clearTimeout(timer);
      void load({ quiet: true }).then(() => {
        if (!stop) schedule();
      });
    };
    document.addEventListener("visibilitychange", onVisible);
    schedule();
    return () => {
      stop = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [hasLive, load]);

  const pinned = useMemo(() => new Set(pins), [pins]);
  const shown = useMemo(() => filterHistory(rows, { query, filter, pinned }), [rows, query, filter, pinned]);
  const narrowed = query.trim() !== "" || filter !== "all";

  function pin(id: string) {
    const next = togglePin(pins, id);
    setPins(next);
    savePins(next);
  }

  return (
    <section className="space-y-3" data-testid="research-history">
      <div className="flex items-center gap-2">
        <h2 className="text-base font-semibold text-foreground">{t("historyHeading")}</h2>
        <Button size="sm" variant="ghost" className="ms-auto" onClick={() => load()} aria-label={t("historyRefresh")}>
          <RefreshCw
            className={loading ? "size-4 animate-spin motion-reduce:animate-none" : "size-4"}
            aria-hidden="true"
          />
        </Button>
      </div>

      {rows.length > 0 && (
        <div className="space-y-2">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <Input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              aria-label={RS.historySearch}
              placeholder={RS.historySearchPlaceholder}
              className="sm:max-w-xs"
            />
            <div role="group" aria-label={RS.historySearch} className="flex flex-wrap gap-1.5">
              {FILTERS.map((f) => (
                <Button
                  key={f.value}
                  type="button"
                  size="sm"
                  variant={filter === f.value ? "default" : "outline"}
                  aria-pressed={filter === f.value}
                  onClick={() => setFilter(f.value)}
                >
                  {f.label}
                </Button>
              ))}
            </div>
          </div>
          {(narrowed || offset > 0 || rows.length === pageSize) && (
            <p className="text-xs text-muted-foreground">{RS.historyFilterNote}</p>
          )}
        </div>
      )}

      {error && <ListError message={error} onRetry={() => void load()} />}

      {loading && rows.length === 0 && !error && <ListSkeleton rows={5} label={RS.historyLoading} />}

      {rows.length === 0 && !loading && !error ? (
        page > 1 ? (
          <EmptyState icon={Search} title={t("historyPageEmptyTitle")} description={t("historyPageEmptyHint")} />
        ) : (
          <EmptyState icon={Search} title={t("historyEmptyTitle")} description={t("historyEmptyHint")} />
        )
      ) : (
        rows.length > 0 &&
        (shown.length === 0 ? (
          <div className="flex flex-wrap items-center gap-3 py-4 text-sm text-muted-foreground" role="status">
            {RS.historyNoMatch}
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setQuery("");
                setFilter("all");
              }}
            >
              {RS.historyClearFilters}
            </Button>
          </div>
        ) : (
          <Card className="gap-0 overflow-hidden p-0">
            <ul className="divide-y divide-border">
              {shown.map((run) => {
                const tone = jobStatusTone(run.status);
                const statusKey = statusLabelKey(run.status);
                const duration = formatDuration(run.startedAt, run.finishedAt);
                const isPinned = pinned.has(run.id);
                // Second line only while a run is working (its latest progress
                // note) or when it failed (why).
                const detail = isLiveStatus(run.status)
                  ? run.progressNote
                  : run.status === "FAILED"
                    ? t(failureLabelKey(run.errorCode))
                    : null;
                return (
                  <li key={run.id} className="flex items-center hover:bg-muted/40">
                    <Link
                      href={`/research/${run.id}`}
                      className="flex min-w-0 flex-1 flex-col gap-2 px-4 py-3 transition-colors sm:flex-row sm:items-center sm:gap-4"
                    >
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-foreground">
                          {questionFromGoal(run.goalPreview) || t("historyNoQuestion")}
                        </p>
                        {detail && <p className="mt-0.5 truncate text-xs text-muted-foreground">{detail}</p>}
                      </div>
                      <div className="flex shrink-0 items-center gap-3">
                        {duration && <span className="tabular-nums text-xs text-muted-foreground">{duration}</span>}
                        <span className="hidden text-xs text-muted-foreground sm:inline">
                          {formatWhen(run.createdAt)}
                        </span>
                        <Badge variant="outline" className={tone.className}>
                          {statusKey ? t(statusKey) : tone.label}
                        </Badge>
                      </div>
                    </Link>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      className="me-2 shrink-0"
                      aria-pressed={isPinned}
                      aria-label={isPinned ? RS.historyUnpin : RS.historyPin}
                      onClick={() => pin(run.id)}
                    >
                      {isPinned ? (
                        <PinOff className="size-4" aria-hidden="true" />
                      ) : (
                        <Pin className="size-4" aria-hidden="true" />
                      )}
                    </Button>
                  </li>
                );
              })}
            </ul>
          </Card>
        ))
      )}

      <PaginationControls
        page={page}
        pageSize={pageSize}
        hasMore={rows.length >= pageSize}
        pageItemCount={rows.length}
        loading={loading}
        itemLabel={RS.historyItemLabel}
        onPageChange={setPage}
        onPageSizeChange={(size) => {
          setPage(pageForSizeChange(page, pageSize, size));
          setPageSize(size);
        }}
      />
    </section>
  );
}
