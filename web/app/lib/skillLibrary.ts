// Pure helpers for the skill library page: line diff, safe-preview preparation,
// import-file validation. No React, no network, so they test without a DOM.

export type DiffKind = "same" | "add" | "del" | "change";

export interface DiffRow {
  kind: DiffKind;
  left: string | null;
  right: string | null;
}

/** Past this many lines on either side the LCS table is skipped (O(n*m) memory). */
export const MAX_DIFF_LINES = 1500;

function splitLines(text: string): string[] {
  if (text === "") return [];
  return text.replace(/\r\n?/g, "\n").split("\n");
}

/**
 * Side-by-side line diff. Classic LCS, then adjacent delete+add runs are paired
 * into "change" rows so an edited line shows on one row instead of two.
 * Inputs beyond MAX_DIFF_LINES fall back to a coarse "everything changed" diff
 * rather than freezing the tab.
 */
export function diffLines(before: string, after: string): DiffRow[] {
  const a = splitLines(before);
  const b = splitLines(after);
  if (a.length > MAX_DIFF_LINES || b.length > MAX_DIFF_LINES) {
    if (before === after) return a.map((l) => ({ kind: "same", left: l, right: l }));
    const n = Math.max(a.length, b.length);
    return Array.from({ length: n }, (_, i) => ({
      kind: "change" as const,
      left: i < a.length ? a[i] : null,
      right: i < b.length ? b[i] : null,
    }));
  }
  const n = a.length;
  const m = b.length;
  // lcs[i][j] = LCS length of a[i..] and b[j..]
  const lcs: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const raw: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      raw.push({ kind: "same", left: a[i], right: b[j] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      raw.push({ kind: "del", left: a[i++], right: null });
    } else {
      raw.push({ kind: "add", left: null, right: b[j++] });
    }
  }
  while (i < n) raw.push({ kind: "del", left: a[i++], right: null });
  while (j < m) raw.push({ kind: "add", left: null, right: b[j++] });

  // Pair each run of deletions with the additions that directly follow it.
  const rows: DiffRow[] = [];
  let k = 0;
  while (k < raw.length) {
    if (raw[k].kind !== "del") {
      rows.push(raw[k++]);
      continue;
    }
    const dels: DiffRow[] = [];
    while (k < raw.length && raw[k].kind === "del") dels.push(raw[k++]);
    const adds: DiffRow[] = [];
    while (k < raw.length && raw[k].kind === "add") adds.push(raw[k++]);
    const paired = Math.min(dels.length, adds.length);
    for (let p = 0; p < paired; p++) rows.push({ kind: "change", left: dels[p].left, right: adds[p].right });
    for (let p = paired; p < dels.length; p++) rows.push(dels[p]);
    for (let p = paired; p < adds.length; p++) rows.push(adds[p]);
  }
  return rows;
}

export function diffStats(rows: DiffRow[]): { added: number; removed: number; changed: number } {
  let added = 0;
  let removed = 0;
  let changed = 0;
  for (const r of rows) {
    if (r.kind === "add") added++;
    else if (r.kind === "del") removed++;
    else if (r.kind === "change") changed++;
  }
  return { added, removed, changed };
}

/**
 * Text handed to the markdown preview. A skill body can come from an untrusted
 * zip or from an agent, so the preview must not fetch anything: image syntax is
 * replaced by its alt text (an <img> would be a tracking beacon), and HTML
 * comments/tags are left as plain text because the renderer never builds markup
 * from them. Front matter is shown, not hidden, so a reviewer sees everything.
 */
export function previewMarkdown(body: string): string {
  return body.replace(/!\[([^\]]*)\]\([^)]*\)/g, (_m, alt: string) => (alt ? `[image: ${alt}]` : "[image]"));
}

export const MAX_IMPORT_BYTES = 10 * 1024 * 1024;

export type ImportCheck = { ok: true } | { ok: false; reason: "type" | "size" | "empty" };

/** Cheap client-side screen before upload; the server remains the authority. */
export function checkImportFile(file: { name: string; size: number }): ImportCheck {
  const name = file.name.toLowerCase();
  if (!(name.endsWith(".zip") || name.endsWith(".md"))) return { ok: false, reason: "type" };
  if (file.size <= 0) return { ok: false, reason: "empty" };
  if (file.size > MAX_IMPORT_BYTES) return { ok: false, reason: "size" };
  return { ok: true };
}

export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return "-";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export function exportFilename(name: string): string {
  const safe = name.replace(/[^A-Za-z0-9._-]+/g, "-").replace(/\.{2,}/g, ".").replace(/^[-.]+|[-.]+$/g, "");
  return `${safe || "skill"}.zip`;
}
