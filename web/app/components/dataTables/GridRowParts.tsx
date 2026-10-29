"use client";

import { Check, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cellOf, cellToDisplay, cellToInput, type DataTableColumn, type DataTableRow } from "../../lib/dataTables";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { TableCell, TableRow } from "../ui/table";

export type Editing = { rowId: string; column: string; text: string; error: string | null };

/**
 * One grid cell: the inline editor while `editing` targets it (Enter or blur
 * saves, Escape cancels), a click-to-edit button otherwise, plain text when
 * read-only.
 */
export function GridCell({
  row,
  col,
  editing,
  readOnly,
  setEdit,
  commitEdit,
}: {
  row: DataTableRow;
  col: DataTableColumn;
  editing: Editing | null;
  readOnly: boolean;
  setEdit: (next: Editing | null) => void;
  commitEdit: (row: DataTableRow, col: DataTableColumn, source: "enter" | "blur") => void | Promise<void>;
}) {
  const t = useTranslations("dataTables");
  if (editing && editing.rowId === row.id && editing.column === col.name) {
    return (
      <div>
        <Input
          autoFocus
          className="h-8"
          value={editing.text}
          aria-label={t("editCell", { column: col.name })}
          onChange={(e) => setEdit({ ...editing, text: e.target.value, error: null })}
          onBlur={() => void commitEdit(row, col, "blur")}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              void commitEdit(row, col, "enter");
            } else if (e.key === "Escape") {
              e.preventDefault();
              setEdit(null);
            }
          }}
        />
        {editing.error && (
          <p className="mt-1 text-xs text-destructive" role="alert">
            {editing.error}
          </p>
        )}
      </div>
    );
  }
  const value = cellOf(row, col.name);
  const display = cellToDisplay(col.type, value);
  if (readOnly) {
    return display === "" ? (
      <span className="text-muted-foreground">-</span>
    ) : (
      <span className="break-words px-1">{display}</span>
    );
  }
  return (
    <button
      type="button"
      className="block min-h-6 w-full rounded px-1 text-start hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40"
      aria-label={t("editCell", { column: col.name })}
      onClick={() => setEdit({ rowId: row.id, column: col.name, text: cellToInput(col.type, value), error: null })}
    >
      {display === "" ? (
        <span className="text-muted-foreground">-</span>
      ) : (
        <span className="break-words">{display}</span>
      )}
    </button>
  );
}

/** The add-row line at the top of the grid: one input per column, Enter saves, Escape cancels. */
export function NewRowLine({
  columns,
  newRow,
  setNewRow,
  saving,
  onSave,
}: {
  columns: DataTableColumn[];
  newRow: Record<string, string>;
  setNewRow: (next: Record<string, string> | null) => void;
  saving: boolean;
  onSave: () => void;
}) {
  const t = useTranslations("dataTables");
  return (
    <TableRow data-testid="data-table-new-row">
      {columns.map((c) => (
        <TableCell key={c.name}>
          <Input
            className="h-8"
            value={newRow[c.name] ?? ""}
            placeholder={t(`type_${c.type}`)}
            aria-label={c.name}
            onChange={(e) => setNewRow({ ...newRow, [c.name]: e.target.value })}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                onSave();
              } else if (e.key === "Escape") {
                setNewRow(null);
              }
            }}
          />
        </TableCell>
      ))}
      <TableCell>
        <div className="flex gap-1">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="size-7"
            disabled={saving}
            aria-label={t("saveRow")}
            title={t("saveRow")}
            onClick={onSave}
          >
            <Check size={14} aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="size-7"
            aria-label={t("cancel")}
            title={t("cancel")}
            onClick={() => setNewRow(null)}
          >
            <X size={14} aria-hidden="true" />
          </Button>
        </div>
      </TableCell>
    </TableRow>
  );
}
