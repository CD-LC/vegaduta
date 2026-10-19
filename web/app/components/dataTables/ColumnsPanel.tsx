"use client";

import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import {
  DATA_TABLE_COLUMN_TYPES,
  isValidColumnName,
  type DataTableColumn,
  type DataTableColumnType,
} from "../../lib/dataTables";
import Select from "../Select";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import ConfirmDelete from "./ConfirmDelete";

/**
 * The column manager of one data table: add a column, rename one or change its type (the server only allows a
 * type change while no row holds a value in that column), delete one. Every handler returns a promise that
 * never rejects (the grid swallows API errors after toJson has shown them), so the confirm dialog always closes.
 */

type Props = {
  columns: DataTableColumn[];
  maxColumns: number;
  onAdd: (name: string, type: DataTableColumnType) => Promise<void>;
  onAlter: (column: string, patch: { newName?: string; type?: DataTableColumnType }) => Promise<void>;
  onDrop: (column: string) => Promise<void>;
};

export default function ColumnsPanel({ columns, maxColumns, onAdd, onAlter, onDrop }: Props) {
  const t = useTranslations("dataTables");
  const [name, setName] = useState("");
  const [type, setType] = useState<DataTableColumnType>("text");
  const [busy, setBusy] = useState(false);
  const typeOptions = DATA_TABLE_COLUMN_TYPES.map((v) => ({ value: v, label: t(`type_${v}`) }));
  const full = columns.length >= maxColumns;
  const nameInvalid = name.trim() !== "" && !isValidColumnName(name);

  const add = async () => {
    if (!isValidColumnName(name) || busy || full) return;
    setBusy(true);
    try {
      await onAdd(name.trim(), type);
      setName("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card data-testid="data-table-columns">
      <CardContent className="space-y-3 pt-4">
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-sm font-semibold text-foreground">{t("columnsHeading")}</h3>
          <span className="text-xs text-muted-foreground">
            {t("columnLimit", { count: columns.length, max: maxColumns })}
          </span>
        </div>

        {columns.map((c) => (
          <ColumnRow key={c.name} column={c} typeOptions={typeOptions} onAlter={onAlter} onDrop={onDrop} />
        ))}

        <div className="flex flex-wrap items-end gap-2 border-t border-border pt-3">
          <div className="min-w-[10rem] flex-1">
            <Label htmlFor="data-table-new-column">{t("columnNameLabel")}</Label>
            <Input
              id="data-table-new-column"
              value={name}
              placeholder={t("columnNamePlaceholder")}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  void add();
                }
              }}
              autoComplete="off"
            />
          </div>
          <div className="w-40">
            <Label>{t("columnTypeLabel")}</Label>
            <Select
              value={type}
              onChange={(e: any) => setType(e.target.value as DataTableColumnType)}
              options={typeOptions}
            />
          </div>
          <Button
            type="button"
            size="sm"
            disabled={!isValidColumnName(name) || busy || full}
            onClick={() => void add()}
          >
            <Plus size={14} aria-hidden="true" /> {t("addColumn")}
          </Button>
        </div>
        {nameInvalid && (
          <p className="text-xs text-destructive" role="alert">
            {t("columnNameInvalid")}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function ColumnRow({
  column,
  typeOptions,
  onAlter,
  onDrop,
}: {
  column: DataTableColumn;
  typeOptions: { value: string; label: string }[];
  onAlter: Props["onAlter"];
  onDrop: Props["onDrop"];
}) {
  const t = useTranslations("dataTables");
  const [name, setName] = useState(column.name);
  const [type, setType] = useState<DataTableColumnType>(column.type);
  const [busy, setBusy] = useState(false);
  const changed = name.trim() !== column.name || type !== column.type;
  const valid = isValidColumnName(name);

  const save = async () => {
    if (!changed || !valid || busy) return;
    setBusy(true);
    try {
      await onAlter(column.name, {
        newName: name.trim() !== column.name ? name.trim() : undefined,
        type: type !== column.type ? type : undefined,
      });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="data-table-column-row">
      <Input
        className="min-w-[10rem] flex-1"
        value={name}
        onChange={(e) => setName(e.target.value)}
        aria-label={t("columnNameLabel")}
        autoComplete="off"
      />
      <div className="w-40">
        <Select
          value={type}
          onChange={(e: any) => setType(e.target.value as DataTableColumnType)}
          options={typeOptions}
          aria-label={t("columnTypeLabel")}
        />
      </div>
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={!changed || !valid || busy}
        onClick={() => void save()}
      >
        {t("renameColumn")}
      </Button>
      <ConfirmDelete
        trigger={
          <Button type="button" variant="ghost" size="icon" aria-label={t("dropColumn")} title={t("dropColumn")}>
            <Trash2 size={14} aria-hidden="true" />
          </Button>
        }
        title={t("dropColumnTitle", { name: column.name })}
        body={t("dropColumnBody")}
        confirmLabel={t("dropColumn")}
        cancelLabel={t("cancel")}
        onConfirm={() => onDrop(column.name)}
      />
    </div>
  );
}
