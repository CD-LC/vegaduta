"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { Bot, Copy, Download, ExternalLink, Loader2, Plus, Search, Sparkles, Trash2 } from "lucide-react";
import { api } from "../../lib/api";
import type { AgentResponse } from "../../lib/api/types";
import { useTranslations } from "../../lib/i18n";
import { triggerBrowserDownload } from "../../lib/api/browserDownload";
import { bundleFileName } from "../../agents/bundles/bundleLogic";
import { usePagination } from "../../lib/usePagination";
import PaginationControls from "../PaginationControls";
import Select from "../Select";
import { EmptyState } from "../ax/page-kit";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { canEditAgent, canViewAgentConfig } from "./agentAccess";
import { Skeleton } from "../ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";

/*
 * The /agents landing: every agent in a searchable, filterable, paged table
 * with Open / Duplicate / Delete. GET /api/agents returns the full tenant
 * list, so paging is client-side (usePagination). Opening an agent goes to
 * the workbench via ?agentId=.
 */
export default function AgentsIndex({
  agents,
  loaded,
  error,
  onRetry,
  onChanged,
  agentHref,
  newHref,
}: {
  agents: AgentResponse[];
  loaded: boolean;
  error?: string | null;
  onRetry: () => void;
  onChanged: () => void;
  agentHref: (agentId: string) => string;
  newHref: string;
}) {
  const t = useTranslations("agentsUi");
  const ta = useTranslations("f2GapsAgents");
  const [query, setQuery] = useState("");
  const [provider, setProvider] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [toDelete, setToDelete] = useState<AgentResponse | null>(null);

  const providerOptions = useMemo(() => {
    const names = Array.from(new Set(agents.map((a) => a.providerName).filter(Boolean) as string[])).sort();
    return [{ value: "", label: t("allProviders") }, ...names.map((n) => ({ value: n, label: n }))];
  }, [agents, t]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return agents
      .filter((a) => (provider ? a.providerName === provider : true))
      .filter((a) => (q ? `${a.name} ${a.model ?? ""} ${a.providerName ?? ""}`.toLowerCase().includes(q) : true))
      .sort((a, b) => (b.updatedAt || "").localeCompare(a.updatedAt || ""));
  }, [agents, query, provider]);
  const pg = usePagination(filtered, 10);

  async function duplicate(a: AgentResponse) {
    setBusyId(a.id);
    setActionError(null);
    try {
      await api.duplicateAgent(a.id);
      onChanged();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : t("duplicateFailed"));
    } finally {
      setBusyId(null);
    }
  }

  // The agent's design as a shareable file (never secrets or knowledge-base ids); see /agents/bundles.
  async function exportFile(a: AgentResponse) {
    setBusyId(a.id);
    setActionError(null);
    try {
      const bundle = await api.exportAgentBundle(a.id);
      triggerBrowserDownload(
        new Blob([JSON.stringify(bundle, null, 2)], { type: "application/json" }),
        bundleFileName(a.name)
      );
    } catch (e) {
      setActionError(e instanceof Error ? e.message : t("exportFailed"));
    } finally {
      setBusyId(null);
    }
  }

  async function remove(a: AgentResponse) {
    setBusyId(a.id);
    setActionError(null);
    try {
      await api.deleteAgent(a.id);
      setToDelete(null);
      onChanged();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : t("deleteFailed"));
    } finally {
      setBusyId(null);
    }
  }

  if (!loaded) {
    return (
      <div className="space-y-2" aria-busy="true" aria-label={t("loadingAgents")}>
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            <span>{error}</span>
            <Button type="button" size="sm" variant="outline" onClick={onRetry}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {actionError && (
        <Alert variant="destructive">
          <AlertDescription>{actionError}</AlertDescription>
        </Alert>
      )}

      {agents.length === 0 && !error ? (
        <EmptyState
          icon={Bot}
          title={t("emptyTitle")}
          description={t("emptyBody")}
          action={
            <div className="flex flex-wrap items-center justify-center gap-2">
              <Button asChild size="sm">
                <Link href="/agents/bundles">
                  <Sparkles size={14} aria-hidden="true" /> {t("startReadyMade")}
                </Link>
              </Button>
              <Button asChild size="sm" variant="outline">
                <Link href={newHref}>
                  <Plus size={14} aria-hidden="true" /> {t("newAgent")}
                </Link>
              </Button>
            </div>
          }
        />
      ) : (
        <>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <div className="relative w-full sm:max-w-xs">
              <Search
                size={14}
                aria-hidden="true"
                className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
              />
              <Input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("searchPlaceholder")}
                aria-label={t("searchLabel")}
                className="pl-9"
              />
            </div>
            <Select
              className="w-full sm:w-56"
              value={provider}
              onChange={(e: any) => setProvider(e.target.value)}
              options={providerOptions}
              aria-label={t("providerFilter")}
            />
            <Button asChild size="sm" className="sm:ml-auto">
              <Link href={newHref}>
                <Plus size={14} aria-hidden="true" /> {t("newAgent")}
              </Link>
            </Button>
          </div>

          {filtered.length === 0 ? (
            <EmptyState icon={Search} title={t("noMatchTitle")} description={t("noMatchBody")} />
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("colName")}</TableHead>
                    <TableHead>{t("colModel")}</TableHead>
                    <TableHead className="hidden md:table-cell">{t("colUpdated")}</TableHead>
                    <TableHead className="text-right">{t("colActions")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pg.pageItems.map((a) => (
                    <TableRow key={a.id}>
                      <TableCell className="max-w-[14rem] truncate font-medium text-foreground">
                        <Link href={agentHref(a.id)} className="hover:underline">
                          {a.name}
                        </Link>
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {a.providerName || "—"}
                        {a.model ? ` / ${a.model}` : ""}
                      </TableCell>
                      <TableCell className="hidden text-muted-foreground md:table-cell">
                        {a.updatedAt ? new Date(a.updatedAt).toLocaleDateString() : "—"}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap justify-end gap-1">
                          <Button asChild size="sm" variant="outline">
                            <Link href={agentHref(a.id)} aria-label={t("openAgent", { name: a.name })}>
                              <ExternalLink size={13} aria-hidden="true" /> {t("open")}
                            </Link>
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="ghost"
                            disabled={busyId !== null || !canViewAgentConfig(a)}
                            title={canViewAgentConfig(a) ? undefined : ta("accessNoDuplicate")}
                            aria-label={t("duplicateAgent", { name: a.name })}
                            onClick={() => void duplicate(a)}
                          >
                            {busyId === a.id ? (
                              <Loader2 size={13} className="animate-spin" aria-hidden="true" />
                            ) : (
                              <Copy size={13} aria-hidden="true" />
                            )}
                            <span className="hidden sm:inline">{t("duplicate")}</span>
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="ghost"
                            disabled={busyId !== null || !canViewAgentConfig(a)}
                            aria-label={t("exportAgent", { name: a.name })}
                            onClick={() => void exportFile(a)}
                          >
                            <Download size={13} aria-hidden="true" />
                            <span className="hidden sm:inline">{t("exportFile")}</span>
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="ghost"
                            disabled={busyId !== null || !canEditAgent(a)}
                            title={canEditAgent(a) ? undefined : ta("accessNoDelete")}
                            aria-label={t("deleteAgent", { name: a.name })}
                            onClick={() => setToDelete(a)}
                          >
                            <Trash2 size={13} aria-hidden="true" />
                            <span className="hidden sm:inline">{t("delete")}</span>
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
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
        </>
      )}

      <AlertDialog open={!!toDelete} onOpenChange={(o) => !o && setToDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteTitle", { name: toDelete?.name ?? "" })}</AlertDialogTitle>
            <AlertDialogDescription>{t("deleteBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={() => toDelete && void remove(toDelete)}>{t("delete")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
