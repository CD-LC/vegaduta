"use client";

/**
 * Version history drawer for the workflow designer. Lists every saved
 * version (GET /versions, paged client-side), saves a new one, compares two
 * (the existing VersionDiffPanel), restores one to the draft, promotes one to
 * STAGING/PRODUCTION and rolls PRODUCTION back.
 *
 * Props: workflowId; canvasDirty (unsaved canvas edits, warned about);
 * canWrite (editor-level actions; the server still enforces EDITOR); accessLevel (the workflow's
 * accessLevel; anything but EDITOR hides save/restore/promote/rollback);
 * onFocusNode (diff row -> canvas); onClose.
 * Restore reloads the page so the canvas re-reads the restored graph.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { History, RotateCcw, Rocket, GitCompare, Undo2, X } from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Skeleton } from "../ui/skeleton";
import { Select } from "../ui/select";
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
import { EmptyState } from "../ax/page-kit";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { useTranslations } from "../../lib/i18n";
import { toast } from "../../lib/toastBus";
import {
  promoteWorkflowVersion,
  restoreWorkflowVersion,
  rollbackWorkflowVersion,
  saveWorkflowVersion,
  type VersionLabel,
} from "../../lib/api/workflowVersions";
import VersionDiffPanel from "./VersionDiffPanel";
import { canEditWorkflow } from "./workflowAccess";
import { fetchWorkflowVersions, formatWhen, prettyStatus, type WorkflowVersionRow } from "./versionDiff";

type Load =
  | { kind: "loading" }
  | { kind: "unavailable" }
  | { kind: "error"; message: string }
  | { kind: "ready"; versions: WorkflowVersionRow[] };

type Confirm = { kind: "restore"; n: number } | { kind: "promote"; n: number } | { kind: "rollback" } | null;

export type WorkflowVersionsDrawerProps = {
  workflowId: string;
  canvasDirty?: boolean;
  canWrite?: boolean;
  accessLevel?: string | null;
  onFocusNode: (nodeId: string) => void;
  onClose: () => void;
};

export default function WorkflowVersionsDrawer({
  workflowId,
  canvasDirty = false,
  canWrite: canWriteProp = true,
  accessLevel,
  onFocusNode,
  onClose,
}: WorkflowVersionsDrawerProps) {
  const canWrite = canWriteProp && canEditWorkflow({ accessLevel });
  const t = useTranslations("workflowStudioUi");
  const tRef = useRef(t);
  tRef.current = t;
  const [load, setLoad] = useState<Load>({ kind: "loading" });
  const [reloadKey, setReloadKey] = useState(0);
  const [note, setNote] = useState("");
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [compareFor, setCompareFor] = useState<number | null>(null);
  const [confirm, setConfirm] = useState<Confirm>(null);
  const [label, setLabel] = useState<VersionLabel>("PRODUCTION");

  useEffect(() => {
    let cancelled = false;
    setLoad({ kind: "loading" });
    void fetchWorkflowVersions(workflowId).then((r) => {
      if (cancelled) return;
      if (r.ok) setLoad({ kind: "ready", versions: r.data });
      else if (r.unsupported) setLoad({ kind: "unavailable" });
      else setLoad({ kind: "error", message: r.message ?? tRef.current("versionsError") });
    });
    return () => {
      cancelled = true;
    };
  }, [workflowId, reloadKey]);

  // The diff panel and the confirm dialog handle Escape themselves; close the drawer only when neither is open.
  useEffect(() => {
    if (compareFor != null || confirm) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !e.defaultPrevented) onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [compareFor, confirm, onClose]);

  const versions = useMemo(() => (load.kind === "ready" ? load.versions : []), [load]);
  const latest = versions.length ? Math.max(...versions.map((v) => v.versionNumber)) : null;
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase().replace(/^v/, "");
    if (!q) return versions;
    return versions.filter(
      (v) => String(v.versionNumber).includes(q) || (v.changeSummary ?? "").toLowerCase().includes(q)
    );
  }, [versions, query]);
  const pg = usePagination(filtered);

  const run = useCallback(
    async (fn: () => Promise<void>) => {
      setBusy(true);
      try {
        await fn();
      } catch (err) {
        toast.error(err instanceof Error && err.message ? err.message : t("actionFailed"));
      } finally {
        setBusy(false);
      }
    },
    [t]
  );

  const onSave = () =>
    run(async () => {
      const r = await saveWorkflowVersion(workflowId, note);
      if (r.createdNew) toast.success(t("versionSaved", { n: r.version?.versionNumber ?? "" }));
      else toast.info(t("versionUnchanged"));
      setNote("");
      setReloadKey((k) => k + 1);
    });

  const doConfirm = () => {
    const c = confirm;
    setConfirm(null);
    if (!c) return;
    if (c.kind === "restore") {
      void run(async () => {
        await restoreWorkflowVersion(workflowId, c.n);
        window.location.reload();
      });
    } else if (c.kind === "promote") {
      void run(async () => {
        await promoteWorkflowVersion(workflowId, c.n, label);
        toast.success(
          t("promoteDone", { n: c.n, label: label === "PRODUCTION" ? t("labelProduction") : t("labelStaging") })
        );
      });
    } else {
      void run(async () => {
        await rollbackWorkflowVersion(workflowId);
        toast.success(t("rollbackDone"));
      });
    }
  };

  return (
    <aside
      role="dialog"
      aria-label={t("versionsTitle")}
      className="fixed inset-y-0 end-0 z-50 flex w-full flex-col border-s border-border bg-card shadow-xl sm:max-w-md"
    >
      <div className="flex items-start justify-between gap-3 border-b border-border px-4 py-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 text-base font-semibold tracking-tight text-foreground">
            <History className="size-4 text-muted-foreground" aria-hidden="true" />
            {t("versionsTitle")}
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">{t("versionsIntro")}</p>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-8 shrink-0"
          onClick={onClose}
          aria-label={t("versionsClose")}
        >
          <X className="size-4" aria-hidden="true" />
        </Button>
      </div>

      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-4">
        {!canWrite && <p className="text-xs text-muted-foreground">{t("readOnlyNote")}</p>}

        {canWrite && (
          <div className="space-y-2">
            <Label htmlFor="wf-version-note">{t("saveVersionLabel")}</Label>
            <div className="flex flex-col gap-2 sm:flex-row">
              <Input
                id="wf-version-note"
                value={note}
                maxLength={200}
                onChange={(e) => setNote(e.target.value)}
                placeholder={t("saveVersionPlaceholder")}
                disabled={busy}
              />
              <Button type="button" size="sm" className="shrink-0 sm:h-9" onClick={() => void onSave()} disabled={busy}>
                {busy ? t("savingVersion") : t("saveVersion")}
              </Button>
            </div>
            {canvasDirty && <p className="text-xs text-amber-600 dark:text-amber-400">{t("saveVersionDirty")}</p>}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setConfirm({ kind: "rollback" })}
              disabled={busy || versions.length < 2}
            >
              <Undo2 className="size-3.5" aria-hidden="true" />
              {t("rollbackProduction")}
            </Button>
          </div>
        )}

        {compareFor != null && (
          <div className="[&_.wf-vdiff]:w-full [&_.wf-vdiff]:max-w-full">
            <VersionDiffPanel
              workflowId={workflowId}
              currentVersion={compareFor}
              canvasDirty={canvasDirty}
              onFocusNode={onFocusNode}
              onClose={() => setCompareFor(null)}
            />
          </div>
        )}

        {load.kind === "loading" && (
          <div className="space-y-2" role="status" aria-label={t("loadingVersions")}>
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-16 w-full rounded-lg" />
            ))}
          </div>
        )}

        {load.kind === "unavailable" && <p className="text-sm text-muted-foreground">{t("versionsUnavailable")}</p>}

        {load.kind === "error" && (
          <div
            role="alert"
            className="flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            <span className="min-w-0">{load.message}</span>
            <Button type="button" variant="outline" size="sm" onClick={() => setReloadKey((k) => k + 1)}>
              {t("retry")}
            </Button>
          </div>
        )}

        {load.kind === "ready" && versions.length === 0 && (
          <EmptyState icon={History} title={t("noVersions")} description={t("noVersionsHint")} className="py-8" />
        )}

        {load.kind === "ready" && versions.length > 0 && (
          <>
            {versions.length > 10 && (
              <Input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("searchVersions")}
                aria-label={t("searchVersions")}
              />
            )}
            {filtered.length === 0 ? (
              <p role="status" className="py-4 text-center text-sm text-muted-foreground">
                {t("noMatches")}
              </p>
            ) : (
              <ul className="space-y-2">
                {pg.pageItems.map((v) => (
                  <li key={v.id} className="rounded-lg border border-border bg-background p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-semibold text-foreground">
                        {t("versionNumber", { n: v.versionNumber })}
                      </span>
                      {v.versionNumber === latest && <Badge variant="accent">{t("latest")}</Badge>}
                      <Badge variant="secondary">{prettyStatus(v.status)}</Badge>
                      {v.label && (
                        <Badge variant={v.label === "PRODUCTION" ? "default" : "outline"}>
                          {v.label === "PRODUCTION" ? t("labelProduction") : t("labelStaging")}
                        </Badge>
                      )}
                      <span className="ms-auto text-xs text-muted-foreground">{formatWhen(v.createdAt)}</span>
                    </div>
                    {v.changeSummary && (
                      <p className="mt-1 break-words text-sm text-muted-foreground">{v.changeSummary}</p>
                    )}
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => setCompareFor(v.versionNumber)}
                        disabled={versions.length < 2}
                      >
                        <GitCompare className="size-3.5" aria-hidden="true" />
                        {t("compare")}
                      </Button>
                      {canWrite && (
                        <>
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            disabled={busy}
                            onClick={() => setConfirm({ kind: "restore", n: v.versionNumber })}
                          >
                            <RotateCcw className="size-3.5" aria-hidden="true" />
                            {t("restore")}
                          </Button>
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            disabled={busy}
                            onClick={() => setConfirm({ kind: "promote", n: v.versionNumber })}
                          >
                            <Rocket className="size-3.5" aria-hidden="true" />
                            {t("promote")}
                          </Button>
                        </>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
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

      <AlertDialog open={confirm != null} onOpenChange={(o) => !o && setConfirm(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {confirm?.kind === "restore" && t("restoreTitle", { n: confirm.n })}
              {confirm?.kind === "promote" && t("promoteTitle", { n: confirm.n })}
              {confirm?.kind === "rollback" && t("rollbackTitle")}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {confirm?.kind === "restore" &&
                `${t("restoreBody", { n: confirm.n })}${canvasDirty ? ` ${t("restoreDirty")}` : ""}`}
              {confirm?.kind === "promote" && t("promoteBody")}
              {confirm?.kind === "rollback" && t("rollbackBody")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          {confirm?.kind === "promote" && (
            <div className="space-y-1.5">
              <Label htmlFor="wf-promote-label">{t("promoteTarget")}</Label>
              <Select
                id="wf-promote-label"
                value={label}
                onValueChange={(v) => setLabel(v as VersionLabel)}
                options={[
                  { value: "STAGING", label: t("labelStaging") },
                  { value: "PRODUCTION", label: t("labelProduction") },
                ]}
              />
            </div>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={doConfirm}>
              {confirm?.kind === "restore" && t("restoreConfirm")}
              {confirm?.kind === "promote" && t("promoteConfirm")}
              {confirm?.kind === "rollback" && t("rollbackConfirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </aside>
  );
}
