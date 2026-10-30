"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Eye, History, RefreshCw, StopCircle } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard } from "../components/sections/common";
import { ToneChip } from "../components/ops/OpsWidgets";
import { Button } from "../components/ui/button";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import { pageForSizeChange } from "../lib/usePagination";
import { toast } from "../lib/toastBus";
import { governanceApi, type ImpersonationPage, type ImpersonationSession } from "../lib/adminApi/governance";
import { formatTimeLeft, impersonationState } from "../lib/governanceFormat";
import { UNAVAILABLE, formatCountOrUnavailable, formatExact, formatRelative } from "../lib/platformFormat";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { AdminErrorState, AdminEmptyState } from "../components/admin/AdminKit";
import { useTranslations } from "../../lib/i18n";

// Read-only "view as" sessions (GET/DELETE /api/platform/impersonation).
// Sessions are started from Platform Users or Tenant Detail ("View as
// (read-only)"), last 30 minutes, refuse every write, and are recorded in the
// tenant's own audit log. "End now" revokes the grant server-side; the viewing
// tab notices on its next check (at most a minute) and leaves view-as.

const STATE_META = {
  active: { label: "Active", tone: "warn" as const },
  ended: { label: "Ended", tone: "muted" as const },
  expired: { label: "Expired", tone: "muted" as const },
};

function normalizePage(res: any): ImpersonationPage {
  return { items: Array.isArray(res?.items) ? res.items : [], total: typeof res?.total === "number" ? res.total : 0 };
}

function SessionsTable({
  items,
  now,
  showEnd,
  ending,
  onEnd,
}: {
  items: ImpersonationSession[];
  now: number;
  showEnd: boolean;
  ending: string | null;
  onEnd: (s: ImpersonationSession) => void;
}) {
  const tHC = useTranslations("hcPlatformadminImpersonationPage");
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[820px] text-start text-sm">
        <thead className="text-xs text-muted-foreground">
          <tr className="border-b border-border">
            <th className="py-2 pe-3 font-medium">{tHC("s7")}</th>
            <th className="py-2 pe-3 font-medium">Tenant</th>
            <th className="py-2 pe-3 font-medium">Admin</th>
            <th className="py-2 pe-3 font-medium">Reason</th>
            <th className="py-2 pe-3 font-medium">Started</th>
            <th className="py-2 pe-3 font-medium">{showEnd ? "Expires" : "Ended"}</th>
            <th className="py-2 pe-3 font-medium">Requests</th>
            <th className="py-2 font-medium">{showEnd ? <span className="sr-only">Actions</span> : "State"}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((s) => {
            const state = impersonationState(s, now);
            const meta = STATE_META[state];
            return (
              <tr key={s.id} className="border-b border-border/60 align-top">
                <td className="py-2 pe-3 font-mono text-xs">{s.userEmail || s.userId}</td>
                <td className="py-2 pe-3 text-xs">
                  <Link
                    href={`/platform-admin/tenant-detail?id=${encodeURIComponent(s.tenantId)}`}
                    className="underline-offset-2 hover:text-primary hover:underline"
                    title={s.tenantId}
                  >
                    {s.tenantName || s.tenantId.slice(0, 8)}
                  </Link>
                </td>
                <td className="py-2 pe-3 text-xs">{s.adminEmail || UNAVAILABLE}</td>
                <td className="max-w-[280px] py-2 pe-3 text-xs">
                  <span className="line-clamp-2" title={s.reason}>
                    {s.reason}
                  </span>
                </td>
                <td className="py-2 pe-3 text-xs" title={formatExact(s.startedAt)}>
                  {formatRelative(s.startedAt, now)}
                </td>
                <td className="py-2 pe-3 text-xs" title={formatExact(showEnd ? s.expiresAt : s.endedAt || s.expiresAt)}>
                  {showEnd
                    ? formatTimeLeft(s.expiresAt, now)
                    : s.endedAt
                      ? formatRelative(s.endedAt, now)
                      : formatRelative(s.expiresAt, now)}
                </td>
                <td className="py-2 pe-3 font-mono text-xs">{formatCountOrUnavailable(s.requestCount)}</td>
                <td className="py-2 text-end">
                  {showEnd && state === "active" ? (
                    <Button size="sm" variant="outline" disabled={ending != null} onClick={() => onEnd(s)}>
                      <StopCircle /> {ending === s.id ? "Ending..." : "End now"}
                    </Button>
                  ) : (
                    <ToneChip tone={meta.tone} label={meta.label} />
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function ImpersonationPage() {
  const tHC = useTranslations("hcPlatformadminImpersonationPage");
  const askConfirm = useConfirm();
  const [active, setActive] = useState<ImpersonationPage | null>(null);
  const [activeError, setActiveError] = useState<string | null>(null);
  const [history, setHistory] = useState<ImpersonationPage | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [ending, setEnding] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [pageSize, setPageSize] = useState(25);

  const loadActive = useCallback(async () => {
    try {
      setActive(normalizePage(await governanceApi.listImpersonation({ active: true })));
      setActiveError(null);
    } catch (e: any) {
      setActiveError(e?.message || "Could not load active sessions.");
    }
  }, []);

  const loadHistory = useCallback(async () => {
    try {
      // `page` is 1-based, the same convention as the other Operations lists.
      const next = normalizePage(await governanceApi.listImpersonation({ active: false, page, limit: pageSize }));
      setHistory(next);
      setHistoryError(null);
    } catch (e: any) {
      setHistoryError(e?.message || "Could not load session history.");
    }
  }, [page, pageSize]);

  useEffect(() => {
    void loadActive();
  }, [loadActive]);
  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);
  // Countdowns tick; the list itself refreshes every 30s so a session that
  // expires or is ended elsewhere moves to history.
  useEffect(() => {
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    const poll = window.setInterval(() => {
      void loadActive();
    }, 30_000);
    return () => {
      window.clearInterval(tick);
      window.clearInterval(poll);
    };
  }, [loadActive]);

  async function end(s: ImpersonationSession) {
    if (
      !(await askConfirm(
        `End the view-as session for ${s.userEmail || s.userId} now? The viewing tab leaves view-as within a minute.`
      ))
    )
      return;
    setEnding(s.id);
    try {
      await governanceApi.endImpersonation(s.id);
      toast.success("View-as session ended.");
      await Promise.all([loadActive(), loadHistory()]);
    } catch {
      // toJson already toasted the reason.
    } finally {
      setEnding(null);
    }
  }

  const activeItems = (active?.items || []).filter((s) => impersonationState(s, now) === "active");
  const historyItems = history?.items || [];

  return (
    <AdminShell title="Impersonation">
      <PageHint>
        {tHC("s6")}{" "}
        <Link href="/platform-admin/users" className="underline underline-offset-2">
          {tHC("s5")}
        </Link>{" "}
        {tHC("s4")}
      </PageHint>

      <SectionCard
        title={tHC("s3")}
        icon={Eye}
        subtitle={active ? `${activeItems.length} active` : undefined}
        actions={
          <Button
            type="button"
            size="icon"
            variant="outline"
            aria-label="Refresh"
            onClick={() => {
              void loadActive();
              void loadHistory();
            }}
          >
            <RefreshCw />
          </Button>
        }
      >
        {activeError && <AdminErrorState className="mb-4" message={activeError} onRetry={() => void loadActive()} />}
        {!active && !activeError && <Skeleton className="h-20 w-full" />}
        {active && activeItems.length === 0 && (
          <AdminEmptyState icon={Eye} title={tHC("s2")} description="No one is viewing as a user right now." />
        )}
        {activeItems.length > 0 && (
          <SessionsTable items={activeItems} now={now} showEnd ending={ending} onEnd={(s) => void end(s)} />
        )}
      </SectionCard>

      <SectionCard
        title="History"
        icon={History}
        subtitle={
          history ? `${history.total.toLocaleString("en-US")} past session${history.total === 1 ? "" : "s"}` : undefined
        }
      >
        {historyError && <AdminErrorState className="mb-4" message={historyError} onRetry={() => void loadHistory()} />}
        {!history && !historyError && <Skeleton className="h-32 w-full" />}
        {history && historyItems.length === 0 && <AdminEmptyState icon={History} title={tHC("s1")} />}
        {historyItems.length > 0 && (
          <SessionsTable items={historyItems} now={now} showEnd={false} ending={null} onEnd={() => {}} />
        )}
        {history && (
          <PaginationControls
            page={page}
            pageSize={pageSize}
            totalItems={history.total}
            itemLabel="sessions"
            onPageChange={setPage}
            onPageSizeChange={(n) => {
              setPage(pageForSizeChange(page, pageSize, n));
              setPageSize(n);
            }}
          />
        )}
      </SectionCard>
    </AdminShell>
  );
}
