// Pure helpers for the workflow "Data Table" node (type data_table, WorkflowDataTableNodeHandler on the
// server): the operation list, which fields each operation needs, the values-template builder and the
// canvas subtitle. No React, so it is unit-tested (dataTableNode.test.ts).

import type { DataTableColumn, DataTableFilterOp, DataTableFilterRow } from "../../lib/dataTables";

export const DATA_TABLE_OPERATIONS = ["get_rows", "insert_row", "update_rows", "upsert_row", "delete_rows", "count"] as const;
export type DataTableOperation = (typeof DATA_TABLE_OPERATIONS)[number];

/** The config keys the node carries (a subset of WorkflowNodeConfig; `operation` is shared with code/media). */
export interface DataTableNodeConfig {
  table?: string;
  operation?: string;
  filter?: DataTableFilterRow[];
  valuesJson?: string;
  keyColumn?: string;
  sortColumn?: string;
  sortDirection?: string;
  limit?: string;
  confirmAll?: boolean;
}

/** The server's default when no operation is set: the read-only one. */
export function operationOf(config: { operation?: string } | undefined): string {
  const raw = (config?.operation ?? "").trim().toLowerCase().replace(/[-\s]+/g, "_");
  return raw === "" ? "get_rows" : raw;
}

/** Mirrors DataTableNodeOps.isWrite: an operation nobody can classify counts as a write. */
export function isWriteOperation(operation: string | undefined): boolean {
  const op = operationOf({ operation });
  return ["insert_row", "update_rows", "upsert_row", "delete_rows"].includes(op) || !(DATA_TABLE_OPERATIONS as readonly string[]).includes(op);
}

export function operationUsesFilter(op: string): boolean {
  return ["get_rows", "update_rows", "delete_rows", "count"].includes(op);
}

export function operationUsesValues(op: string): boolean {
  return ["insert_row", "update_rows", "upsert_row"].includes(op);
}

export function operationUsesKey(op: string): boolean {
  return op === "upsert_row";
}

export function operationUsesSortAndLimit(op: string): boolean {
  return op === "get_rows";
}

/** update_rows / delete_rows refuse an empty filter on the server unless confirmAll is set. */
export function operationNeedsConfirmAll(op: string): boolean {
  return op === "update_rows" || op === "delete_rows";
}

/** What the saved node is still missing, in the order the form asks for it; null = runnable. */
export function dataTableMissing(config: DataTableNodeConfig | undefined): "table" | "key" | null {
  if (!config?.table?.trim()) return "table";
  if (operationUsesKey(operationOf(config)) && !config.keyColumn?.trim()) return "key";
  return null;
}

/** A starting `valuesJson`: every column mapped to a `{{column}}` expression, pretty-printed. */
export function buildValuesTemplate(columns: DataTableColumn[]): string {
  if (columns.length === 0) return "";
  const obj: Record<string, string> = {};
  for (const c of columns) obj[c.name] = `{{${c.name}}}`;
  return JSON.stringify(obj, null, 2);
}

export function newFilterRow(columns: DataTableColumn[]): DataTableFilterRow {
  return { column: columns[0]?.name ?? "", op: "eq" as DataTableFilterOp, value: "" };
}

/** Canvas subtitle: the translation key (in the workflowNode namespace) and its values. */
export function dataTableSubtitle(config: DataTableNodeConfig | undefined): { key: string; values?: Record<string, string> } {
  const table = config?.table?.trim();
  if (!table) return { key: "dataTableNoTable" };
  const op = operationOf(config);
  const known = (DATA_TABLE_OPERATIONS as readonly string[]).includes(op) ? op : "get_rows";
  return { key: `dataTable_${known}`, values: { table } };
}
