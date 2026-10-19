// "Use this table" snippets: exactly what a workflow Data Table step or an agent tool call needs to reference a
// given table, built from its real name and columns. Pure (dataTableSnippets.test.ts); every key, operation and
// action name here is the one core accepts (WorkflowDataTableNodeHandler, DataTableQueryToolCallback,
// DataTableWriteToolCallback).

import type { DataTableColumn, DataTableColumnType, DataTableSummary } from "./dataTables";

export const QUERY_TOOL = "data_table_query";
export const WRITE_TOOL = "data_table_write";

export type SnippetId = "workflowRead" | "workflowUpsert" | "agentRead" | "agentWrite" | "agentUpsert";

export interface Snippet {
  id: SnippetId;
  /** Whether it is for the workflow step or an agent tool call (picks the heading). */
  surface: "workflow" | "agent";
  text: string;
}

/** An example value of the right shape for a column type. */
export function sampleValue(type: DataTableColumnType, today: string): unknown {
  switch (type) {
    case "number":
      return 42;
    case "boolean":
      return true;
    case "date":
      return today;
    case "json":
      return { note: "example" };
    default:
      return "example";
  }
}

function sampleValues(columns: DataTableColumn[], today: string): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const c of columns.slice(0, 6)) out[c.name] = sampleValue(c.type, today);
  return out;
}

/** A first column to filter or key on: the first non-json one (json cannot be an upsert key), else the first. */
export function keyColumnOf(columns: DataTableColumn[]): DataTableColumn | undefined {
  return columns.find((c) => c.type !== "json") ?? columns[0];
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}

/** Filter example on one column: a range check for numbers and dates, an equality otherwise. */
function filterFor(col: DataTableColumn, today: string): { column: string; op: string; value?: unknown } {
  if (col.type === "number") return { column: col.name, op: "gte", value: 1 };
  if (col.type === "date") return { column: col.name, op: "gte", value: today };
  if (col.type === "boolean") return { column: col.name, op: "eq", value: true };
  return { column: col.name, op: "eq", value: "example" };
}

/**
 * The snippets for a table; `today` is an ISO date used for example date values (passed in so tests are stable).
 * A table with no columns has nothing to reference yet, so the list is empty.
 */
export function buildSnippets(table: Pick<DataTableSummary, "name" | "columns">, today: string): Snippet[] {
  const { name, columns } = table;
  const key = keyColumnOf(columns);
  if (!key) return [];
  const valuesObject = sampleValues(columns, today);
  // The workflow step takes the values as a JSON STRING whose entries may be {{expressions}} from earlier steps.
  const templated: Record<string, string> = {};
  for (const c of columns.slice(0, 6)) templated[c.name] = `{{${c.name}}}`;
  const filter = filterFor(key, today);
  const sortable = columns.find((c) => c.type === "date") ?? key;

  return [
    {
      id: "workflowRead",
      surface: "workflow",
      text: pretty({
        table: name,
        operation: "get_rows",
        filter: [filter],
        sortColumn: sortable.name,
        sortDirection: "desc",
        limit: "20",
      }),
    },
    {
      id: "workflowUpsert",
      surface: "workflow",
      text: pretty({
        table: name,
        operation: "upsert_row",
        keyColumn: key.name,
        valuesJson: JSON.stringify(templated),
      }),
    },
    {
      id: "agentRead",
      surface: "agent",
      text: pretty({ action: "get_rows", table: name, filter: [filter], sortColumn: sortable.name, sortDirection: "desc", limit: 20 }),
    },
    {
      id: "agentWrite",
      surface: "agent",
      text: pretty({ action: "insert_row", table: name, values: valuesObject }),
    },
    {
      id: "agentUpsert",
      surface: "agent",
      text: pretty({ action: "upsert_row", table: name, keyColumn: key.name, values: valuesObject }),
    },
  ];
}

/** Local calendar date as yyyy-MM-dd (what a date column stores for a date-only value). */
export function isoToday(now: Date = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}
