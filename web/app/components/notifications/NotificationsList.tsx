"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Bell, CheckCheck, RefreshCw } from "lucide-react";
import { api, type NotificationResponse, type NotificationSeverity } from "../../lib/api";
import { listNotificationsPage } from "../../lib/api/notificationsPaged";
import { timeAgo } from "../../lib/format";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { pageForSizeChange } from "../../lib/usePagination";
import { cn } from "../../lib/utils";
import PaginationControls from "../PaginationControls";
import { EmptyState } from "../ax/page-kit";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Skeleton } from "../ui/skeleton";

const SEVERITY_STATE_VAR: Record<NotificationSeverity, string> = {
  ERROR: "var(--state-error)",
  WARNING: "var(--state-warning)",
  INFO: "var(--accent-500)",
};

const SEVERITY_KEY: Record<NotificationSeverity, string> = {
  ERROR: "severityError",
  WARNING: "severityWarning",
  INFO: "severityInfo",
};

/** Full notifications list: unread filter, server paging (limit/offset), mark read. */
export default function NotificationsList() {
  const t = useTranslations("auditNotifications");
  const router = useRouter();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [page, setPageNo] = useState(1);
  const [pageSize, setPageSizeState] = useState(25);
  const [items, setItems] = useState<NotificationResponse[]>([]);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await listNotificationsPage({ unreadOnly, limit: pageSize, offset: (page - 1) * pageSize });
      setItems(res.rows);
      setTotal(res.total);
      setHasMore(res.hasMore);
      setError(null);
      // Walked past the end (e.g. after marking rows read in the unread view): step back.
      if (res.rows.length === 0 && page > 1) setPageNo(page - 1);
    } catch (err) {
      setError(errorMessage(err, t("notifLoadFailed")));
    } finally {
      setLoading(false);
    }
  }, [unreadOnly, page, pageSize, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const markRead = async (n: NotificationResponse) => {
    if (n.read) return;
    setItems((prev) => prev.map((row) => (row.id === n.id ? { ...row, read: true } : row)));
    try {
      await api.markNotificationRead(n.id);
    } catch {
      /* reconciled by the reload below */
    }
    void load();
  };

  const openRow = (n: NotificationResponse) => {
    void markRead(n);
    if (n.link) router.push(n.link);
  };

  const markAll = async () => {
    try {
      await api.markAllNotificationsRead();
    } catch {
      /* reconciled by the reload below */
    }
    void load();
  };

  const hasUnread = items.some((n) => !n.read);

  return (
    <div className="space-y-4" data-testid="notifications-list">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="sm"
          variant={!unreadOnly ? "default" : "outline"}
          aria-pressed={!unreadOnly}
          className="rounded-full"
          onClick={() => {
            setUnreadOnly(false);
            setPageNo(1);
          }}
        >
          {t("filterAll")}
        </Button>
        <Button
          type="button"
          size="sm"
          variant={unreadOnly ? "default" : "outline"}
          aria-pressed={unreadOnly}
          className="rounded-full"
          onClick={() => {
            setUnreadOnly(true);
            setPageNo(1);
          }}
        >
          {t("filterUnread")}
        </Button>
        <div className="ms-auto flex flex-wrap items-center gap-2">
          <Button type="button" size="sm" variant="outline" onClick={() => void load()}>
            <RefreshCw size={14} aria-hidden="true" /> {t("refresh")}
          </Button>
          <Button type="button" size="sm" variant="outline" onClick={() => void markAll()} disabled={!hasUnread}>
            <CheckCheck size={14} aria-hidden="true" /> {t("markAllRead")}
          </Button>
        </div>
      </div>

      {loading ? (
        <div className="space-y-2" aria-busy="true">
          {[0, 1, 2, 3, 4].map((n) => (
            <Skeleton key={n} className="h-16 w-full" />
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
      ) : items.length === 0 ? (
        <EmptyState
          icon={Bell}
          title={unreadOnly ? t("emptyUnread") : t("emptyAll")}
          description={unreadOnly ? t("emptyUnreadHint") : undefined}
        />
      ) : (
        <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
          {items.map((n) => (
            <li key={n.id}>
              <button
                type="button"
                onClick={() => openRow(n)}
                className={cn(
                  "flex w-full flex-col items-stretch gap-1 border-0 bg-transparent px-4 py-3 text-start shadow-none transition-colors hover:bg-secondary",
                  !n.read && "bg-secondary/40"
                )}
              >
                <span className="flex min-w-0 flex-wrap items-center gap-2">
                  <span
                    className="state-dot shrink-0"
                    style={{ ["--state" as string]: SEVERITY_STATE_VAR[n.severity] ?? SEVERITY_STATE_VAR.INFO }}
                    aria-hidden="true"
                  />
                  <span className="sr-only">{t(SEVERITY_KEY[n.severity] ?? SEVERITY_KEY.INFO) + ": "}</span>
                  <span className={cn("min-w-0 truncate text-sm", n.read ? "text-foreground" : "font-semibold")}>
                    {n.title}
                  </span>
                  {n.category === "AGENT" && <Badge variant="secondary">{t("agentChip")}</Badge>}
                  {!n.read && <Badge variant="outline">{t("unreadBadge")}</Badge>}
                  <span className="ms-auto shrink-0 text-xs text-muted-foreground">{timeAgo(n.createdAt)}</span>
                </span>
                {n.body && <span className="line-clamp-2 ps-4 text-xs text-muted-foreground">{n.body}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      <PaginationControls
        page={page}
        totalItems={total}
        hasMore={hasMore}
        pageItemCount={items.length}
        pageSize={pageSize}
        loading={loading}
        onPageChange={(p) => setPageNo(Math.max(1, p))}
        onPageSizeChange={(size) => {
          setPageNo(pageForSizeChange(page, pageSize, size));
          setPageSizeState(size);
        }}
        itemLabel={t("notifItemLabel")}
      />
    </div>
  );
}
