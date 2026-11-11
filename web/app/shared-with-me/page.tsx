"use client";

import { formatDateTime } from "../lib/format";
import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Bot, Workflow, ArrowRight, Users, Search } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import { useTranslations } from "../lib/i18n";
import { Card, CardContent } from "../components/ui/card";
import { Skeleton } from "../components/ui/skeleton";
import { Badge } from "../components/ui/badge";
import { Input } from "../components/ui/input";
import { ListError } from "../components/tenantLists/ListStates";
import PaginationControls from "../components/PaginationControls";
import LoadMoreBar from "../components/LoadMoreBar";
import { usePagination } from "../lib/usePagination";

/**
 * "Shared with me" - core/sharing's GET /api/shared-with-me, the receiving
 * side of cross-user Agent/Workflow sharing (the granting side is
 * TeammateAccessPanel, mounted on each resource's own detail view). This is
 * the ONLY discovery surface for what's been shared with you - named-invite
 * only, no directory/browse of other people's private resources.
 *
 * SharedResourceResponse carries only `resourceId`, not a resource name (see
 * core/sharing/SharedWithMeController.java) - this page enriches best-effort
 * by cross-referencing GET /api/agents and GET /api/workflows (both already
 * full tenant-wide lists today, no extra permission surface exposed by
 * reading them), falling back to a truncated id if a match isn't found
 * (e.g. the resource was deleted after the grant was made).
 */
function ResourceRow({
  item,
  name,
  href,
  t,
}: {
  item: any;
  name: string | null;
  href: string;
  t: (key: string, values?: any) => string;
}) {
  return (
    <Link
      href={href}
      className="flex items-center justify-between gap-3 rounded-lg border border-border bg-card px-4 py-3 transition-colors hover:bg-accent"
    >
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-foreground">{name || `#${item.resourceId.slice(0, 8)}`}</div>
        <div className="text-xs text-muted-foreground">
          {t("grantedAtLabel", { date: formatDateTime(item.grantedAt) })}
        </div>
      </div>
      <Badge variant="outline" className="shrink-0">
        {t(`permission${item.permissionLevel.charAt(0)}${item.permissionLevel.slice(1).toLowerCase()}Label`)}
      </Badge>
      <ArrowRight size={14} className="shrink-0 text-muted-foreground" aria-hidden="true" />
    </Link>
  );
}

/** Grants fetched per server request when the core pages this list. */
const SERVER_PAGE = 200;

function RowSkeleton() {
  return (
    <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-card px-4 py-3">
      <div className="min-w-0 flex-1 space-y-1.5">
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="h-3 w-1/4" />
      </div>
      <Skeleton className="h-5 w-16 rounded-full" />
    </div>
  );
}

export default function SharedWithMePage() {
  const t = useTranslations("sharing");
  const [items, setItems] = useState<any[]>([]);
  const [agentsById, setAgentsById] = useState<Record<string, string>>({});
  const [workflowsById, setWorkflowsById] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  // Server paging (when the core provides X-Total-Count): how many grants exist in all, and
  // whether more can be fetched. Null total = an older core that answered everything at once.
  const [serverTotal, setServerTotal] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const page = await api.getSharedWithMePage(0, SERVER_PAGE);
      setItems(page.rows);
      setServerTotal(page.total);
      // Only trust "more" when the server said it counts; without X-Total-Count the whole list arrived.
      setHasMore(page.total != null && page.hasMore && page.rows.length < page.total);
      setError(null);
    } catch (err: any) {
      setItems([]);
      setServerTotal(null);
      setHasMore(false);
      setError(err?.message || t("pageLoadError"));
    }
    // Best-effort name enrichment - a failure here just means names fall
    // back to a truncated id, not a page-level error (see header comment).
    try {
      const agents = await api.listAgents();
      setAgentsById(Object.fromEntries((agents || []).map((a: any) => [a.id, a.name])));
    } catch {
      setAgentsById({});
    }
    try {
      const workflows = await api.listWorkflows();
      setWorkflowsById(Object.fromEntries((workflows || []).map((w: any) => [w.id, w.name])));
    } catch {
      setWorkflowsById({});
    } finally {
      setLoaded(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadMore = useCallback(async () => {
    setLoadingMore(true);
    try {
      const page = await api.getSharedWithMePage(items.length, SERVER_PAGE);
      setItems((cur) => [...cur, ...page.rows]);
      setServerTotal(page.total);
      setHasMore(page.rows.length > 0 && page.hasMore);
    } catch (err: any) {
      setError(err?.message || t("pageLoadError"));
    } finally {
      setLoadingMore(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.length]);

  useEffect(() => {
    void refresh();
  }, [refresh]);
  usePageReady(loaded);

  const tl = useTranslations("tenantLists");
  const tp = useTranslations("consolePolish");
  const [query, setQuery] = useState("");
  const matches = useCallback(
    (i: any, names: Record<string, string>) => {
      const q = query.trim().toLowerCase();
      return (
        !q ||
        String(names[i.resourceId] ?? i.resourceId)
          .toLowerCase()
          .includes(q)
      );
    },
    [query]
  );
  const agentItems = useMemo(
    () => items.filter((i) => i.resourceType === "AGENT" && matches(i, agentsById)),
    [items, agentsById, matches]
  );
  const workflowItems = useMemo(
    () => items.filter((i) => i.resourceType === "WORKFLOW" && matches(i, workflowsById)),
    [items, workflowsById, matches]
  );
  // Grants of any other resource type have no screen here yet; say so rather than hiding them silently.
  const otherCount = items.filter((i) => i.resourceType !== "AGENT" && i.resourceType !== "WORKFLOW").length;
  const agentsPagination = usePagination(agentItems);
  const workflowsPagination = usePagination(workflowItems);

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader title={t("pageTitle")} subtitle={t("pageSubtitle")} />

      {error && (
        <div className="mb-5">
          <ListError message={error} onRetry={() => void refresh()} />
        </div>
      )}

      {loaded && items.length > 5 && (
        <div className="relative mb-4 sm:max-w-sm">
          <Search
            size={14}
            aria-hidden="true"
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={tl("searchShared")}
            aria-label={tl("searchShared")}
            className="ps-8"
          />
        </div>
      )}

      {loaded && otherCount > 0 && (
        <p role="note" className="mb-4 text-xs text-muted-foreground">
          {tl("sharedOther", { count: otherCount })}
        </p>
      )}

      {loaded && query.trim() !== "" && agentItems.length === 0 && workflowItems.length === 0 && items.length > 0 && (
        <p role="status" className="mb-4 text-sm text-muted-foreground">
          {tl("noMatch")}
        </p>
      )}

      {!loaded && (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <RowSkeleton key={i} />
          ))}
        </div>
      )}

      {loaded && !error && items.length === 0 && (
        <Card>
          <CardContent className="flex flex-col items-center gap-2 py-10 text-center">
            <Users size={28} className="text-muted-foreground" aria-hidden="true" />
            <p className="text-sm font-medium text-foreground">{t("pageEmptyTitle")}</p>
            <p className="max-w-sm text-xs text-muted-foreground">{t("pageEmptyHint")}</p>
          </CardContent>
        </Card>
      )}

      {loaded && agentItems.length > 0 && (
        <div className="mb-6">
          <div className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            <Bot size={13} aria-hidden="true" /> {t("groupAgents")}
          </div>
          <div className="flex flex-col gap-2">
            {agentsPagination.pageItems.map((item) => (
              <ResourceRow
                key={item.grantId}
                item={item}
                name={agentsById[item.resourceId] || null}
                href={`/agents?agentId=${item.resourceId}`}
                t={t}
              />
            ))}
          </div>
          <PaginationControls
            page={agentsPagination.page}
            totalPages={agentsPagination.totalPages}
            totalItems={agentsPagination.totalItems}
            pageSize={agentsPagination.pageSize}
            onPageChange={agentsPagination.setPage}
            onPageSizeChange={agentsPagination.setPageSize}
            itemLabel={t("itemLabelAgents")}
          />
        </div>
      )}

      {loaded && workflowItems.length > 0 && (
        <div>
          <div className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            <Workflow size={13} aria-hidden="true" /> {t("groupWorkflows")}
          </div>
          <div className="flex flex-col gap-2">
            {workflowsPagination.pageItems.map((item) => (
              <ResourceRow
                key={item.grantId}
                item={item}
                name={workflowsById[item.resourceId] || null}
                href={`/workflows/${item.resourceId}`}
                t={t}
              />
            ))}
          </div>
          <PaginationControls
            page={workflowsPagination.page}
            totalPages={workflowsPagination.totalPages}
            totalItems={workflowsPagination.totalItems}
            pageSize={workflowsPagination.pageSize}
            onPageChange={workflowsPagination.setPage}
            onPageSizeChange={workflowsPagination.setPageSize}
            itemLabel={t("itemLabelWorkflows")}
          />
        </div>
      )}

      {loaded && (
        <LoadMoreBar
          loaded={items.length}
          total={serverTotal}
          hasMore={hasMore}
          loading={loadingMore}
          onLoadMore={() => void loadMore()}
          itemLabel={tp("sharedGrantsLabel")}
        />
      )}
    </AppShell>
  );
}
