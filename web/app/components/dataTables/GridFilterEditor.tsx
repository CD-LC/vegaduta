"use client";

import { Plus, Trash2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import {
  emptyFilterRow,
  opsForType,
  opTakesValue,
  type DataTableColumn,
  type DataTableColumnType,
  type DataTableFilterOp,
  type DataTableFilterRow,
} from "../../lib/dataTables";
import Select from "../Select";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";

/* eslint-disable @typescript-eslint/no-explicit-any */
const MAX_FILTER_ROWS = 10;

/** The grid's filter builder: up to MAX_FILTER_ROWS column/operator/value conditions, applied together. */
export default function GridFilterEditor({
  columns,
  draftFilters,
  setDraftFilters,
  appliedCount,
  onApply,
  onClear,
}: {
  columns: DataTableColumn[];
  draftFilters: DataTableFilterRow[];
  setDraftFilters: (update: (rows: DataTableFilterRow[]) => DataTableFilterRow[]) => void;
  appliedCount: number;
  onApply: () => void;
  onClear: () => void;
}) {
  const t = useTranslations("dataTables");
  const updateFilter = (index: number, patch: Partial<DataTableFilterRow>) =>
    setDraftFilters((rowsNow) => rowsNow.map((r, i) => (i === index ? { ...r, ...patch } : r)));

  const typeOf = (name: string): DataTableColumnType => columns.find((c) => c.name === name)?.type ?? "text";

  return (
    <Card>
      <CardContent className="flex flex-col gap-3 pt-4">
        {draftFilters.map((f, index) => (
          <div
            key={index}
            className="grid grid-cols-1 items-center gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,1.4fr)_auto]"
          >
            <Select
              value={f.column}
              onChange={(e: any) => {
                const column = String(e.target.value || "");
                const allowed: DataTableFilterOp[] = opsForType(typeOf(column));
                updateFilter(index, { column, op: allowed.includes(f.op) ? f.op : "eq" });
              }}
              options={columns.map((c) => ({ value: c.name, label: c.name }))}
              placeholder={t("filterColumn")}
              aria-label={t("filterColumn")}
            />
            <Select
              value={f.op}
              onChange={(e: any) => updateFilter(index, { op: e.target.value as DataTableFilterOp })}
              options={opsForType(typeOf(f.column)).map((o) => ({ value: o, label: t(`op_${o}`) }))}
              aria-label={t("filterOperator")}
            />
            {opTakesValue(f.op) && typeOf(f.column) === "boolean" ? (
              <Select
                value={f.value === "false" ? "false" : f.value === "true" ? "true" : ""}
                onChange={(e: any) => updateFilter(index, { value: String(e.target.value || "") })}
                options={[
                  { value: "true", label: t("boolTrue") },
                  { value: "false", label: t("boolFalse") },
                ]}
                placeholder={t("filterValue")}
                aria-label={t("filterValue")}
              />
            ) : opTakesValue(f.op) ? (
              <Input
                value={f.value}
                onChange={(e) => updateFilter(index, { value: e.target.value })}
                placeholder={t("filterValue")}
                aria-label={t("filterValue")}
              />
            ) : (
              <span />
            )}
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("removeCondition")}
              title={t("removeCondition")}
              onClick={() => setDraftFilters((rowsNow) => rowsNow.filter((_, i) => i !== index))}
            >
              <Trash2 size={14} aria-hidden="true" />
            </Button>
          </div>
        ))}
        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={draftFilters.length >= MAX_FILTER_ROWS}
            onClick={() =>
              setDraftFilters((rowsNow) => [...rowsNow, { ...emptyFilterRow(), column: columns[0]?.name ?? "" }])
            }
          >
            <Plus size={14} aria-hidden="true" /> {t("addCondition")}
          </Button>
          <Button type="button" size="sm" onClick={onApply}>
            {t("applyFilters")}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={draftFilters.length === 0 && appliedCount === 0}
            onClick={onClear}
          >
            {t("clearFilters")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
