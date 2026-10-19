// Client-side CSV helpers for Data Tables: parse a file, infer column names and types, map rows onto typed values
// for POST /api/data-tables/{id}/rows/bulk, and write a CSV back out. No React, no fetch, so every rule is
// unit-tested (dataTableCsv.test.ts).
//
// The naming and type-inference rules deliberately mirror core's DataTableCsv (sanitizeName / inferType /
// looksNumeric) so a file previewed here yields the same table the server's own "import as new table" would; the
// difference is that the person sees the result first and may override a name or type before anything is created.

import { parseCellInput, type CellError, type DataTableColumn, type DataTableColumnType } from "./dataTables";

export interface ParsedCsv {
  header: string[];
  rows: string[][];
  delimiter: "," | ";" | "\t";
}

const FORMULA_CHARS = "=+-@\t\r";

function sniffDelimiter(text: string): "," | ";" | "\t" {
  const nl = text.indexOf("\n");
  const head = nl < 0 ? text : text.slice(0, nl);
  const count = (c: string) => head.split(c).length - 1;
  const commas = count(",");
  const semis = count(";");
  const tabs = count("\t");
  if (tabs > commas && tabs >= semis) return "\t";
  return semis > commas ? ";" : ",";
}

/** RFC 4180 tokenizer (doubled-quote escapes, newlines inside quotes); the delimiter is sniffed from the first line. */
export function parseCsv(input: string): ParsedCsv {
  const text = input.replace(/﻿/g, "");
  const delimiter = sniffDelimiter(text);
  const records: string[][] = [];
  let current: string[] = [];
  let field = "";
  let inQuotes = false;
  let any = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += c;
      }
      continue;
    }
    if (c === '"') {
      inQuotes = true;
      any = true;
    } else if (c === delimiter) {
      current.push(field);
      field = "";
      any = true;
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      if (any || field.length > 0) {
        current.push(field);
        records.push(current);
      }
      current = [];
      field = "";
      any = false;
    } else {
      field += c;
      any = true;
    }
  }
  if (any || field.length > 0) {
    current.push(field);
    records.push(current);
  }
  if (records.length === 0) return { header: [], rows: [], delimiter };
  return { header: records[0].map((h) => h.trim()), rows: records.slice(1), delimiter };
}

// ---------------------------------------------------------------------------
// Names and types
// ---------------------------------------------------------------------------

/** core DataTableCsv.sanitizeName: runs of other characters become "_", a non-letter start gets "c_", empty is column_N. */
export function importColumnName(header: string, position: number): string {
  let s = header.trim().replace(/[^A-Za-z0-9_]+/g, "_").replace(/^_+|_+$/g, "");
  if (s === "") return `column_${position}`;
  if (!/^[A-Za-z]/.test(s)) s = `c_${s}`;
  return s.slice(0, 64);
}

/** Sanitised names made unique case-insensitively with _2, _3 ... suffixes (as the server does). */
export function uniqueColumnNames(headers: string[]): string[] {
  const used = new Set<string>();
  return headers.map((h, i) => {
    const name = importColumnName(h, i + 1);
    let unique = name;
    let n = 2;
    while (used.has(unique.toLowerCase())) {
      const suffix = `_${n++}`;
      unique = name.slice(0, 64 - suffix.length) + suffix;
    }
    used.add(unique.toLowerCase());
    return unique;
  });
}

function looksNumeric(v: string): boolean {
  if (!/^-?\d+(\.\d+)?$/.test(v)) return false;
  const digits = v.startsWith("-") ? v.slice(1) : v;
  if (digits.length > 15) return false;
  return !(digits.length > 1 && digits[0] === "0" && digits[1] !== ".");
}

function looksDate(v: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(v);
  if (m) {
    const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
    const dt = new Date(Date.UTC(y, mo - 1, d));
    return dt.getUTCFullYear() === y && dt.getUTCMonth() === mo - 1 && dt.getUTCDate() === d;
  }
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?Z$/.test(v) && !Number.isNaN(Date.parse(v));
}

/** core DataTableCsv.inferType: boolean, number or date when EVERY non-empty value fits, else text. */
export function inferColumnType(values: string[]): DataTableColumnType {
  let number = true;
  let bool = true;
  let date = true;
  let seen = 0;
  for (const raw of values) {
    const v = (raw ?? "").trim();
    if (v === "") continue;
    seen++;
    number = number && looksNumeric(v);
    bool = bool && (v.toLowerCase() === "true" || v.toLowerCase() === "false");
    date = date && looksDate(v);
    if (!number && !bool && !date) return "text";
  }
  if (seen === 0) return "text";
  if (bool) return "boolean";
  if (number) return "number";
  if (date) return "date";
  return "text";
}

export interface ImportColumn {
  /** The header exactly as written in the file. */
  header: string;
  /** The column name that will be created (editable in the preview). */
  name: string;
  type: DataTableColumnType;
  /** The first few non-empty values, for the preview. */
  sample: string[];
}

export interface ImportPlan {
  columns: ImportColumn[];
  rows: string[][];
  delimiter: ParsedCsv["delimiter"];
}

/** Parses a file and proposes columns. An empty file (no header) gives zero columns. */
export function buildImportPlan(text: string): ImportPlan {
  const parsed = parseCsv(text);
  const names = uniqueColumnNames(parsed.header);
  const columns = parsed.header.map((header, i) => {
    const values = parsed.rows.map((r) => r[i] ?? "");
    return {
      header,
      name: names[i],
      type: inferColumnType(values),
      sample: values.filter((v) => v.trim() !== "").slice(0, 3),
    };
  });
  return { columns, rows: parsed.rows, delimiter: parsed.delimiter };
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/** Drops the quote an export puts in front of a formula-looking text cell (core DataTableCsv.unneutralize). */
export function unneutralize(cell: string): string {
  if (cell.length > 1 && cell[0] === "'" && needsGuard(cell.slice(1))) return cell.slice(1);
  return cell;
}

function needsGuard(v: string): boolean {
  if (v === "") return false;
  if (FORMULA_CHARS.includes(v[0])) return true;
  return v.length > 1 && v[0] === "'" && FORMULA_CHARS.includes(v[1]);
}

export interface RowProblem {
  /** 1-based data row (the header is not counted), as the server reports it. */
  row: number;
  column: string;
  error: CellError;
}

export interface MappedRows {
  rows: Record<string, unknown>[];
  /** Rows left out: empty, or holding a cell that does not fit its column's type. */
  skipped: number;
  problems: RowProblem[];
}

const MAX_REPORTED_PROBLEMS = 50;

/** Types each cell for its column; an empty cell is "no value"; a row with a bad cell is skipped and reported. */
export function mapRows(columns: { name: string; type: DataTableColumnType }[], rows: string[][]): MappedRows {
  const out: Record<string, unknown>[] = [];
  const problems: RowProblem[] = [];
  let skipped = 0;
  rows.forEach((rec, index) => {
    const values: Record<string, unknown> = {};
    let bad: RowProblem | null = null;
    for (let i = 0; i < columns.length && i < rec.length; i++) {
      const cell = rec[i];
      if (cell === undefined || cell === "") continue;
      const col = columns[i];
      const text = col.type === "text" || col.type === "json" ? unneutralize(cell) : cell;
      const parsed = parseCellInput(col.type, text);
      if (parsed.error) {
        bad = { row: index + 1, column: col.name, error: parsed.error };
        break;
      }
      if (parsed.value !== null && parsed.value !== "") values[col.name] = parsed.value;
    }
    if (bad) {
      skipped++;
      if (problems.length < MAX_REPORTED_PROBLEMS) problems.push(bad);
    } else if (Object.keys(values).length === 0) {
      skipped++;
    } else {
      out.push(values);
    }
  });
  return { rows: out, skipped, problems };
}

export function chunk<T>(items: T[], size: number): T[][] {
  const n = Math.max(1, Math.floor(size));
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += n) out.push(items.slice(i, i + n));
  return out;
}

// ---------------------------------------------------------------------------
// Writing CSV
// ---------------------------------------------------------------------------

function escapeCell(value: string, neutralize: boolean): string {
  let v = value;
  if (neutralize && needsGuard(v)) v = `'${v}`;
  if (/[",\n\r;\t]/.test(v)) return `"${v.replace(/"/g, '""')}"`;
  return v;
}

function cellText(col: DataTableColumn, value: unknown): string {
  if (value === null || value === undefined) return "";
  if (col.type === "json") return typeof value === "string" ? value : JSON.stringify(value);
  return String(value);
}

/**
 * The rows as CSV text, declared columns only, CRLF line ends. Text cells that start with = + - @ get a leading quote
 * (spreadsheet formula injection), exactly as the server's own export does, so a re-import restores them.
 */
export function toCsv(columns: DataTableColumn[], rows: { data: Record<string, unknown> }[]): string {
  const lines: string[] = [columns.map((c) => escapeCell(c.name, true)).join(",")];
  for (const row of rows) {
    lines.push(
      columns
        .map((c) => {
          const value = row.data[c.name];
          const userText = c.type === "text" || (c.type === "json" && typeof value === "string");
          return escapeCell(cellText(c, value), userText);
        })
        .join(",")
    );
  }
  return lines.join("\r\n") + "\r\n";
}
