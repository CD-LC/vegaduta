// Pure logic behind the Data API panel: policy payloads, dirty detection, origin
// parsing, risk warnings and the copy-paste client snippet. Kept free of React so
// it is unit-tested (dataApi.test.ts) and mirrors the server's own rules
// (app-builder/app/dataapi.py) so the UI rejects what the API would.

import type { AppBuilderDataTable, DataOp } from "./lib";

export const DATA_OPS: DataOp[] = ["select", "insert", "update", "delete"];

// Same shape the server accepts: scheme://host[:port], no path.
const LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
const ORIGIN_RX = new RegExp(String.raw`^https?:\/\/${LABEL}(?:\.${LABEL})*(?::(\d{1,5}))?$`);
export const MAX_ORIGINS = 20;

export type Policies = Record<string, Record<DataOp, boolean>>;

/** The full policy map the API expects: only tables with at least one operation on. */
export function policiesPayload(tables: AppBuilderDataTable[]): Policies {
  const out: Policies = {};
  for (const t of tables) {
    if (DATA_OPS.some((o) => t.policy[o])) {
      out[t.name] = {
        select: !!t.policy.select,
        insert: !!t.policy.insert,
        update: !!t.policy.update,
        delete: !!t.policy.delete,
      };
    }
  }
  return out;
}

export function parseOrigins(raw: string): { origins: string[]; invalid: string[] } {
  const parts = raw
    .split(/[\s,]+/)
    .map((p) => p.trim().replace(/\/+$/, "").toLowerCase())
    .filter(Boolean);
  const origins: string[] = [];
  const invalid: string[] = [];
  for (const p of parts) {
    const m = ORIGIN_RX.exec(p);
    const port = m && m[1] ? Number(m[1]) : null;
    const hostLen = p.replace(/^https?:\/\//, "").replace(/:\d+$/, "").length;
    if (!m || hostLen > 253 || (port !== null && (port < 1 || port > 65535))) invalid.push(p);
    else if (!origins.includes(p)) origins.push(p);
  }
  if (origins.length > MAX_ORIGINS) invalid.push(`more than ${MAX_ORIGINS} origins`);
  return { origins: origins.sort(), invalid };
}

export function isDirty(
  saved: AppBuilderDataTable[],
  draft: AppBuilderDataTable[],
  savedOrigins: string[],
  draftOriginsRaw: string
): boolean {
  const a = JSON.stringify(policiesPayload(saved));
  const b = JSON.stringify(policiesPayload(draft));
  if (a !== b) return true;
  const parsed = parseOrigins(draftOriginsRaw);
  // Something typed that cannot be saved is still an unsaved edit: the owner
  // must hear why on Save, not have it silently dropped.
  if (parsed.invalid.length > 0) return true;
  return JSON.stringify([...savedOrigins].sort()) !== JSON.stringify(parsed.origins);
}

/** Warnings shown next to a table so the owner understands what the anon key can do. */
export function riskNotes(t: AppBuilderDataTable): string[] {
  const notes: string[] = [];
  if (t.policy.delete) notes.push("Anyone who opens your app can delete rows from this table.");
  if (t.policy.update) notes.push("Anyone who opens your app can change any row in this table.");
  if (t.policy.insert && !t.policy.select && !t.policy.update && !t.policy.delete) {
    notes.push("Anyone who opens your app can add rows to this table, so expect spam. Add validation or rate limits before sharing it widely.");
  }
  if (t.policy.select && !t.policy.update && !t.policy.delete) {
    // read-only is the common safe case: no note
  } else if (t.policy.select) {
    notes.push("Every row is readable by anyone with the app.");
  }
  return notes;
}

function jsString(s: string): string {
  return JSON.stringify(s);
}

/** A ready-to-paste client for the app, using the (public-by-design) anon key. */
export function fetchSnippet(baseUrl: string, anonKey: string, tables: AppBuilderDataTable[]): string {
  const exposed = tables.find((t) => t.policy.select || t.policy.insert);
  const table = exposed ? exposed.name : "your_table";
  const lines = [
    "// The anon key is safe in client code: it can only do what your table policies allow.",
    `const VD_URL = ${jsString(baseUrl)};`,
    `const VD_KEY = ${jsString(anonKey)};`,
    "",
    "async function vd(path, init = {}) {",
    "  const res = await fetch(`${VD_URL}${path}`, {",
    "    ...init,",
    '    headers: { apikey: VD_KEY, "Content-Type": "application/json", ...(init.headers || {}) },',
    "  });",
    "  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || res.statusText);",
    "  if (res.status === 204 || res.headers.get(\"content-length\") === \"0\") return null;",
    "  const text = await res.text();",
    "  return text ? JSON.parse(text) : null;",
    "}",
    "",
  ];
  if (!exposed || exposed.policy.select) {
    lines.push(`// Read:   const rows = await vd(${jsString(`/${table}?order=id.desc&limit=20`)});`);
  }
  if (!exposed || exposed.policy.insert) {
    lines.push(`// Create: await vd(${jsString(`/${table}`)}, { method: "POST", body: JSON.stringify({ /* columns */ }) });`);
  }
  return lines.join("\n");
}

export function maskKey(key: string): string {
  return key.length <= 14 ? key : `${key.slice(0, 9)}…${key.slice(-4)}`;
}
