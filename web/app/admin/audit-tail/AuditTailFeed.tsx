"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Pause, Play, RefreshCw, ScrollText } from "lucide-react";
import { api, type AuditLogEntry } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { pageForSizeChange } from "../../lib/usePagination";
import PaginationControls from "../../components/PaginationControls";
import { EmptyState } from "../../components/ax/page-kit";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Skeleton } from "../../components/ui/skeleton";
import { StatusDot } from "../../components/ui/status-dot";

const POLL_MS = 6000;

function timeOf(iso: string) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "--:--:--" : d.toTimeString().slice(0, 8);
}

function targetOf(e: AuditLogEntry) {
  const target = (e.targetType || "") + (e.targetId ? "/" + e.targetId : "");
  return [target, e.detail].filter(Boolean).join(" - ");
}

/**
 * Full-page live audit tail, newest first. Server-paged (limit/offset +
 * X-Total-Count); page 1 re-polls every few seconds unless paused, older pages
 * are static so a row never moves while it is being read. The text filter
 * narrows the loaded page only.
 */
export default function AuditTailFeed({ refreshKey = 0 }: { refreshKey?: number }) {
  const t = useTranslations("auditNotifications");
  const [rows, setRows] = useState<AuditLogEntry[]>([]);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [page, setPageNo] = useState(1);
  const [pageSize, setPageSizeState] = useState(50);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [paused, setPaused] = useState(false);
  const [filter, setFilter] = useState("");

  const load = useCallback(async () => {
    try {
      const res = await api.listAuditLogPage({ limit: pageSize, offset: (page - 1) * pageSize });
      setRows(res.rows);
      setTotal(res.total);
      setHasMore(res.hasMore);
      setError(null);
    } catch (err) {
      setError(errorMessage(err, t("tailLoadFailed")));
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, t]);

  useEffect(() => {
    void load();
  }, [load, refreshKey]);

  const live = page === 1 && !paused;
  useEffect(() => {
    if (!live) return undefined;
    const id = setInterval(() => {
      if (!document.hidden) void load();
    }, POLL_MS);
    return () => clearInterval(id);
  }, [live, load]);

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((e) =>
      [e.actorId, e.actionType, e.targetType, e.targetId, e.detail, e.outcome].some((f) =>
        (f || "").toLowerCase().includes(q)
      )
    );
  }, [rows, filter]);

  return (
    <div className="flex min-h-[360px] flex-col gap-3" data-testid="audit-tail-feed">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder={t("tailFilterPlaceholder")}
          aria-label={t("tailFilterPlaceholder")}
          className="w-full sm:w-72"
        />
        <span className="text-xs text-muted-foreground">{t("tailFilterScope")}</span>
        <div className="ms-auto flex items-center gap-2">
          <span className="inline-flex items-center gap-1.5 font-mono text-xs text-muted-foreground">
            <StatusDot status={live ? "up" : "unknown"} size={6} glow={false} pulse={live} />
            {live ? t("tailLive") : page > 1 ? t("tailHistorical") : t("tailPaused")}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setPaused((p) => !p)}
            disabled={page > 1}
            aria-pressed={paused}
          >
            {paused ? <Play size={14} aria-hidden="true" /> : <Pause size={14} aria-hidden="true" />}
            {paused ? t("tailResume") : t("tailPause")}
          </Button>
          {page > 1 && (
            <Button type="button" variant="outline" size="sm" onClick={() => setPageNo(1)}>
              <RefreshCw size={14} aria-hidden="true" /> {t("tailBackToLatest")}
            </Button>
          )}
        </div>
      </div>

      {loading ? (
        <div className="space-y-2" aria-busy="true">
          {[0, 1, 2, 3, 4, 5].map((n) => (
            <Skeleton key={n} className="h-8 w-full" />
          ))}
        </div>
      ) : error ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-lg border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          <span>{error}</span>
          <Button type="button" variant="outline" size="sm" onClick={() => void load()}>
            {t("retry")}
          </Button>
        </div>
      ) : shown.length === 0 ? (
        <EmptyState
          icon={ScrollText}
          title={filter.trim() ? t("tailNoMatch") : t("tailEmpty")}
          description={filter.trim() ? t("tailNoMatchHint") : undefined}
        />
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border bg-card">
          {shown.map((e) => (
            <li
              key={e.id}
              className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 px-3 py-2 font-mono text-xs sm:grid-cols-[5rem_4rem_14rem_1fr]"
            >
              <span className="text-muted-foreground">{timeOf(e.createdAt)}</span>
              <span className={e.outcome === "SUCCESS" ? "text-[var(--state-success)]" : "text-[var(--state-error)]"}>
                {e.outcome === "SUCCESS" ? "OK" : "ERR"}
              </span>
              <span className="col-span-2 truncate font-semibold sm:col-span-1">{e.actionType}</span>
              <span className="col-span-2 truncate text-muted-foreground sm:col-span-1" title={targetOf(e)}>
                {targetOf(e)}
              </span>
            </li>
          ))}
        </ul>
      )}

      <PaginationControls
        page={page}
        totalItems={total}
        hasMore={hasMore}
        pageItemCount={rows.length}
        pageSize={pageSize}
        loading={loading}
        onPageChange={(p) => setPageNo(Math.max(1, p))}
        onPageSizeChange={(size) => {
          setPageNo(pageForSizeChange(page, pageSize, size));
          setPageSizeState(size);
        }}
        itemLabel={t("tailItemLabel")}
      />
    </div>
  );
}
