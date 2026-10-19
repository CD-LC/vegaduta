// Pure helpers for the Studio "Data" tab: view / edit / export / import the
// app's saved data. appData is a Record<string,string> - a mirror of the
// preview's localStorage - and almost every value is itself JSON text. For
// editing we show the values as real JSON (so a list reads as a list), and on
// the way back turn anything that is not a plain string into JSON text again.

export type AppData = Record<string, string>;

/** The same ceiling the preview-message handler enforces on a snapshot. */
export const MAX_DATA_CHARS = 200_000;

function tryParse(v: string): { ok: true; value: unknown } | { ok: false } {
  try {
    return { ok: true, value: JSON.parse(v) };
  } catch {
    return { ok: false };
  }
}

/** Pretty JSON for the editor. Values that are JSON text for an object /
 * array / number / boolean / null are unwrapped; a value that is a JSON
 * *string* (or not JSON at all) is shown verbatim as a string, so the
 * round trip through parseAppData is lossless. */
export function formatAppData(data: AppData): string {
  const shown: Record<string, unknown> = {};
  for (const k of Object.keys(data)) {
    const v = data[k];
    const p = tryParse(v);
    shown[k] = p.ok && typeof p.value !== "string" ? p.value : v;
  }
  return JSON.stringify(shown, null, 2);
}

/** Parse what the person typed / imported back into the string map. */
export function parseAppData(text: string): { data: AppData } | { error: string } {
  const raw = (text || "").trim();
  if (!raw) return { data: {} };
  let v: unknown;
  try {
    v = JSON.parse(raw);
  } catch (e) {
    return { error: "That is not valid JSON" + (e instanceof Error && e.message ? ` (${e.message})` : "") + "." };
  }
  if (!v || typeof v !== "object" || Array.isArray(v)) return { error: "The data must be a JSON object, like { \"tasks\": [] }." };
  const data: AppData = {};
  for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
    data[k] = typeof val === "string" ? val : JSON.stringify(val);
  }
  if (JSON.stringify(data).length > MAX_DATA_CHARS) return { error: "That is too much data for the preview (limit about 200 KB)." };
  return { data };
}

export function dataSummary(data: AppData): { keys: number; bytes: number } {
  return { keys: Object.keys(data).length, bytes: JSON.stringify(data).length };
}

function csvCell(v: unknown): string {
  let s = v === null || v === undefined ? "" : typeof v === "object" ? JSON.stringify(v) : String(v);
  // Spreadsheet formula guard: a cell that starts like a formula would run on open.
  if (typeof v === "string" && /^([=@]|[+-][a-z(])/i.test(s)) s = "'" + s;
  return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

function rowsToCsv(rows: unknown[][]): string {
  return rows.map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

/** Keys whose value is a non-empty JSON array of objects (a table). */
export function tabularKeys(data: AppData): string[] {
  return Object.keys(data).filter((k) => {
    const p = tryParse(data[k]);
    return p.ok && Array.isArray(p.value) && p.value.length > 0 && p.value.every((r) => r && typeof r === "object" && !Array.isArray(r));
  });
}

/** One table as CSV: header = union of keys in first-seen order. */
export function tableCsv(value: string): string | null {
  const p = tryParse(value);
  if (!p.ok || !Array.isArray(p.value) || p.value.length === 0) return null;
  const list = p.value as unknown[];
  if (list.every((r) => r && typeof r === "object" && !Array.isArray(r))) {
    const cols: string[] = [];
    for (const r of list as Record<string, unknown>[]) for (const c of Object.keys(r)) if (!cols.includes(c)) cols.push(c);
    return rowsToCsv([cols, ...(list as Record<string, unknown>[]).map((r) => cols.map((c) => r[c]))]);
  }
  return rowsToCsv([["value"], ...list.map((x) => [x])]);
}

/** Everything as CSV: either one table (`key` given) or key,value rows. */
export function dataToCsv(data: AppData, key?: string): string {
  if (key && key in data) {
    const t = tableCsv(data[key]);
    if (t) return t;
  }
  return rowsToCsv([["key", "value"], ...Object.keys(data).map((k) => [k, data[k]])]);
}
