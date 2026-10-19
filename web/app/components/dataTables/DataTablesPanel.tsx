"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { AlertTriangle, LayoutTemplate, Loader2, Plus, Search, Sparkles, Table2, Upload } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { useAuth } from "../AuthProvider";
import type { DataTableSummary, DataTablesConfig } from "../../lib/dataTables";
import EmptyState from "../EmptyState";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import ImportWizard from "./ImportWizard";
import TemplatesGallery from "./TemplatesGallery";
import CapacityMeter from "./CapacityMeter";
import DataTableCapacity from "./DataTableCapacity";
import DataTableExplainer from "./DataTableExplainer";
import DataTableGrid from "./DataTableGrid";
import { capacity } from "./capacity";
import PaginationControls from "../PaginationControls";
import { ListSkeleton } from "../tenantLists/ListStates";
import { usePagination } from "../../lib/usePagination";

/**
 * /data-tables: the list of the workspace's tables on the left, the grid editor of the selected one on the right.
 *
 * Rendering order follows what the server says: GET /api/data-tables/config is always readable, so the page can
 * tell "not enabled in this deployment" (flag off) and "Premium feature" (plan-locked) apart from a real load
 * failure, and only calls the rest of the API when the feature is usable. The plan lock is an inline upgrade
 * card, never a global error toast.
 */

const NO_LIMITS: DataTablesConfig = {
  enabled: false,
  planLocked: false,
  maxTables: 0,
  maxRowsPerTable: 0,
  maxColumns: 0,
  maxCellBytes: 0,
  maxBulkInsert: 0,
  maxImportBytes: 0,
  maxImportRows: 0,
};

type Drawer = "none" | "create" | "import" | "templates";

function byName(a: DataTableSummary, b: DataTableSummary) {
  return a.name.localeCompare(b.name);
}

export default function DataTablesPanel({ onLoaded }: { onLoaded?: () => void }) {
  const t = useTranslations("dataTables");
  // Tables are tenant-wide shared state; every write is gated server-side to MANAGE_WORKFLOWS_ADMIN (SecurityConfig).
  // Mirror it here so a plain member is not offered controls that can only 403 (B12). Reads stay open to everyone.
  const { hasPermission } = useAuth();
  const canManage = hasPermission("MANAGE_WORKFLOWS_ADMIN");
  const [cfg, setCfg] = useState<DataTablesConfig | null>(null);
  const [failed, setFailed] = useState(false);
  const [tables, setTables] = useState<DataTableSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [drawer, setDrawer] = useState<Drawer>("none");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const tl = useTranslations("tenantLists");
  const [tableQuery, setTableQuery] = useState("");
  const filteredTables = useMemo(() => {
    const q = tableQuery.trim().toLowerCase();
    return q ? tables.filter((x) => x.name.toLowerCase().includes(q)) : tables;
  }, [tables, tableQuery]);
  const tablePager = usePagination(filteredTables, 10);
  const reportedLoaded = useRef(false);
  // The host passes an inline callback: read it through a ref so a new identity never re-runs the load.
  const onLoadedRef = useRef(onLoaded);
  onLoadedRef.current = onLoaded;

  const reportLoaded = useCallback(() => {
    if (reportedLoaded.current) return;
    reportedLoaded.current = true;
    onLoadedRef.current?.();
  }, []);

  const init = useCallback(async () => {
    setFailed(false);
    try {
      const c = await api.getDataTablesConfig();
      setCfg(c);
      if (c?.enabled && !c.planLocked) {
        const list = await api.listDataTables();
        setTables(Array.isArray(list) ? [...list].sort(byName) : []);
      }
    } catch (e: any) {
      // 404 = a core that predates the feature: the same "not enabled" state, not an error.
      if (e?.status === 404) setCfg(NO_LIMITS);
      else setFailed(true);
    } finally {
      reportLoaded();
    }
  }, [reportLoaded]);

  useEffect(() => {
    void init();
  }, [init]);

  const selected = tables.find((x) => x.id === selectedId) ?? null;

  const upsert = (table: DataTableSummary) =>
    setTables((list) => {
      const rest = list.filter((x) => x.id !== table.id);
      return [...rest, table].sort(byName);
    });

  const toggleDrawer = (next: Drawer) => {
    setDrawer((d) => (d === next ? "none" : next));
    setName("");
    setDescription("");
  };

  const create = async () => {
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    try {
      const created = await api.createDataTable({
        name: trimmed,
        description: description.trim() || undefined,
        columns: [],
      });
      upsert(created);
      setSelectedId(created.id);
      setName("");
      setDescription("");
      setDrawer("none");
      toast.success(t("created"));
    } catch {
      /* the server's reason (duplicate name, table limit, ...) was shown by the API layer */
    } finally {
      setBusy(false);
    }
  };

  // Both the wizard and the gallery hand back the finished table (already created and filled).
  const adopt = (table: DataTableSummary) => {
    upsert(table);
    setSelectedId(table.id);
    setDrawer("none");
  };

  if (failed && !cfg) {
    return (
      <Alert variant="destructive">
        <AlertTriangle aria-hidden="true" />
        <AlertTitle>{t("loadFailed")}</AlertTitle>
        <AlertDescription>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => void init()}>
            {t("retry")}
          </Button>
        </AlertDescription>
      </Alert>
    );
  }
  if (!cfg) return <ListSkeleton rows={4} label={t("loading")} />;

  if (!cfg.enabled) {
    return <EmptyState icon={Table2} title={t("disabledTitle")} hint={t("disabledBody")} />;
  }

  if (cfg.planLocked) {
    return (
      <Alert variant="info" data-testid="data-tables-plan-locked">
        <Sparkles aria-hidden="true" />
        <AlertTitle>{t("planLockedTitle")}</AlertTitle>
        <AlertDescription>
          <p>{t("planLockedBody")}</p>
          <Button asChild size="sm" className="mt-2">
            <Link href="/admin/billing">{t("upgrade")}</Link>
          </Button>
        </AlertDescription>
      </Alert>
    );
  }

  const actions = !canManage ? null : (
    <div className="flex flex-wrap gap-2">
      <Button type="button" size="sm" aria-expanded={drawer === "create"} onClick={() => toggleDrawer("create")}>
        <Plus size={14} aria-hidden="true" /> {t("newTable")}
      </Button>
      <Button
        type="button"
        size="sm"
        variant="outline"
        aria-expanded={drawer === "import"}
        onClick={() => toggleDrawer("import")}
      >
        <Upload size={14} aria-hidden="true" /> {t("importCsv")}
      </Button>
      <Button
        type="button"
        size="sm"
        variant="outline"
        aria-expanded={drawer === "templates"}
        onClick={() => toggleDrawer("templates")}
      >
        <LayoutTemplate size={14} aria-hidden="true" /> {t("tplOpen")}
      </Button>
    </div>
  );

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[18rem_minmax(0,1fr)]">
      {failed && (
        <Alert variant="destructive" className="lg:col-span-2">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("loadFailed")}</AlertTitle>
          <AlertDescription>
            <Button variant="outline" size="sm" className="mt-2" onClick={() => void init()}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {/* Read-only figures and the explainer: shown to every member, whatever they may edit. */}
      <div className="flex min-w-0 flex-col gap-3 lg:col-span-2">
        <DataTableCapacity tables={tables} config={cfg} />
        <DataTableExplainer />
      </div>
      <aside className="flex min-w-0 flex-col gap-3">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold text-foreground">{t("listHeading")}</h2>
          <span className="text-xs text-muted-foreground">
            {t("tableLimit", { count: tables.length, max: cfg.maxTables })}
          </span>
        </div>
        {actions}

        {drawer === "create" && (
          <Card>
            <CardContent className="flex flex-col gap-3 pt-4">
              <h3 className="text-sm font-semibold text-foreground">{t("createTitle")}</h3>
              <div>
                <Label htmlFor="data-table-name">{t("nameLabel")}</Label>
                <Input
                  id="data-table-name"
                  value={name}
                  placeholder={t("namePlaceholder")}
                  maxLength={100}
                  autoComplete="off"
                  onChange={(e) => setName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      void create();
                    }
                  }}
                />
              </div>
              <div>
                <Label htmlFor="data-table-description">{t("descriptionLabel")}</Label>
                <Input
                  id="data-table-description"
                  value={description}
                  maxLength={2000}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </div>
              <div className="flex gap-2">
                <Button type="button" size="sm" disabled={!name.trim() || busy} onClick={() => void create()}>
                  {busy ? (
                    <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                  ) : (
                    <Plus size={14} aria-hidden="true" />
                  )}{" "}
                  {t("create")}
                </Button>
                <Button type="button" size="sm" variant="ghost" onClick={() => setDrawer("none")}>
                  {t("cancel")}
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {tables.length > 5 && (
          <div className="relative">
            <Search
              size={14}
              aria-hidden="true"
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              type="search"
              value={tableQuery}
              onChange={(e) => setTableQuery(e.target.value)}
              placeholder={tl("searchTables")}
              aria-label={tl("searchTables")}
              className="pl-8"
            />
          </div>
        )}
        {tables.length > 0 && filteredTables.length === 0 && (
          <p role="status" className="text-sm text-muted-foreground">
            {tl("noMatch")}
          </p>
        )}
        {tables.length > 0 && (
          <ul className="flex flex-col gap-1" data-testid="data-table-list">
            {[
              // Keep the open table visible even when it falls on another page of the list.
              ...(selectedId && filteredTables.some((x) => x.id === selectedId) && !tablePager.pageItems.some((x) => x.id === selectedId)
                ? filteredTables.filter((x) => x.id === selectedId)
                : []),
              ...tablePager.pageItems,
            ].map((x) => (
              <li key={x.id}>
                <button
                  type="button"
                  aria-current={x.id === selectedId ? "true" : undefined}
                  onClick={() => setSelectedId(x.id)}
                  className={`flex w-full flex-col items-start gap-0.5 rounded-md border px-3 py-2 text-left transition-colors ${
                    x.id === selectedId ? "border-primary bg-accent" : "border-border bg-card hover:bg-muted/40"
                  }`}
                >
                  <span className="w-full truncate text-sm font-medium text-foreground">{x.name}</span>
                  <span className="text-xs text-muted-foreground">{t("rowCount", { count: x.rowCount })}</span>
                  <CapacityMeter
                    className="w-full"
                    value={capacity(x.rowCount, cfg.maxRowsPerTable)}
                    label={t("rowMeterLabel", { name: x.name, count: x.rowCount, max: cfg.maxRowsPerTable })}
                    nearLabel={t("capacityNear")}
                    fullLabel={t("capacityFull")}
                  />
                </button>
              </li>
            ))}
          </ul>
        )}
        {tables.length > 0 && (
          <PaginationControls
            page={tablePager.page}
            totalPages={tablePager.totalPages}
            totalItems={tablePager.totalItems}
            pageSize={tablePager.pageSize}
            onPageChange={tablePager.setPage}
            onPageSizeChange={tablePager.setPageSize}
            pageSizeOptions={[10, 25, 50]}
            itemLabel={tl("tables")}
          />
        )}
      </aside>

      <section className="min-w-0">
        {drawer === "import" ? (
          // The multi-step flows (CSV import, templates) get the wide main area, not the narrow list column.
          <ImportWizard config={cfg} onCreated={adopt} onCancel={() => setDrawer("none")} />
        ) : drawer === "templates" ? (
          <TemplatesGallery config={cfg} onCreated={adopt} onCancel={() => setDrawer("none")} />
        ) : selected ? (
          <DataTableGrid
            key={selected.id}
            table={selected}
            config={cfg}
            onChanged={upsert}
            readOnly={!canManage}
            onDeleted={(id) => {
              setTables((list) => list.filter((x) => x.id !== id));
              setSelectedId(null);
            }}
          />
        ) : tables.length === 0 ? (
          <EmptyState icon={Table2} title={t("emptyTitle")} hint={t("emptyHint")} />
        ) : (
          <p className="rounded-md border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
            {t("selectPrompt")}
          </p>
        )}
      </section>
    </div>
  );
}
