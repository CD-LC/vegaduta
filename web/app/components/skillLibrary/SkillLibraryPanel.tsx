"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Sparkles } from "lucide-react";
import { skillLibraryApi, type LibrarySkill, type SkillStatus } from "../../lib/api/skillLibrary";
import { toast } from "../../lib/toastBus";
import { usePagination } from "../../lib/usePagination";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import EmptyState from "../EmptyState";
import PaginationControls from "../PaginationControls";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import SkillSelfCheckBadge from "./SkillSelfCheckBadge";
import { Button } from "../ui/button";
import { Skeleton } from "../ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "../ui/tabs";
import SkillDetailPanel, { type AgentOption } from "./SkillDetailPanel";
import SkillImportDropzone from "./SkillImportDropzone";

export type LibraryTab = "active" | "pending" | "archived";

const TAB_STATUSES: Record<LibraryTab, SkillStatus[]> = {
  active: ["ACTIVE"],
  pending: ["PENDING"],
  archived: ["ARCHIVED", "REJECTED"],
};

type Buckets = Record<SkillStatus, LibrarySkill[]>;
const EMPTY: Buckets = { ACTIVE: [], PENDING: [], ARCHIVED: [], REJECTED: [] };

/** Active / Pending review / Archived lists, the import dropzone and the open skill. */
export default function SkillLibraryPanel({ agents }: { agents: AgentOption[] }) {
  const t = useTranslations("skillLibrary");
  const [tab, setTab] = useState<LibraryTab>("active");
  const [buckets, setBuckets] = useState<Buckets>(EMPTY);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const statuses: SkillStatus[] = ["ACTIVE", "PENDING", "ARCHIVED", "REJECTED"];
      const pages = await Promise.all(statuses.map((s) => skillLibraryApi.listLibrarySkills(s)));
      const next: Buckets = { ACTIVE: [], PENDING: [], ARCHIVED: [], REJECTED: [] };
      statuses.forEach((s, i) => {
        // Trust the row's own status over the request: a server that ignored the filter must not leak rows across tabs.
        for (const row of pages[i].items) if (row.status === s) next[s].push(row);
      });
      setBuckets(next);
      setFailed(false);
    } catch {
      setFailed(true);
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const rows = useMemo(
    () =>
      TAB_STATUSES[tab]
        .flatMap((s) => buckets[s])
        .sort((a, b) => (b.updatedAt || "").localeCompare(a.updatedAt || "")),
    [buckets, tab]
  );
  const pg = usePagination(rows, 10);
  const pendingCount = buckets.PENDING.length;
  const agentName = useMemo(() => new Map(agents.map((a) => [a.id, a.name])), [agents]);

  const onImport = async (file: File) => {
    setImporting(true);
    try {
      const created = await skillLibraryApi.importLibrarySkill(file);
      toast.success(t("imported"));
      await refresh();
      if (created) {
        setTab(created.status === "PENDING" ? "pending" : "active");
        setOpenId(created.id);
      }
    } catch {
      // apiFetch already raised the error toast
    } finally {
      setImporting(false);
    }
  };

  const emptyFor = (which: LibraryTab) =>
    which === "pending" ? (
      <EmptyState icon={Sparkles} title={t("emptyPendingTitle")} hint={t("emptyPendingHint")} />
    ) : which === "archived" ? (
      <EmptyState title={t("emptyArchivedTitle")} hint={t("emptyArchivedHint")} />
    ) : (
      <EmptyState title={t("emptyActiveTitle")} hint={t("emptyActiveHint")} />
    );

  return (
    <div className="space-y-5">
      <p className="max-w-3xl text-sm text-muted-foreground">{t("intro")}</p>

      {failed && (
        <Alert variant="destructive" data-testid="skill-library-failed">
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span>{t("loadFailed")}</span>
            <Button size="sm" variant="outline" onClick={() => void refresh()}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      <SkillImportDropzone onImport={onImport} busy={importing} />

      <Tabs value={tab} onValueChange={(v) => setTab(v as LibraryTab)}>
        <TabsList aria-label={t("tabsLabel")} className="max-w-md">
          <TabsTrigger value="active">{t("tabActive")}</TabsTrigger>
          <TabsTrigger value="pending">
            {t("tabPending")}
            {pendingCount > 0 && (
              <Badge variant="default" aria-label={t("pendingBadge", { n: pendingCount })} data-testid="pending-badge">
                {pendingCount}
              </Badge>
            )}
          </TabsTrigger>
          <TabsTrigger value="archived">{t("tabArchived")}</TabsTrigger>
        </TabsList>
      </Tabs>

      <div className={cn("grid gap-5", openId && "lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]")}>
        <div role="tabpanel" aria-label={t("tabsLabel")}>
          {!loaded ? (
            <div className="space-y-2" aria-busy="true" aria-label={t("loading")}>
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-16 w-full" />
            </div>
          ) : rows.length === 0 ? (
            emptyFor(tab)
          ) : (
            <>
              <ul className="space-y-2" data-testid="skill-list">
                {pg.pageItems.map((s) => (
                  <li key={s.id}>
                    <button
                      type="button"
                      aria-pressed={openId === s.id}
                      onClick={() => setOpenId(openId === s.id ? null : s.id)}
                      className={cn(
                        "w-full rounded-lg border border-border bg-card p-3 text-left outline-none transition-colors",
                        "hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring",
                        openId === s.id && "border-primary"
                      )}
                    >
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="min-w-0 break-words text-sm font-medium">{s.name}</span>
                        <Badge variant="secondary">{t(`origin_${s.origin}`)}</Badge>
                        <SkillSelfCheckBadge check={s.selfCheck} />
                        {tab === "archived" && <Badge variant="outline">{t(`status_${s.status}`)}</Badge>}
                      </span>
                      {s.description && <span className="mt-1 line-clamp-2 block text-xs text-muted-foreground">{s.description}</span>}
                      <span className="mt-1 block text-xs text-muted-foreground">
                        {t("rowMeta", { version: s.version, uses: s.uses, agents: s.agentIds.length })}
                        {s.agentIds.length > 0 && s.agentIds.length <= 3
                          ? ` - ${s.agentIds.map((a) => agentName.get(a) ?? a).join(", ")}`
                          : ""}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
              <PaginationControls
                page={pg.page}
                totalPages={pg.totalPages}
                totalItems={pg.totalItems}
                pageSize={pg.pageSize}
                onPageChange={pg.setPage}
                onPageSizeChange={pg.setPageSize}
                itemLabel={t("itemLabel")}
              />
            </>
          )}
        </div>
        {openId && (
          <SkillDetailPanel
            key={openId}
            id={openId}
            agents={agents}
            onClose={() => setOpenId(null)}
            onChanged={refresh}
          />
        )}
      </div>
    </div>
  );
}
