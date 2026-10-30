"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Workflow, Lock, Plus, Search, X, GitBranch, Boxes, Activity, Layers, LayoutTemplate } from "lucide-react";
import { api } from "../lib/api";
import { starterGraph, type WorkflowGraphNode, type WorkflowGraphEdge } from "./workflow/nodeDefs";
import { WORKFLOW_TEMPLATES, buildWorkflowGraph, templateNameKey } from "./workflowTemplates";
import { UpgradeLock } from "./ui/upgrade-lock";
import { EmptyState } from "./ax/page-kit";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import { usePageReady } from "./RouteTransition";
import { useTranslations } from "../lib/i18n";
import { Card, CardContent } from "./ui/card";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "./ui/table";
import { usePortabilityEnabled } from "../lib/usePortabilityEnabled";
import { filterWorkflows, sortWorkflows, statusCounts, workflowLink, type WorkflowSort } from "./workflow/workflowList";
import WorkflowRowActions from "./workflow/WorkflowRowActions";
import { WorkflowListFilters, WorkflowStatTiles } from "./workflow/WorkflowListChrome";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogFooter,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogAction,
  AlertDialogCancel,
} from "./ui/alert-dialog";

// FREE-tier fallback cap (core/security/TenantLimits#maxWorkflows, enforced
// server-side in WorkflowService#create - this is a UX head start, not the
// real gate). The live per-plan cap comes from getTenantSettings()'s
// maxWorkflows below; this constant only covers the default/loading state.
// Lives in lib/planLimits alongside AGENT_LIMIT so the console's two tier
// caps sit together in one file rather than one per panel; see that module
// for the full rationale.
import { WORKFLOW_LIMIT } from "../lib/planLimits";
import { errorMessage } from "../lib/errors";

/**
 * Workflow list — the "Build" section's flagship screen. Rebuilt (2026-07-22,
 * direct feedback: "/workflows page is not standard, apply all applicable
 * shadcn components and corporate/enterprise look") onto the canonical
 * shadcn/ui primitives (Card / Button / Badge / Input / Table / AlertDialog)
 * that every other rebuilt console screen already uses, replacing the bespoke
 * `.ax-wflist-*` CSS that had drifted out of step. The layout is now the
 * standard enterprise "list view" shape: a stat-tile row, a search + primary-
 * action toolbar, a bordered data table, and pagination — so a workspace with
 * many workflows stays legible (also closes the "records over 10 must
 * paginate" gap for this list).
 *
 * List-only (no canvas preview): the drag-and-drop editor lives on its own
 * dedicated route (app/workflows/[id]/page.tsx); this component just navigates
 * there via `onOpen`.
 */
interface WorkflowListItem {
  id: string;
  name: string;
  description?: string;
  status: string;
  graph?: { nodes?: WorkflowGraphNode[]; edges?: WorkflowGraphEdge[] };
  updatedAt: string;
}

interface WorkflowDesignerPanelProps {
  onOpen: (id: string) => void;
  initialTemplateKey?: string | null;
}

export default function WorkflowDesignerPanel({ onOpen, initialTemplateKey }: WorkflowDesignerPanelProps) {
  const tHC = useTranslations("hcComponentsWorkflowDesignerPanel");
  const t = useTranslations("workflowDesignerPanel");
  const portable = usePortabilityEnabled();
  const tTemplates = useTranslations("workflowTemplates");
  const [workflows, setWorkflows] = useState([]);
  const [newName, setNewName] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [deletingId, setDeletingId] = useState(null);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [sort, setSort] = useState<WorkflowSort>("updated");
  const [duplicatingId, setDuplicatingId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  // Set once from initialTemplateKey (the /workflows?template=<key> deep
  // link from /workflows/templates) - createWorkflow() below builds the
  // real starter graph from it instead of the bare starterGraph(). Applying
  // a template only pre-fills this create form, same "review before
  // creating" posture as AgentWorkbench's applyTemplate.
  const [activeTemplate, setActiveTemplate] = useState(null);
  const lastTemplateNameRef = useRef<string | null>(null);

  useEffect(() => {
    if (!initialTemplateKey) return;
    const template = WORKFLOW_TEMPLATES.find((tpl) => tpl.key === initialTemplateKey);
    if (!template) return;
    setActiveTemplate(template);
    // Pre-fill the name too (editable): a deep link used to land on an empty
    // name field, so the first Create click bounced with "name required".
    // A second example replaces the first one's name; a name the person typed
    // themselves is kept.
    const templateName = tTemplates(templateNameKey(template.key));
    setNewName((prev) => (!prev || prev === lastTemplateNameRef.current ? templateName : prev));
    lastTemplateNameRef.current = templateName;
    setNewDescription(template.description);
    setShowCreate(true);
    // The create form sits far below the examples: without this the click looks
    // dead. Wait a frame for the form to mount, then bring it into view.
    requestAnimationFrame(() => {
      const field = document.getElementById(newNameId);
      field?.scrollIntoView({ behavior: "smooth", block: "center" });
      (field as HTMLInputElement | null)?.focus({ preventScroll: true });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- tTemplates is stable per locale; re-run only on a new key
  }, [initialTemplateKey]);
  // Row targeted by the delete-confirmation dialog (null = closed). A modal,
  // focus-trapped confirm instead of an immediate destructive click.
  const [pendingDelete, setPendingDelete] = useState(null);
  const newNameId = useId();
  const newDescriptionId = useId();

  // Hold RouteTransition's cover until the first list answers (or fails),
  // instead of lifting after 200 ms onto an empty list (prod latency pass).
  const [loaded, setLoaded] = useState(false);
  usePageReady(loaded);

  const refresh = useCallback(() => {
    api
      .listWorkflows()
      .then(setWorkflows)
      .catch((err) => setError(err.message || t("couldNotLoad")))
      .finally(() => setLoaded(true));
  }, [t]);

  useEffect(refresh, [refresh]);

  // FREE-tier gate for destructive/paid actions (same posture as AgentsPanel):
  // read the tenant's plan once; `isFreePlan` drives the delete button's
  // upgrade-lock below.
  const [tenantPlan, setTenantPlan] = useState(null);
  // Live per-plan workflow cap from TenantSettingsResponse#maxWorkflows (null
  // = no cap, e.g. UNLIMITED) - defaults to the FREE-tier WORKFLOW_LIMIT
  // constant until this has loaded, same fail-closed posture as before.
  const [maxWorkflows, setMaxWorkflows] = useState(WORKFLOW_LIMIT);
  useEffect(() => {
    api
      .getTenantSettings()
      .then((s) => {
        setTenantPlan(s?.plan ?? null);
        setMaxWorkflows(typeof s?.maxWorkflows === "number" ? s.maxWorkflows : Infinity);
      })
      .catch(() => {});
  }, []);
  const isFreePlan = tenantPlan === "FREE";

  const atLimit = workflows.length >= maxWorkflows;
  const activeCount = useMemo(() => workflows.filter((w) => w.status === "ACTIVE").length, [workflows]);

  // Case-insensitive name/description filter, then shared client-side paging
  // (usePagination — same hook every other list panel uses, default 10/page).
  const statuses = useMemo(() => statusCounts(workflows), [workflows]);
  const filtered = useMemo(
    () => sortWorkflows(filterWorkflows(workflows, statusFilter, query), sort),
    [workflows, statusFilter, query, sort]
  );

  // A filter whose last workflow was deleted would otherwise strand the list on an empty view.
  useEffect(() => {
    if (statusFilter && !statuses.some((s) => s.status === statusFilter)) setStatusFilter("");
  }, [statuses, statusFilter]);

  async function duplicateWorkflow(wf: WorkflowListItem) {
    if (atLimit) return;
    setError(null);
    setDuplicatingId(wf.id);
    try {
      const copy = await api.duplicateWorkflow(wf.id);
      if (copy?.id) onOpen(copy.id);
      else refresh();
    } catch (err) {
      setError(errorMessage(err, "Could not duplicate this workflow."));
    } finally {
      setDuplicatingId(null);
    }
  }

  async function copyWorkflowLink(id: string) {
    try {
      await navigator.clipboard.writeText(workflowLink(window.location.origin, id));
      setCopiedId(id);
      window.setTimeout(() => setCopiedId((c) => (c === id ? null : c)), 1500);
    } catch {
      /* clipboard blocked: nothing to undo */
    }
  }

  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(filtered);

  async function createWorkflow(e: React.FormEvent) {
    e.preventDefault();
    if (atLimit) return;
    // Direct bug report history: an empty/whitespace name used to `return`
    // with zero feedback (a dead-looking button). Tell the user why instead.
    if (!newName.trim()) {
      setError(t("nameRequired"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await api.createWorkflow({
        name: newName.trim(),
        description: newDescription.trim(),
        graph: activeTemplate ? buildWorkflowGraph(activeTemplate) : starterGraph(),
      });
      setNewName("");
      setNewDescription("");
      setActiveTemplate(null);
      setShowCreate(false);
      refresh();
      onOpen(created.id);
    } catch (err) {
      // A real 403 from the server-side cap lands here too; api.js already
      // popped a toast, refresh() re-syncs the count so the UI flips to the
      // limit state immediately.
      setError(errorMessage(err, t("couldNotCreate")));
      refresh();
    } finally {
      setBusy(false);
    }
  }

  async function removeWorkflow(id: string) {
    setError(null);
    setDeletingId(id);
    try {
      await api.deleteWorkflow(id);
      refresh();
    } catch (err) {
      setError(errorMessage(err, t("couldNotDelete")));
    } finally {
      setDeletingId(null);
      setPendingDelete(null);
    }
  }

  const stats = [
    { key: "total", label: t("statTotalLabel"), value: String(workflows.length), icon: Boxes },
    { key: "active", label: t("statActiveLabel"), value: String(activeCount), icon: Activity },
    {
      key: "capacity",
      label: t("statCapacityLabel"),
      value: t("statCapacityValue", {
        used: workflows.length,
        limit: Number.isFinite(maxWorkflows) ? maxWorkflows : "∞",
      }),
      icon: Layers,
    },
  ];

  return (
    <section className="space-y-6">
      {/* Stat tiles */}
      <WorkflowStatTiles stats={stats} />

      {error && (
        <div
          role="alert"
          className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          {error}
        </div>
      )}

      {/* Toolbar: search + primary action / limit pill */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="relative w-full sm:max-w-xs">
          <Search
            aria-hidden="true"
            size={15}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="text"
            placeholder={t("searchPlaceholder")}
            aria-label={t("searchPlaceholder")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="ps-9"
          />
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <Button asChild variant="outline" size="sm">
            <Link href="/workflows/templates">
              <LayoutTemplate aria-hidden="true" size={14} />
              {/* Inline: workflowDesignerPanel.templatesLink never existed in
                  en.json, so t() rendered the raw key in the toolbar. */}
              Templates
            </Link>
          </Button>
          {atLimit ? (
            <Badge
              variant="outline"
              className="gap-1.5 border-[color-mix(in_srgb,var(--state-warning)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] py-1.5 text-[var(--state-warning)]"
            >
              <Lock aria-hidden="true" size={12} />
              {t("limitReached")}
            </Badge>
          ) : (
            <Button onClick={() => setShowCreate((v) => !v)} className="shrink-0">
              {showCreate ? <X aria-hidden="true" size={15} /> : <Plus aria-hidden="true" size={15} />}
              {showCreate ? t("cancelButton") : t("newWorkflowShort")}
            </Button>
          )}
        </div>
      </div>

      {/* Limit banner — upsell lives in the hover popup (UpgradeLock), no
          on-page upgrade button. */}
      {atLimit && (
        <UpgradeLock
          reason={t("upgradeReason", { limit: maxWorkflows })}
          label={t("upgradeYourPlan")}
          className="w-full"
        >
          <div className="w-full rounded-lg border border-amber-500/25 bg-amber-500/5 px-4 py-3 text-sm text-muted-foreground">
            {t("limitBannerPrefix", { limit: maxWorkflows })} {t("limitBannerSuffix")}
          </div>
        </UpgradeLock>
      )}

      {/* Inline create form (revealed by the toolbar button) */}
      {!atLimit && showCreate && (
        <Card>
          <CardContent className="p-5">
            <form onSubmit={createWorkflow} className="space-y-4">
              <div className="text-sm font-medium text-foreground">{t("createHeading")}</div>
              {activeTemplate && (
                <div className="rounded-md border border-primary/25 bg-primary/5 px-3 py-2 text-xs text-muted-foreground">
                  {t("usingTemplatePrefix", { template: tTemplates(templateNameKey(activeTemplate.key)) })}
                </div>
              )}
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor={newNameId}>{t("namePlaceholder")}</Label>
                  <Input
                    id={newNameId}
                    type="text"
                    placeholder={t("namePlaceholder")}
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    aria-describedby="wf-new-name-help"
                    autoFocus
                  />
                  <p id="wf-new-name-help" className="text-xs text-muted-foreground">
                    {tHC("s2")}
                  </p>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor={newDescriptionId}>{t("descriptionPlaceholder")}</Label>
                  <Input
                    id={newDescriptionId}
                    type="text"
                    placeholder={t("descriptionPlaceholder")}
                    value={newDescription}
                    onChange={(e) => setNewDescription(e.target.value)}
                  />
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Button type="submit" disabled={busy}>
                  {busy ? t("creating") : t("createButton")}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    setShowCreate(false);
                    setActiveTemplate(null);
                  }}
                >
                  {t("cancelButton")}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}

      {/* List — the empty state must wait for the first listWorkflows()
          round-trip: rendering it on `workflows.length === 0` alone painted a
          second "New workflow" CTA alongside the toolbar's for the whole
          initial load (confirmed prod E2E defect, 2026-09-25). Whatever falls
          through here during !loaded is invisible anyway: usePageReady(loaded)
          holds RouteTransition's opaque cover over the page until then. */}
      {loaded && workflows.length > 1 && (
        <WorkflowListFilters
          total={workflows.length}
          statuses={statuses}
          statusFilter={statusFilter}
          setStatusFilter={setStatusFilter}
          sort={sort}
          setSort={setSort}
        />
      )}

      {loaded && workflows.length === 0 ? (
        <EmptyState
          icon={Workflow}
          title={t("noWorkflowsYet")}
          description="Wire a trigger, one or more agent steps and an output together, and the workflow runs on its own."
          action={
            <div className="flex flex-wrap items-center justify-center gap-3">
              <Button size="sm" onClick={() => setShowCreate(true)} disabled={atLimit}>
                <Plus aria-hidden="true" size={14} /> {t("newWorkflowShort")}
              </Button>
              <Link
                href="/help#workflows"
                className="text-sm font-medium text-primary underline-offset-4 hover:underline"
              >
                {tHC("s1")}
              </Link>
            </div>
          }
        />
      ) : filtered.length === 0 ? (
        <EmptyState icon={Search} title={t("noResultsTitle")} description={t("noResultsHint")} />
      ) : (
        <Card className="gap-0 overflow-hidden py-0">
          <Table className="table-fixed">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-auto">{t("colWorkflow")}</TableHead>
                <TableHead className="w-28">{t("colStatus")}</TableHead>
                <TableHead className="hidden w-40 md:table-cell">{t("colStructure")}</TableHead>
                <TableHead className="hidden w-40 lg:table-cell">{t("colUpdated")}</TableHead>
                <TableHead className={portable ? "w-52 text-end" : "w-40 text-end"}>{t("colActions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pageItems.map((wf: WorkflowListItem) => {
                const nodes = wf.graph?.nodes?.length ?? 0;
                const edges = wf.graph?.edges?.length ?? 0;
                const active = wf.status === "ACTIVE";
                return (
                  <TableRow
                    key={wf.id}
                    className="cursor-pointer"
                    onClick={() => onOpen(wf.id)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        onOpen(wf.id);
                      }
                    }}
                    aria-label={t("editWorkflow")}
                  >
                    <TableCell>
                      <div className="flex items-center gap-3">
                        <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-secondary text-muted-foreground">
                          <Workflow aria-hidden="true" size={15} />
                        </div>
                        <div className="min-w-0">
                          <div className="truncate font-medium text-foreground">{wf.name}</div>
                          <div className="truncate text-xs text-muted-foreground">
                            {wf.description || t("noDescription")}
                          </div>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={active ? "default" : "secondary"} className="gap-1.5">
                        <span
                          className={`size-1.5 rounded-full ${active ? "bg-primary-foreground" : "bg-muted-foreground"}`}
                        />
                        {wf.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="hidden md:table-cell">
                      <div className="flex items-center gap-3 text-xs text-muted-foreground">
                        <span className="inline-flex items-center gap-1">
                          <Boxes aria-hidden="true" size={13} />{" "}
                          {t("nodeCount", { count: nodes, plural: nodes === 1 ? "" : "s" })}
                        </span>
                        <span className="inline-flex items-center gap-1">
                          <GitBranch aria-hidden="true" size={13} />{" "}
                          {t("connectionCount", { count: edges, plural: edges === 1 ? "" : "s" })}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell className="hidden text-xs text-muted-foreground lg:table-cell">
                      {new Date(wf.updatedAt).toLocaleDateString()}
                    </TableCell>
                    <TableCell onClick={(e) => e.stopPropagation()}>
                      <WorkflowRowActions
                        wf={wf}
                        portable={portable}
                        isFreePlan={isFreePlan}
                        atLimit={atLimit}
                        duplicating={duplicatingId === wf.id}
                        copied={copiedId === wf.id}
                        deleting={deletingId === wf.id}
                        onEdit={() => onOpen(wf.id)}
                        onDuplicate={() => duplicateWorkflow(wf)}
                        onCopyLink={() => copyWorkflowLink(wf.id)}
                        onDelete={() => setPendingDelete(wf)}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <div className="border-t border-border px-3 py-2">
            <PaginationControls
              page={page}
              totalPages={totalPages}
              totalItems={totalItems}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              itemLabel={t("recordsLabel")}
            />
          </div>
        </Card>
      )}

      {/* Delete confirmation */}
      <AlertDialog open={!!pendingDelete} onOpenChange={(open) => !open && setPendingDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("confirmDeleteTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("confirmDeleteBody", { name: pendingDelete?.name ?? "" })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancelButton")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => pendingDelete && removeWorkflow(pendingDelete.id)}
            >
              {t("confirmDeleteConfirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}
