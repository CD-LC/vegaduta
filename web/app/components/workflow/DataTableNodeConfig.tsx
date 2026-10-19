"use client";

import { useEffect, useMemo, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { api } from "../../lib/api";
import {
  opsForType,
  opTakesValue,
  type DataTableColumn,
  type DataTableFilterOp,
  type DataTableFilterRow,
  type DataTableSummary,
} from "../../lib/dataTables";
import { useTranslations } from "../../lib/i18n";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { ScopedExpressionField } from "./ExpressionField";
import {
  DATA_TABLE_OPERATIONS,
  buildValuesTemplate,
  newFilterRow,
  operationNeedsConfirmAll,
  operationOf,
  operationUsesFilter,
  operationUsesKey,
  operationUsesSortAndLimit,
  operationUsesValues,
  type DataTableNodeConfig as NodeConfig,
} from "./dataTableNode";

/**
 * Data Table node (type data_table): the form for WorkflowDataTableNodeHandler's config. Rendered from
 * NodeConfigDrawer. Every text field is an expression field (the server resolves table, keyColumn, valuesJson,
 * filter[].value, sortColumn and limit before the node runs), so a value may be {{field}} from an earlier step.
 *
 * The table picker lists the workspace's tables when the feature is on; it only fills the "Table" field (which
 * stays free text so a table can also be chosen by an expression) and, once a table is known, feeds the column
 * dropdowns and the "fill from columns" shortcut. When the feature is off the form still works as plain text and
 * says that the step is skipped at run time.
 */

type Translator = (key: string, values?: Record<string, string | number>) => string;

type Props = {
  config: NodeConfig;
  // any: the drawer passes its own Partial<WorkflowNodeConfig> setter (an interface, so not assignable to a Record).
  setConfig: (patch: any) => void;
};

const SYSTEM_ID_COLUMN = "_id";

export default function DataTableNodeConfig({ config, setConfig }: Props) {
  const t = useTranslations("dataTables") as Translator;
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [locked, setLocked] = useState(false);
  const [tables, setTables] = useState<DataTableSummary[]>([]);

  useEffect(() => {
    let cancelled = false;
    api
      .getDataTablesConfig()
      .then(async (cfg) => {
        if (cancelled) return;
        const on = !!cfg?.enabled;
        setEnabled(on);
        setLocked(on && !!cfg.planLocked);
        if (on && !cfg.planLocked) {
          const list = await api.listDataTables();
          if (!cancelled) setTables(Array.isArray(list) ? list : []);
        }
      })
      .catch(() => {
        // Older core (404 is quiet) or a transient failure: the form stays usable as plain text.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const op = operationOf(config);
  const tableName = (config.table || "").trim();
  const selected = useMemo(
    () => tables.find((x) => x.name.toLowerCase() === tableName.toLowerCase() || x.id === tableName) ?? null,
    [tables, tableName]
  );
  const columns: DataTableColumn[] = selected?.columns ?? [];
  const filter: DataTableFilterRow[] = Array.isArray(config.filter) ? config.filter : [];

  const columnOptions = [
    ...columns.map((c) => ({ value: c.name, label: `${c.name} (${t(`type_${c.type}`)})` })),
    { value: SYSTEM_ID_COLUMN, label: SYSTEM_ID_COLUMN },
  ];
  const typeOfColumn = (name: string) => columns.find((c) => c.name === name)?.type ?? "text";

  const updateFilter = (index: number, patch: Partial<DataTableFilterRow>) =>
    setConfig({ filter: filter.map((row, i) => (i === index ? { ...row, ...patch } : row)) });

  const opOptions = (column: string) => {
    const allowed: DataTableFilterOp[] =
      column === SYSTEM_ID_COLUMN ? ["eq", "ne", "in"] : opsForType(typeOfColumn(column));
    return allowed.map((o) => ({ value: o, label: t(`op_${o}`) }));
  };

  return (
    <>
      <p className="ax-wf-empty">{t("nodeHint")}</p>
      {enabled === false && <p className="ax-wf-empty">{t("nodeUnavailable")}</p>}

      <div className="field">
        <Label>{t("nodeTableLabel")}</Label>
        {tables.length > 0 && (
          <div style={{ marginBottom: "0.4rem" }}>
            <Select
              value={selected ? selected.name : ""}
              onChange={(e: any) => setConfig({ table: String(e.target.value || "") })}
              options={tables.map((x) => ({ value: x.name, label: x.name }))}
              placeholder={t("nodePickTable")}
              searchable
            />
          </div>
        )}
        <ScopedExpressionField
          field="table"
          value={config.table || ""}
          onChange={(v) => setConfig({ table: v })}
          placeholder={t("nodeTablePlaceholder")}
          ariaLabel={t("nodeTableLabel")}
        />
        {enabled === true && locked && <p className="ax-wf-empty">{t("planLockedBody")}</p>}
        {enabled === true && !locked && tables.length === 0 && <p className="ax-wf-empty">{t("nodeNoTables")}</p>}
      </div>

      <div className="field">
        <Label>{t("nodeOperationLabel")}</Label>
        <Select
          value={op}
          onChange={(e: any) => setConfig({ operation: e.target.value })}
          options={DATA_TABLE_OPERATIONS.map((o) => ({ value: o, label: t(`nodeOp_${o}`) }))}
        />
      </div>

      {operationUsesKey(op) && (
        <div className="field">
          <Label>{t("nodeKeyColumnLabel")}</Label>
          {columns.length > 0 && (
            <div style={{ marginBottom: "0.4rem" }}>
              <Select
                value={columns.some((c) => c.name === config.keyColumn) ? config.keyColumn : ""}
                onChange={(e: any) => setConfig({ keyColumn: String(e.target.value || "") })}
                options={columns.filter((c) => c.type !== "json").map((c) => ({ value: c.name, label: c.name }))}
                placeholder={t("nodeKeyColumnLabel")}
              />
            </div>
          )}
          <ScopedExpressionField
            field="keyColumn"
            value={config.keyColumn || ""}
            onChange={(v) => setConfig({ keyColumn: v })}
            ariaLabel={t("nodeKeyColumnLabel")}
          />
          <p className="ax-wf-empty">{t("nodeKeyColumnHint")}</p>
        </div>
      )}

      {operationUsesValues(op) && (
        <div className="field">
          <Label>{t("nodeValuesLabel")}</Label>
          <ScopedExpressionField
            field="valuesJson"
            multiline
            value={config.valuesJson || ""}
            onChange={(v) => setConfig({ valuesJson: v })}
            placeholder={t("nodeValuesPlaceholder")}
            ariaLabel={t("nodeValuesLabel")}
          />
          <p className="ax-wf-empty">{op === "update_rows" ? t("nodeValuesHintUpdate") : t("nodeValuesHintInsert")}</p>
          {columns.length > 0 && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setConfig({ valuesJson: buildValuesTemplate(columns) })}
            >
              {t("nodeFillTemplate")}
            </Button>
          )}
        </div>
      )}

      {operationUsesFilter(op) && (
        <div className="field">
          <Label>{t("nodeFilterLabel")}</Label>
          <div style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
            {filter.map((row, index) => (
              <div
                key={index}
                style={{
                  display: "flex",
                  flexDirection: "column",
                  gap: "0.3rem",
                  borderLeft: "2px solid var(--border)",
                  paddingLeft: "0.5rem",
                }}
              >
                <div className="row" style={{ gap: "0.4rem", alignItems: "center" }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    {columns.length > 0 ? (
                      <Select
                        value={row.column}
                        onChange={(e: any) => {
                          const column = String(e.target.value || "");
                          const allowed =
                            column === SYSTEM_ID_COLUMN ? ["eq", "ne", "in"] : opsForType(typeOfColumn(column));
                          updateFilter(index, { column, op: (allowed as string[]).includes(row.op) ? row.op : "eq" });
                        }}
                        options={columnOptions}
                        placeholder={t("filterColumn")}
                        aria-label={t("filterColumn")}
                      />
                    ) : (
                      <Input
                        type="text"
                        value={row.column}
                        onChange={(e) => updateFilter(index, { column: e.target.value })}
                        placeholder={t("filterColumn")}
                        aria-label={t("filterColumn")}
                      />
                    )}
                  </div>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    onClick={() => setConfig({ filter: filter.filter((_, i) => i !== index) })}
                    title={t("removeCondition")}
                    aria-label={t("removeCondition")}
                  >
                    <Trash2 size={14} aria-hidden="true" />
                  </Button>
                </div>
                <Select
                  value={row.op}
                  onChange={(e: any) => updateFilter(index, { op: e.target.value as DataTableFilterOp })}
                  options={opOptions(row.column)}
                  aria-label={t("filterOperator")}
                />
                {opTakesValue(row.op) && (
                  <ScopedExpressionField
                    field={`filter[${index}].value`}
                    value={row.value || ""}
                    onChange={(v) => updateFilter(index, { value: v })}
                    placeholder={t("nodeFilterValuePlaceholder")}
                    ariaLabel={t("filterValue")}
                  />
                )}
              </div>
            ))}
            <div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setConfig({ filter: [...filter, newFilterRow(columns)] })}
              >
                <Plus size={14} aria-hidden="true" />
                {t("nodeAddFilter")}
              </Button>
            </div>
          </div>
        </div>
      )}

      {operationUsesSortAndLimit(op) && (
        <>
          <div className="field">
            <Label>{t("nodeSortLabel")}</Label>
            <ScopedExpressionField
              field="sortColumn"
              value={config.sortColumn || ""}
              onChange={(v) => setConfig({ sortColumn: v })}
              placeholder={t("nodeSortNone")}
              ariaLabel={t("nodeSortLabel")}
            />
          </div>
          <div className="field">
            <Label>{t("nodeSortDirLabel")}</Label>
            <Select
              value={config.sortDirection === "desc" ? "desc" : "asc"}
              onChange={(e: any) => setConfig({ sortDirection: e.target.value })}
              options={[
                { value: "asc", label: t("nodeSortAsc") },
                { value: "desc", label: t("nodeSortDesc") },
              ]}
            />
          </div>
          <div className="field">
            <Label>{t("nodeLimitLabel")}</Label>
            <ScopedExpressionField
              field="limit"
              value={config.limit || ""}
              onChange={(v) => setConfig({ limit: v })}
              placeholder="100"
              ariaLabel={t("nodeLimitLabel")}
            />
          </div>
        </>
      )}

      {operationNeedsConfirmAll(op) && (
        <>
          <label
            className="row"
            style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}
          >
            <input
              type="checkbox"
              checked={!!config.confirmAll}
              onChange={(e) => setConfig({ confirmAll: e.target.checked })}
            />
            {t("nodeConfirmAllLabel")}
          </label>
          <p className="ax-wf-empty">{t("nodeConfirmAllHint")}</p>
        </>
      )}
    </>
  );
}
