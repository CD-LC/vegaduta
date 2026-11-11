"use client";

import { useMemo, useState } from "react";
import { Archive, FolderOpen, Search, Timer } from "lucide-react";
import { Badge } from "../components/ui/badge";
import { Card } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { EmptyState, Section } from "../components/ax/page-kit";
import PaginationControls from "../components/PaginationControls";
import { ListError, ListSkeleton } from "../components/tenantLists/ListStates";
import { usePagination } from "../lib/usePagination";
import { useTranslations } from "../lib/i18n";
import type { AppBuilderProjectSummary } from "./lib";

/**
 * The "App projects" list: every project the app-builder service holds for this tenant, newest first, with a search
 * box (project id, template, status) and the shared pager. The service answers `GET /projects` with the whole list
 * (no limit/offset), so search and paging run in the browser over that one response.
 */
export default function ProjectsList({
  projects,
  loaded,
  failed,
  onRetry,
  onOpen,
}: {
  projects: AppBuilderProjectSummary[];
  loaded: boolean;
  failed: boolean;
  onRetry: () => void;
  onOpen: (projectId: string) => void;
}) {
  const t = useTranslations("buildProjects");
  const tb = useTranslations("appBuilder");
  const [query, setQuery] = useState("");

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    const sorted = [...projects].sort((a, b) => (b.created_at || "").localeCompare(a.created_at || ""));
    if (!q) return sorted;
    return sorted.filter((p) =>
      [p.project_id, p.template_id, p.status].some((v) => (v || "").toLowerCase().includes(q))
    );
  }, [projects, query]);
  const pager = usePagination(rows, 10);

  return (
    <Section title={t("title")} description={t("description")}>
      {failed && <ListError message={t("loadFailed")} onRetry={onRetry} />}
      {!loaded && !failed && <ListSkeleton rows={4} label={t("loading")} />}
      {loaded && !failed && projects.length === 0 && (
        <EmptyState icon={FolderOpen} title={t("emptyTitle")} description={t("emptyBody")} />
      )}
      {loaded && projects.length > 0 && (
        <>
          <div className="relative w-full sm:max-w-xs">
            <Search
              size={14}
              aria-hidden="true"
              className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              type="search"
              className="ps-8"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("searchPlaceholder")}
              aria-label={t("searchLabel")}
            />
          </div>
          {rows.length === 0 ? (
            <EmptyState icon={Search} title={t("noMatchTitle")} description={t("noMatchBody")} />
          ) : (
            <Card className="gap-0 overflow-hidden p-0">
              <ul className="divide-y divide-border">
                {pager.pageItems.map((p) => {
                  const archived = !!p.archived_at;
                  const ephemeral = p.lifecycle === "ephemeral" && !archived;
                  return (
                    <li key={p.project_id}>
                      <button
                        type="button"
                        onClick={() => onOpen(p.project_id)}
                        className="flex w-full flex-col gap-1.5 px-4 py-3 text-start outline-none transition-colors hover:bg-muted/40 focus-visible:bg-muted/40 focus-visible:ring-[3px] focus-visible:ring-inset focus-visible:ring-ring sm:flex-row sm:items-center sm:gap-4"
                      >
                        <span className="min-w-0 flex-1">
                          <span className="block truncate font-mono text-sm font-medium text-foreground">
                            {p.project_id}
                          </span>
                          <span className="block truncate text-xs text-muted-foreground">
                            {t("templateLine", { template: p.template_id })}
                          </span>
                        </span>
                        <span className="flex shrink-0 flex-wrap items-center gap-2">
                          {archived && (
                            <Badge variant="outline">
                              <Archive size={11} aria-hidden="true" /> {tb("lifecycleArchived")}
                            </Badge>
                          )}
                          {ephemeral && (
                            <Badge variant="outline">
                              <Timer size={11} aria-hidden="true" /> {tb("lifecycleEphemeral")}
                            </Badge>
                          )}
                          {p.status && <Badge variant="outline">{p.status}</Badge>}
                          {p.created_at && (
                            <span className="text-xs tabular-nums text-muted-foreground">
                              {new Date(p.created_at).toLocaleDateString()}
                            </span>
                          )}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </Card>
          )}
          <PaginationControls
            page={pager.page}
            totalPages={pager.totalPages}
            totalItems={pager.totalItems}
            pageSize={pager.pageSize}
            onPageChange={pager.setPage}
            onPageSizeChange={pager.setPageSize}
            itemLabel={t("itemLabel")}
          />
        </>
      )}
    </Section>
  );
}
