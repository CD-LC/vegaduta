"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Download, LayoutGrid, Loader2, Plus, Rows3, Search, Upload } from "lucide-react";
import { api, triggerBrowserDownload } from "../../lib/api";
import type { LeadImportResult } from "../../lib/api/leads";
import { useTranslations } from "../../lib/i18n";
import { toast } from "../../lib/toastBus";
import {
  LEAD_STAGES,
  formatValue,
  groupLeadsByStage,
  initials,
  leadDisplayName,
  moveLeadLocally,
  normalizeTotals,
  openPipelineValue,
  scoreTone,
  type Lead,
  type LeadStage,
  type StageTotal,
} from "../../lib/leads";
import Select from "../Select";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import LeadDrawer from "./LeadDrawer";
import PaginationControls from "../PaginationControls";
import { COLUMN_PAGE_SIZE, boardIsTruncated, mergeLeadsById, nextColumnPage, remainingInColumn } from "./boardPaging";

// Leads CRM screen (/admin/leads, backend V337): kanban pipeline board and a
// paginated table over the same lead store, with a detail drawer (edit +
// activity timeline), CSV import/export. The board loads up to BOARD_LIMIT
// leads (column headers use the server's per-stage totals, so counts/values
// stay right past that cap); the table pages server-side.

const BOARD_LIMIT = 200;
const VIEW_KEY = "vd_leads_view";
const STACK_LABEL = "font-mono text-[11px] font-medium uppercase tracking-[0.12em] text-muted-foreground";

type View = "board" | "table";
type DrawerState = { lead: Lead | null; stage?: LeadStage } | null;

function readStoredView(): View {
  try {
    return window.localStorage.getItem(VIEW_KEY) === "table" ? "table" : "board";
  } catch {
    return "board";
  }
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

export default function LeadsPanel({ onLoaded }: { onLoaded?: () => void }) {
  const t = useTranslations("leadsPage");
  const [view, setView] = useState<View>("board");
  const [q, setQ] = useState("");
  const [source, setSource] = useState("");
  const [stageFilter, setStageFilter] = useState("");
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [loadingStage, setLoadingStage] = useState<LeadStage | null>(null);
  const tg = useTranslations("f2GapsAdmin");
  const [leads, setLeads] = useState<Lead[]>([]);
  const [total, setTotal] = useState(0);
  const [totals, setTotals] = useState<StageTotal[]>(() => normalizeTotals(null));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [drawer, setDrawer] = useState<DrawerState>(null);
  const [showImport, setShowImport] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<LeadImportResult | null>(null);
  const [dragOver, setDragOver] = useState<LeadStage | null>(null);
  const requestSeq = useRef(0);
  const loadedOnce = useRef(false);

  const debouncedQ = useDebounced(q, 300);
  const debouncedSource = useDebounced(source, 300);

  useEffect(() => {
    setView(readStoredView());
  }, []);

  const changeView = (next: View) => {
    setView(next);
    setPage(0);
    try {
      window.localStorage.setItem(VIEW_KEY, next);
    } catch {
      /* storage unavailable - the toggle still works for this visit */
    }
  };

  const refresh = useCallback(async () => {
    const seq = ++requestSeq.current;
    setError(false);
    try {
      const [list, pipeline] = await Promise.all([
        api.listLeads(
          view === "board"
            ? { q: debouncedQ, source: debouncedSource, size: BOARD_LIMIT }
            : { q: debouncedQ, source: debouncedSource, stage: stageFilter, page, size: pageSize }
        ),
        api.getLeadsPipeline().catch((): null => null),
      ]);
      if (seq !== requestSeq.current) return; // a newer request superseded this one
      setLeads(list.items ?? []);
      setTotal(list.total ?? 0);
      setTotals(normalizeTotals(pipeline));
    } catch {
      if (seq !== requestSeq.current) return;
      setError(true);
    } finally {
      if (seq === requestSeq.current) {
        setLoading(false);
        if (!loadedOnce.current) {
          loadedOnce.current = true;
          onLoaded?.();
        }
      }
    }
  }, [view, debouncedQ, debouncedSource, stageFilter, page, pageSize, onLoaded]);

  useEffect(() => {
    setLoading(true);
    void refresh();
  }, [refresh]);

  // Filters reset paging.
  useEffect(() => {
    setPage(0);
  }, [debouncedQ, debouncedSource, stageFilter]);

  const grouped = useMemo(() => groupLeadsByStage(leads), [leads]);
  const totalsByStage = useMemo(() => Object.fromEntries(totals.map((s) => [s.stage, s])) as Record<LeadStage, StageTotal>, [totals]);
  const openValue = openPipelineValue(totals);
  const allCount = totals.reduce((n, s) => n + s.count, 0);
  // Pipeline totals ignore the search/source filters, so truncation hints only apply to the unfiltered board.
  const unfiltered = !debouncedQ && !debouncedSource;

  async function moveLead(id: string, stage: LeadStage) {
    const current = leads.find((l) => l.id === id);
    if (!current || current.stage === stage) return;
    const before = leads;
    setLeads((prev) => moveLeadLocally(prev, id, stage)); // optimistic
    try {
      await api.setLeadStage(id, stage);
      void refresh();
    } catch (e: any) {
      setLeads(before);
      toast.error(e?.message || t("moveFailed"));
    }
  }

  async function loadMoreInColumn(stage: LeadStage) {
    if (loadingStage) return;
    setLoadingStage(stage);
    try {
      const loaded = leads.filter((l) => l.stage === stage).length;
      const res = await api.listLeads({
        q: debouncedQ,
        source: debouncedSource,
        stage,
        page: nextColumnPage(loaded),
        size: COLUMN_PAGE_SIZE,
      });
      setLeads((prev) => mergeLeadsById(prev, res.items ?? []));
    } catch (e: any) {
      toast.error(e?.message || t("loadFailed"));
    } finally {
      setLoadingStage(null);
    }
  }

  function shiftStage(lead: Lead, delta: number) {
    const idx = LEAD_STAGES.indexOf(lead.stage) + delta;
    if (idx >= 0 && idx < LEAD_STAGES.length) void moveLead(lead.id, LEAD_STAGES[idx]);
  }

  async function runImport() {
    if (!importFile) return;
    setImporting(true);
    setImportResult(null);
    try {
      const result = await api.importLeadsCsv(importFile);
      setImportResult(result);
      toast.success(t("importDone", { created: result.created, updated: result.updated }));
      setImportFile(null);
      void refresh();
    } catch (e: any) {
      toast.error(e?.message || t("importFailed"));
    } finally {
      setImporting(false);
    }
  }

  async function runExport() {
    try {
      triggerBrowserDownload(await api.exportLeadsCsv(), "leads.csv");
    } catch (e: any) {
      toast.error(e?.message || t("exportFailed"));
    }
  }

  const onDrawerChanged = (saved?: Lead, deletedId?: string) => {
    if (deletedId) setLeads((prev) => prev.filter((l) => l.id !== deletedId));
    if (saved) setDrawer((d) => (d && d.lead ? { ...d, lead: saved } : d));
    void refresh();
  };

  const stageBadge = (stage: LeadStage) => <Badge variant="outline">{t(`stage_${stage}`)}</Badge>;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  const scoreChip = (score: number) => {
    const tone = scoreTone(score);
    const cls = tone === "high" ? "text-[var(--state-success)]" : tone === "mid" ? "text-[var(--state-warning)]" : "text-muted-foreground";
    return <span className={`font-mono text-xs ${cls}`} title={t("fieldScore")}>{score}</span>;
  };

  const leadCard = (lead: Lead) => (
    <div
      key={lead.id}
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData("text/plain", lead.id);
        e.dataTransfer.effectAllowed = "move";
      }}
      className="rounded-md border border-border bg-card p-3 shadow-sm"
      data-testid="lead-card"
    >
      <button type="button" className="block w-full text-left" onClick={() => setDrawer({ lead })}>
        <div className="flex items-start justify-between gap-2">
          <span className="truncate text-sm font-medium">{leadDisplayName(lead)}</span>
          {scoreChip(lead.score)}
        </div>
        {(lead.company || lead.title) && (
          <div className="truncate text-xs text-muted-foreground">{[lead.title, lead.company].filter(Boolean).join(" · ")}</div>
        )}
        {lead.value != null && <div className="mt-1 font-mono text-xs">{formatValue(lead.value)}</div>}
        {(lead.tags?.length ?? 0) > 0 && (
          <div className="mt-1 flex flex-wrap gap-1">
            {lead.tags!.slice(0, 3).map((tag) => (
              <Badge key={tag} variant="secondary">{tag}</Badge>
            ))}
          </div>
        )}
      </button>
      <div className="mt-2 flex justify-between">
        <Button
          type="button" variant="ghost" size="icon" className="size-7"
          disabled={lead.stage === LEAD_STAGES[0]} aria-label={t("movePrev")} onClick={() => shiftStage(lead, -1)}
        >
          <ChevronLeft size={14} />
        </Button>
        <Button
          type="button" variant="ghost" size="icon" className="size-7"
          disabled={lead.stage === LEAD_STAGES[LEAD_STAGES.length - 1]} aria-label={t("moveNext")} onClick={() => shiftStage(lead, 1)}
        >
          <ChevronRight size={14} />
        </Button>
      </div>
    </div>
  );

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-[12rem] flex-1 sm:max-w-xs">
          <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <Input className="pl-8" value={q} placeholder={t("searchPlaceholder")} aria-label={t("searchPlaceholder")} onChange={(e) => setQ(e.target.value)} />
        </div>
        <Input className="w-36" value={source} placeholder={t("sourceFilter")} aria-label={t("sourceFilter")} onChange={(e) => setSource(e.target.value)} />
        {view === "table" && (
          <div className="w-40">
            <Select
              value={stageFilter}
              onChange={(e: any) => setStageFilter(e.target.value)}
              options={[{ value: "", label: t("allStages") }, ...LEAD_STAGES.map((s) => ({ value: s, label: t(`stage_${s}`) }))]}
            />
          </div>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <div role="group" aria-label={t("viewToggle")} className="inline-flex overflow-hidden rounded-md border border-border">
            <Button type="button" size="sm" variant={view === "board" ? "secondary" : "ghost"} aria-pressed={view === "board"} onClick={() => changeView("board")}>
              <LayoutGrid size={14} /> {t("viewBoard")}
            </Button>
            <Button type="button" size="sm" variant={view === "table" ? "secondary" : "ghost"} aria-pressed={view === "table"} onClick={() => changeView("table")}>
              <Rows3 size={14} /> {t("viewTable")}
            </Button>
          </div>
          <Button type="button" variant="outline" size="sm" onClick={() => setShowImport((v) => !v)} aria-expanded={showImport}>
            <Upload size={14} /> {t("importButton")}
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={runExport}>
            <Download size={14} /> {t("exportButton")}
          </Button>
          <Button type="button" size="sm" onClick={() => setDrawer({ lead: null })}>
            <Plus size={14} /> {t("newLead")}
          </Button>
        </div>
      </div>

      <p className="text-sm text-muted-foreground">{t("summary", { count: allCount, value: formatValue(openValue) || "0" })}</p>

      {showImport && (
        <Card>
          <CardContent className="flex flex-col gap-3 pt-4">
            <p className="text-sm text-muted-foreground">{t("importHint")}</p>
            <div className="flex flex-wrap items-center gap-2">
              <Input
                type="file"
                accept=".csv,.txt,text/csv"
                className="max-w-sm"
                aria-label={t("importFileLabel")}
                onChange={(e) => {
                  setImportFile(e.target.files?.[0] ?? null);
                  setImportResult(null);
                }}
              />
              <Button type="button" variant="outline" size="sm" disabled={importing || !importFile} onClick={runImport}>
                {importing ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />} {t("importRun")}
              </Button>
            </div>
            {importResult && (
              <div className="text-sm" role="status">
                <p>{t("importSummary", { created: importResult.created, updated: importResult.updated, skipped: importResult.skipped })}</p>
                {importResult.errors.length > 0 && (
                  <ul className="mt-1 list-disc pl-5 text-xs text-muted-foreground">
                    {importResult.errors.slice(0, 10).map((msg) => (
                      <li key={msg}>{msg}</li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {error && (
        <div className="flex items-center gap-3 rounded-md border border-border p-3 text-sm" role="alert">
          <span>{t("loadFailed")}</span>
          <Button type="button" size="sm" variant="outline" onClick={() => void refresh()}>{t("retry")}</Button>
        </div>
      )}

      {view === "board" && unfiltered && !loading && boardIsTruncated(allCount, leads.length) && (
        <p className="rounded-md border border-border bg-secondary/30 p-3 text-sm text-muted-foreground" role="status" data-testid="board-truncated">
          {tg("leadsTruncated", { shown: leads.length, total: allCount })}
        </p>
      )}

      {loading && leads.length === 0 && !error ? (
        <p className="text-sm text-muted-foreground">{t("loading")}</p>
      ) : view === "board" ? (
        <div className="grid auto-cols-[minmax(15rem,1fr)] grid-flow-col gap-3 overflow-x-auto pb-2" data-testid="lead-board">
          {LEAD_STAGES.map((stage) => {
            const stageTotal = totalsByStage[stage];
            return (
              <section
                key={stage}
                aria-label={t(`stage_${stage}`)}
                className={`flex min-h-[12rem] flex-col gap-2 rounded-lg border bg-secondary/30 p-2 transition-colors ${dragOver === stage ? "border-primary" : "border-border"}`}
                onDragOver={(e) => {
                  e.preventDefault();
                  e.dataTransfer.dropEffect = "move";
                  if (dragOver !== stage) setDragOver(stage);
                }}
                onDragLeave={() => setDragOver((s) => (s === stage ? null : s))}
                onDrop={(e) => {
                  e.preventDefault();
                  setDragOver(null);
                  const id = e.dataTransfer.getData("text/plain");
                  if (id) void moveLead(id, stage);
                }}
              >
                <header className="flex items-center justify-between gap-2 px-1">
                  <div>
                    <div className={STACK_LABEL}>{t(`stage_${stage}`)}</div>
                    <div className="text-xs text-muted-foreground">
                      {t("columnCount", { count: stageTotal?.count ?? 0 })}
                      {stageTotal && stageTotal.value > 0 ? ` · ${formatValue(stageTotal.value)}` : ""}
                    </div>
                  </div>
                  <Button type="button" variant="ghost" size="icon" className="size-7" aria-label={t("addToStage", { stage: t(`stage_${stage}`) })} onClick={() => setDrawer({ lead: null, stage })}>
                    <Plus size={14} />
                  </Button>
                </header>
                {grouped[stage].map(leadCard)}
                {grouped[stage].length === 0 && <p className="px-1 py-4 text-center text-xs text-muted-foreground">{t("columnEmpty")}</p>}
                {unfiltered && remainingInColumn(stageTotal?.count ?? 0, grouped[stage].length) > 0 && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={loadingStage === stage}
                    onClick={() => void loadMoreInColumn(stage)}
                  >
                    {loadingStage === stage ? <Loader2 size={14} className="animate-spin" /> : null}{" "}
                    {tg("showMore", { count: remainingInColumn(stageTotal?.count ?? 0, grouped[stage].length) })}
                  </Button>
                )}
              </section>
            );
          })}
        </div>
      ) : leads.length === 0 && !error ? (
        <p className="rounded-md border border-dashed border-border p-6 text-center text-sm text-muted-foreground">{t("emptyState")}</p>
      ) : (
        <>
          <div className="hidden sm:block">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("fieldName")}</TableHead>
                  <TableHead>{t("fieldCompany")}</TableHead>
                  <TableHead>{t("fieldStage")}</TableHead>
                  <TableHead>{t("fieldScore")}</TableHead>
                  <TableHead>{t("fieldValue")}</TableHead>
                  <TableHead>{t("fieldSource")}</TableHead>
                  <TableHead>{t("fieldOwner")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {leads.map((lead) => (
                  <TableRow key={lead.id} className="cursor-pointer" onClick={() => setDrawer({ lead })}>
                    <TableCell>
                      <button type="button" className="flex items-center gap-2 text-left" onClick={(e) => { e.stopPropagation(); setDrawer({ lead }); }}>
                        <span className="grid size-7 shrink-0 place-items-center rounded-md bg-secondary font-mono text-[11px]">{initials(lead)}</span>
                        <span>
                          <span className="block text-sm font-medium">{leadDisplayName(lead)}</span>
                          {lead.email && lead.name && <span className="block text-xs text-muted-foreground">{lead.email}</span>}
                        </span>
                      </button>
                    </TableCell>
                    <TableCell>{lead.company}</TableCell>
                    <TableCell>{stageBadge(lead.stage)}</TableCell>
                    <TableCell>{scoreChip(lead.score)}</TableCell>
                    <TableCell className="font-mono text-xs">{formatValue(lead.value)}</TableCell>
                    <TableCell>{lead.source}</TableCell>
                    <TableCell>{lead.owner}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <ul className="flex flex-col gap-2 sm:hidden">
            {leads.map((lead) => (
              <li key={lead.id}>
                <button type="button" className="w-full rounded-md border border-border bg-card p-3 text-left" onClick={() => setDrawer({ lead })}>
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-sm font-medium">{leadDisplayName(lead)}</span>
                    {stageBadge(lead.stage)}
                  </div>
                  <div className="truncate text-xs text-muted-foreground">{[lead.company, lead.source].filter(Boolean).join(" · ")}</div>
                </button>
              </li>
            ))}
          </ul>
          <PaginationControls
            page={page + 1}
            totalPages={pageCount}
            totalItems={total}
            pageSize={pageSize}
            onPageChange={(p) => setPage(Math.max(0, p - 1))}
            onPageSizeChange={(n) => {
              setPageSize(n);
              setPage(0);
            }}
            itemLabel={tg("leadsItemLabel")}
            loading={loading}
          />
        </>
      )}

      {drawer && (
        <LeadDrawer
          lead={drawer.lead}
          initialStage={drawer.stage}
          onClose={() => setDrawer(null)}
          onChanged={onDrawerChanged}
        />
      )}
    </div>
  );
}
