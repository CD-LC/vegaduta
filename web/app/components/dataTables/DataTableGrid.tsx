"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowDown, ArrowUp, BarChart3, Braces, Download, Loader2, Plus, Settings2, Trash2, Upload } from "lucide-react";
import { api, triggerBrowserDownload } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import {
  activeFilters,
  buildRowsQuery,
  cellOf,
  csvFilename,
  isCellUnchanged,
  nextSort,
  pageInfo,
  parseCellInput,
  usagePercent,
  type DataTableColumn,
  type DataTableColumnType,
  type DataTableFilterRow,
  type DataTableRow,
  type DataTableRowPage,
  type DataTableSort,
  type DataTableSummary,
  type DataTablesConfig,
} from "../../lib/dataTables";
import EmptyState from "../EmptyState";
import PaginationControls from "../PaginationControls";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import { toCsv } from "../../lib/dataTableCsv";
import ColumnsPanel from "./ColumnsPanel";
import ConfirmDelete from "./ConfirmDelete";
import TableInsights from "./TableInsights";
import UseTablePanel from "./UseTablePanel";
import GridFilterEditor from "./GridFilterEditor";
import GridImportCard from "./GridImportCard";
import GridTitle from "./GridTitle";
import { GridCell, NewRowLine, type Editing } from "./GridRowParts";

/**
 * The grid editor of one data table: a sortable, filterable, paged view of its rows with inline cell editing
 * (click a cell, Enter or click away saves, Escape cancels), an add-row line, the column manager, CSV import
 * and export, rename and delete. Everything the server validates (types, caps, names) is also checked here only
 * to avoid a pointless round trip - a refusal from the server is shown by the shared API layer's toast.
 */

const DEFAULT_PAGE_SIZE = 50;
// "Export this view" pages through the rows endpoint at its ceiling and stops where the server's own export does.
const EXPORT_PAGE = 1000;
const EXPORT_MAX_ROWS = 50_000;


type Props = {
  table: DataTableSummary;
  config: DataTablesConfig;
  /** The table's summary changed (row count, columns, name) - the list on the left follows. */
  onChanged: (table: DataTableSummary) => void;
  onDeleted: (id: string) => void;
  /** The caller lacks MANAGE_WORKFLOWS_ADMIN: every write 403s, so no write control is offered. */
  readOnly?: boolean;
};

export default function DataTableGrid({ table, config, onChanged, onDeleted, readOnly = false }: Props) {
  const t = useTranslations("dataTables");
  const tl = useTranslations("tenantLists");
  const columns = table.columns;

  const [page, setPage] = useState<DataTableRowPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [offset, setOffset] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<DataTableSort | null>(null);
  const [draftFilters, setDraftFilters] = useState<DataTableFilterRow[]>([]);
  const [applied, setApplied] = useState<DataTableFilterRow[]>([]);
  const [showFilters, setShowFilters] = useState(false);
  const [showColumns, setShowColumns] = useState(false);
  const [showImport, setShowImport] = useState(false);
  const [showInsights, setShowInsights] = useState(false);
  const [showUse, setShowUse] = useState(false);
  const [exportingView, setExportingView] = useState(false);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [newRow, setNewRow] = useState<Record<string, string> | null>(null);
  const [saving, setSaving] = useState(false);

  const requestSeq = useRef(0);
  const editRef = useRef<Editing | null>(null);
  const committing = useRef(false);

  const setEdit = (next: Editing | null) => {
    editRef.current = next;
    setEditing(next);
  };

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    setError(false);
    try {
      const p = await api.listDataTableRows(
        table.id,
        buildRowsQuery({ filters: applied, sort, limit: pageSize, offset })
      );
      if (seq !== requestSeq.current) return; // a newer request superseded this one
      if (p.items.length === 0 && p.total > 0 && offset > 0) {
        // The last row of the final page was removed: step back to the last page that still has rows.
        setOffset(Math.max(0, Math.floor((p.total - 1) / pageSize) * pageSize));
        return;
      }
      setPage(p);
    } catch {
      if (seq === requestSeq.current) setError(true);
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [table.id, applied, sort, offset, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = page?.items ?? [];
  const columnsOpen = !readOnly && (showColumns || columns.length === 0);

  // ---- table-level actions


  const removeTable = async () => {
    try {
      await api.deleteDataTable(table.id);
      onDeleted(table.id);
    } catch {
      /* surfaced by the API layer */
    }
  };

  const runExport = async () => {
    try {
      triggerBrowserDownload(await api.exportDataTableCsv(table.id), csvFilename(table.name));
    } catch {
      /* the server's reason was already shown by the API layer's toast */
    }
  };

  // The rows the grid is showing (filter + sort), every page, as a CSV - the server's export always takes the whole table.
  const runExportView = async () => {
    if (exportingView) return;
    setExportingView(true);
    try {
      const all: DataTableRow[] = [];
      let total = Infinity;
      for (let off = 0; off < total && off < EXPORT_MAX_ROWS; off += EXPORT_PAGE) {
        const p = await api.listDataTableRows(
          table.id,
          buildRowsQuery({ filters: applied, sort, limit: EXPORT_PAGE, offset: off })
        );
        all.push(...p.items);
        total = p.total;
        if (p.items.length === 0) break;
      }
      const blob = new Blob(["﻿", toCsv(columns, all)], { type: "text/csv;charset=utf-8" });
      triggerBrowserDownload(blob, csvFilename(`${table.name}-view`));
      if (total > EXPORT_MAX_ROWS) toast.error(t("exportViewTruncated", { max: EXPORT_MAX_ROWS.toLocaleString() }));
    } catch {
      /* the server's reason was already shown by the API layer's toast */
    } finally {
      setExportingView(false);
    }
  };


  // ---- column actions (handlers never reject: errors were already shown)

  const resetView = () => {
    setSort(null);
    setApplied([]);
    setDraftFilters([]);
    setOffset(0);
  };

  const addColumn = async (name: string, type: DataTableColumnType) => {
    try {
      onChanged(await api.addDataTableColumn(table.id, name, type));
      void load();
    } catch {
      /* surfaced by the API layer */
    }
  };

  const alterColumn = async (column: string, patch: { newName?: string; type?: DataTableColumnType }) => {
    try {
      onChanged(await api.alterDataTableColumn(table.id, column, patch));
      if (patch.newName) resetView();
      void load();
    } catch {
      /* surfaced by the API layer */
    }
  };

  const dropColumn = async (column: string) => {
    try {
      onChanged(await api.dropDataTableColumn(table.id, column));
      resetView();
      void load();
    } catch {
      /* surfaced by the API layer */
    }
  };

  // ---- row actions

  const commitEdit = async (row: DataTableRow, col: DataTableColumn, source: "enter" | "blur") => {
    const ed = editRef.current;
    if (!ed || ed.rowId !== row.id || ed.column !== col.name || committing.current) return;
    if (isCellUnchanged(col.type, cellOf(row, col.name), ed.text)) {
      setEdit(null);
      return;
    }
    const parsed = parseCellInput(col.type, ed.text);
    if (parsed.error) {
      const message = t(`cellError_${parsed.error}`);
      if (source === "blur") {
        setEdit(null);
        toast.error(`${col.name}: ${message}`);
      } else {
        setEdit({ ...ed, error: message });
      }
      return;
    }
    // Clearing a text cell removes the value (an empty string would be stored as a real, empty text).
    const value = col.type === "text" && parsed.value === "" ? null : parsed.value;
    committing.current = true;
    try {
      const saved = await api.updateDataTableRow(table.id, row.id, { [col.name]: value });
      setPage((p) => (p ? { ...p, items: p.items.map((r) => (r.id === saved.id ? saved : r)) } : p));
    } catch {
      /* surfaced by the API layer */
    } finally {
      committing.current = false;
      // A click on another cell while this save was in flight opened a newer editor: leave that one alone.
      if (editRef.current === ed) setEdit(null);
    }
  };

  const saveNewRow = async () => {
    if (!newRow || saving) return;
    const values: Record<string, unknown> = {};
    for (const c of columns) {
      const parsed = parseCellInput(c.type, newRow[c.name] ?? "");
      if (parsed.error) {
        toast.error(`${c.name}: ${t(`cellError_${parsed.error}`)}`);
        return;
      }
      if (parsed.value !== null && parsed.value !== "") values[c.name] = parsed.value;
    }
    if (Object.keys(values).length === 0) {
      toast.error(t("rowNeedsValue"));
      return;
    }
    setSaving(true);
    try {
      await api.insertDataTableRow(table.id, values);
      setNewRow(null);
      onChanged({ ...table, rowCount: table.rowCount + 1 });
      // The new row sorts last by creation time and may not match the current filter: drop sort and filter so the
      // row the person just typed is visible, on the page it lands on.
      const viewActive = sort !== null || applied.length > 0;
      const lastOffset = viewActive
        ? Math.floor(table.rowCount / pageSize) * pageSize
        : Math.floor((page?.total ?? 0) / pageSize) * pageSize;
      if (viewActive) {
        setSort(null);
        setApplied([]);
        setDraftFilters([]);
      }
      if (lastOffset !== offset) setOffset(lastOffset);
      else void load();
    } catch {
      /* surfaced by the API layer */
    } finally {
      setSaving(false);
    }
  };

  const removeRow = async (row: DataTableRow) => {
    try {
      await api.deleteDataTableRow(table.id, row.id);
      onChanged({ ...table, rowCount: Math.max(0, table.rowCount - 1) });
      void load();
    } catch {
      /* surfaced by the API layer */
    }
  };

  // ---- filter editing


  const applyFilters = () => {
    setApplied(activeFilters(draftFilters));
    setOffset(0);
  };

  const clearFilters = () => {
    setDraftFilters([]);
    setApplied([]);
    setOffset(0);
  };

  // ---- rendering


  const info = page ? pageInfo(page.offset, page.limit, page.total, page.items.length) : null;
  const percent = usagePercent(table.rowCount, config.maxRowsPerTable);

  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="data-table-grid">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <GridTitle
          table={table}
          readOnly={readOnly}
          percent={percent}
          maxRows={config.maxRowsPerTable}
          onChanged={onChanged}
        />
        <div className="flex flex-wrap items-center gap-2">
          {!readOnly && (
            <Button
              type="button"
              size="sm"
              disabled={columns.length === 0 || newRow !== null}
              onClick={() => setNewRow({})}
            >
              <Plus size={14} aria-hidden="true" /> {t("addRow")}
            </Button>
          )}
          {!readOnly && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              aria-expanded={columnsOpen}
              onClick={() => setShowColumns((v) => !v)}
            >
              <Settings2 size={14} aria-hidden="true" /> {t("columnsHeading")}
            </Button>
          )}
          <Button
            type="button"
            size="sm"
            variant="outline"
            aria-expanded={showFilters}
            disabled={columns.length === 0}
            onClick={() => setShowFilters((v) => !v)}
          >
            {t("filter")}
            {applied.length > 0 && <Badge variant="accent">{applied.length}</Badge>}
          </Button>
          {!readOnly && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              aria-expanded={showImport}
              disabled={columns.length === 0}
              onClick={() => setShowImport((v) => !v)}
            >
              <Upload size={14} aria-hidden="true" /> {t("importCsv")}
            </Button>
          )}
          <Button
            type="button"
            size="sm"
            variant="outline"
            aria-expanded={showInsights}
            disabled={columns.length === 0}
            onClick={() => setShowInsights((v) => !v)}
          >
            <BarChart3 size={14} aria-hidden="true" /> {t("insights")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            aria-expanded={showUse}
            onClick={() => setShowUse((v) => !v)}
          >
            <Braces size={14} aria-hidden="true" /> {t("useTable")}
          </Button>
          <Button type="button" size="sm" variant="outline" onClick={() => void runExport()}>
            <Download size={14} aria-hidden="true" /> {t("exportCsv")}
          </Button>
          {(applied.length > 0 || sort !== null) && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={exportingView}
              onClick={() => void runExportView()}
            >
              {exportingView ? (
                <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              ) : (
                <Download size={14} aria-hidden="true" />
              )}{" "}
              {t("exportView")}
            </Button>
          )}
          {!readOnly && (
            <ConfirmDelete
              trigger={
                <Button type="button" size="sm" variant="ghost" aria-label={t("deleteTable")} title={t("deleteTable")}>
                  <Trash2 size={14} aria-hidden="true" />
                </Button>
              }
              title={t("deleteTableTitle")}
              body={t("deleteTableBody")}
              confirmLabel={t("delete")}
              cancelLabel={t("cancel")}
              onConfirm={removeTable}
            />
          )}
        </div>
      </div>

      {columnsOpen && (
        <ColumnsPanel
          columns={columns}
          maxColumns={config.maxColumns}
          onAdd={addColumn}
          onAlter={alterColumn}
          onDrop={dropColumn}
        />
      )}

      {showInsights && columns.length > 0 && <TableInsights table={table} filters={applied} />}
      {showUse && <UseTablePanel table={table} />}

      <GridImportCard
        open={showImport && !readOnly}
        tableId={table.id}
        onImported={(updated) => {
          onChanged(updated);
          void load();
        }}
      />

      {showFilters && columns.length > 0 && (
        <GridFilterEditor
          columns={columns}
          draftFilters={draftFilters}
          setDraftFilters={setDraftFilters}
          appliedCount={applied.length}
          onApply={applyFilters}
          onClear={clearFilters}
        />
      )}

      {error && (
        <div className="flex items-center gap-3 rounded-md border border-border p-3 text-sm" role="alert">
          <span>{t("loadFailed")}</span>
          <Button type="button" size="sm" variant="outline" onClick={() => void load()}>
            {t("retry")}
          </Button>
        </div>
      )}

      {columns.length === 0 ? (
        <EmptyState title={t("noColumnsTitle")} hint={t("noColumnsHint")} />
      ) : error && !page ? null : loading && !page ? (
        <p className="text-sm text-muted-foreground">{t("loading")}</p>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                {columns.map((c) => (
                  <TableHead
                    key={c.name}
                    aria-sort={
                      sort?.column === c.name ? (sort.direction === "asc" ? "ascending" : "descending") : "none"
                    }
                  >
                    <button
                      type="button"
                      className="inline-flex items-center gap-1 uppercase tracking-wider"
                      aria-label={t("sortBy", { column: c.name })}
                      onClick={() => {
                        setSort(nextSort(sort, c.name));
                        setOffset(0);
                      }}
                    >
                      {c.name}
                      {sort?.column === c.name &&
                        (sort.direction === "asc" ? (
                          <ArrowUp size={12} aria-hidden="true" />
                        ) : (
                          <ArrowDown size={12} aria-hidden="true" />
                        ))}
                    </button>
                    <Badge variant="outline" className="ml-2 normal-case tracking-normal">
                      {t(`type_${c.type}`)}
                    </Badge>
                  </TableHead>
                ))}
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {newRow && (
                <NewRowLine
                  columns={columns}
                  newRow={newRow}
                  setNewRow={setNewRow}
                  saving={saving}
                  onSave={() => void saveNewRow()}
                />
              )}
              {rows.map((row) => (
                <TableRow key={row.id}>
                  {columns.map((c) => (
                    <TableCell key={c.name} className="max-w-[22rem] align-top">
                      <GridCell row={row} col={c} editing={editing} readOnly={readOnly} setEdit={setEdit} commitEdit={commitEdit} />
                    </TableCell>
                  ))}
                  <TableCell>
                    <ConfirmDelete
                      trigger={
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="size-7"
                          aria-label={t("deleteRow")}
                          title={t("deleteRow")}
                        >
                          <Trash2 size={14} aria-hidden="true" />
                        </Button>
                      }
                      title={t("deleteRow")}
                      body={t("deleteRowBody")}
                      confirmLabel={t("delete")}
                      cancelLabel={t("cancel")}
                      onConfirm={() => removeRow(row)}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>

          <PaginationControls
            page={Math.floor(offset / pageSize) + 1}
            totalItems={page ? page.total : 0}
            pageSize={pageSize}
            loading={loading}
            onPageChange={(n) => setOffset((n - 1) * pageSize)}
            onPageSizeChange={(size) => {
              setOffset(Math.floor(offset / size) * size);
              setPageSize(size);
            }}
            pageSizeOptions={[25, 50, 100, 250]}
            itemLabel={tl("rows")}
          />
          {info && info.from <= 0 && <p className="text-sm text-muted-foreground">{t("noRows")}</p>}
        </>
      )}
    </div>
  );
}
