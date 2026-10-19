// Data Tables (core/agents com.agenticai.core.tables, /api/data-tables): types and the pure helpers the
// console page uses - cell parsing, filter/sort query building, paging and name rules. No React, no fetch,
// so every rule is unit-tested (dataTables.test.ts). The server is the source of truth for every rule here
// (DataTableValidator / DataTableFilter / DataTableQueryBuilder); this module only avoids a round trip for
// obviously wrong input and renders what the server stored.

export type DataTableColumnType = "text" | "number" | "boolean" | "date" | "json";

export const DATA_TABLE_COLUMN_TYPES: readonly DataTableColumnType[] = ["text", "number", "boolean", "date", "json"];

export interface DataTableColumn {
  name: string;
  type: DataTableColumnType;
}

export interface DataTableSummary {
  id: string;
  name: string;
  description: string | null;
  columns: DataTableColumn[];
  rowCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface DataTableRow {
  id: string;
  data: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface DataTableRowPage {
  items: DataTableRow[];
  total: number;
  limit: number;
  offset: number;
}

/** GET /api/data-tables/config - readable even while the feature is off. */
export interface DataTablesConfig {
  enabled: boolean;
  /** The flag is on, but this tenant's plan is excluded (renders the upgrade card). */
  planLocked: boolean;
  maxTables: number;
  maxRowsPerTable: number;
  maxColumns: number;
  maxCellBytes: number;
  maxBulkInsert: number;
  maxImportBytes: number;
  maxImportRows: number;
}

export interface DataTableImportResult {
  inserted: number;
  skipped: number;
  errors: string[];
  ignoredHeaders: string[];
  table: DataTableSummary;
}

export type DataTableFilterOp =
  | "eq"
  | "ne"
  | "gt"
  | "gte"
  | "lt"
  | "lte"
  | "contains"
  | "starts_with"
  | "in"
  | "is_null"
  | "not_null";

export interface DataTableFilterRow {
  column: string;
  op: DataTableFilterOp;
  value: string;
}

export interface DataTableSort {
  column: string;
  direction: "asc" | "desc";
}

// ---------------------------------------------------------------------------
// Column names
// ---------------------------------------------------------------------------

/** Same grammar as the server's DataTableColumn.NAME_PATTERN: a letter, then letters/digits/underscores, up to 64. */
export const COLUMN_NAME_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

export function isValidColumnName(name: string): boolean {
  return COLUMN_NAME_RE.test(name.trim());
}

/** Turns free text ("Order total ($)") into a valid column name, or "" when nothing usable is left. */
export function suggestColumnName(raw: string): string {
  const cleaned = raw
    .trim()
    .replace(/[^A-Za-z0-9_]+/g, "_")
    .replace(/^_+|_+$/g, "");
  if (!cleaned) return "";
  const named = /^[A-Za-z]/.test(cleaned) ? cleaned : `c_${cleaned}`;
  return named.slice(0, 64);
}

// ---------------------------------------------------------------------------
// Cells
// ---------------------------------------------------------------------------

export type CellError = "number" | "boolean" | "date" | "json";

/**
 * `error` is null when the text parsed (then `value` is what to send; null clears the cell). A plain object
 * rather than a boolean-discriminated union: this project's tsconfig is not strict, so such a union would not narrow.
 */
export interface CellParse {
  value: unknown;
  error: CellError | null;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}(?:[T ].*)?$/;

/** Parses what a person typed into a grid cell for a column of `type`. Empty text clears a non-text cell. */
export function parseCellInput(type: DataTableColumnType, text: string): CellParse {
  if (type === "text") return { value: text, error: null };
  const t = text.trim();
  if (t === "") return { value: null, error: null };
  switch (type) {
    case "number": {
      const n = Number(t);
      return Number.isFinite(n) ? { value: n, error: null } : { value: null, error: "number" };
    }
    case "boolean": {
      const lower = t.toLowerCase();
      if (["true", "yes", "y", "1"].includes(lower)) return { value: true, error: null };
      if (["false", "no", "n", "0"].includes(lower)) return { value: false, error: null };
      return { value: null, error: "boolean" };
    }
    case "date":
      return ISO_DATE.test(t) && !Number.isNaN(Date.parse(t.replace(" ", "T")))
        ? { value: t.replace(" ", "T"), error: null }
        : { value: null, error: "date" };
    default:
      try {
        return { value: JSON.parse(t), error: null };
      } catch {
        return { value: null, error: "json" };
      }
  }
}

/** The text an editor starts from for a stored cell value. */
export function cellToInput(type: DataTableColumnType, value: unknown): string {
  if (value === null || value === undefined) return "";
  if (type === "json") return typeof value === "string" ? value : JSON.stringify(value);
  return String(value);
}

const DISPLAY_CAP = 160;

/** The text a grid cell shows (long values are cut; the full value is what editing opens). */
export function cellToDisplay(type: DataTableColumnType, value: unknown): string {
  const text = cellToInput(type, value);
  return text.length > DISPLAY_CAP ? `${text.slice(0, DISPLAY_CAP)}…` : text;
}

/** True when the edited text equals what is already stored - nothing to save. */
export function isCellUnchanged(type: DataTableColumnType, stored: unknown, text: string): boolean {
  const parsed = parseCellInput(type, text);
  if (parsed.error) return false;
  const before = stored === undefined ? null : stored;
  if (type === "json") return JSON.stringify(parsed.value ?? null) === JSON.stringify(before);
  // null and "" are the same empty text
  if (type === "text") return (before ?? "") === parsed.value;
  return before === parsed.value;
}

// ---------------------------------------------------------------------------
// Filter + sort + paging
// ---------------------------------------------------------------------------

const TEXT_OPS: DataTableFilterOp[] = ["eq", "ne", "contains", "starts_with", "gt", "gte", "lt", "lte", "in", "is_null", "not_null"];
const NUMBER_OPS: DataTableFilterOp[] = ["eq", "ne", "gt", "gte", "lt", "lte", "in", "is_null", "not_null"];
const BOOLEAN_OPS: DataTableFilterOp[] = ["eq", "ne", "is_null", "not_null"];
const DATE_OPS: DataTableFilterOp[] = ["eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts_with", "in", "is_null", "not_null"];
const JSON_OPS: DataTableFilterOp[] = ["eq", "ne", "contains", "is_null", "not_null"];

/** The operators the server accepts for a column type (mirrors DataTableQueryBuilder#requireLegal). */
export function opsForType(type: DataTableColumnType): DataTableFilterOp[] {
  switch (type) {
    case "number":
      return NUMBER_OPS;
    case "boolean":
      return BOOLEAN_OPS;
    case "date":
      return DATE_OPS;
    case "json":
      return JSON_OPS;
    default:
      return TEXT_OPS;
  }
}

export function opTakesValue(op: DataTableFilterOp): boolean {
  return op !== "is_null" && op !== "not_null";
}

export function emptyFilterRow(): DataTableFilterRow {
  return { column: "", op: "eq", value: "" };
}

/** The complete conditions of a draft filter: a row with no column, or a value-taking op with no value, is still being typed. */
export function activeFilters(rows: DataTableFilterRow[]): DataTableFilterRow[] {
  return rows.filter((r) => r.column.trim() !== "" && (!opTakesValue(r.op) || r.value.trim() !== ""));
}

/** Cycles a column header click: none -> ascending -> descending -> none. */
export function nextSort(current: DataTableSort | null, column: string): DataTableSort | null {
  if (!current || current.column !== column) return { column, direction: "asc" };
  if (current.direction === "asc") return { column, direction: "desc" };
  return null;
}

export interface RowsQuery {
  filters?: DataTableFilterRow[];
  sort?: DataTableSort | null;
  limit?: number;
  offset?: number;
}

/** The query string (no leading "?") for GET /api/data-tables/{id}/rows. */
export function buildRowsQuery(q: RowsQuery): string {
  const qs = new URLSearchParams();
  const filters = activeFilters(q.filters ?? []);
  if (filters.length > 0) {
    qs.set(
      "filter",
      JSON.stringify(
        filters.map((f) => (opTakesValue(f.op) ? { column: f.column.trim(), op: f.op, value: f.value } : { column: f.column.trim(), op: f.op }))
      )
    );
  }
  if (q.sort) qs.set("sort", `${q.sort.column}:${q.sort.direction}`);
  if (q.limit !== undefined) qs.set("limit", String(q.limit));
  if (q.offset !== undefined && q.offset > 0) qs.set("offset", String(q.offset));
  return qs.toString();
}

export interface PageInfo {
  /** 1-based first row on the page, 0 when empty. */
  from: number;
  to: number;
  hasPrev: boolean;
  hasNext: boolean;
}

export function pageInfo(offset: number, limit: number, total: number, shown: number): PageInfo {
  if (total <= 0 || shown <= 0) return { from: 0, to: 0, hasPrev: offset > 0, hasNext: false };
  return { from: offset + 1, to: offset + shown, hasPrev: offset > 0, hasNext: offset + limit < total };
}

/** The file name a CSV export is saved under. */
export function csvFilename(tableName: string): string {
  const base = tableName.trim().replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^[-.]+|[-.]+$/g, "");
  return `${base || "data-table"}.csv`;
}

/** How full a table is, 0-100, for the row-limit hint. */
export function usagePercent(rowCount: number, maxRows: number): number {
  if (maxRows <= 0) return 0;
  return Math.min(100, Math.round((rowCount / maxRows) * 100));
}

/** Reads the cell of a row for a column (missing = null). */
export function cellOf(row: DataTableRow, column: string): unknown {
  const v = row.data[column];
  return v === undefined ? null : v;
}
