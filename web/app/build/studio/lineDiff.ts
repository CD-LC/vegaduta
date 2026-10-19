// Line diff between two versions of an app, for the History "compare" view.
// Classic LCS on lines, after trimming the common head and tail so the usual
// small edit to a big file stays cheap. Pure; no DOM.

export type DiffKind = "same" | "add" | "del";
export interface DiffLine {
  kind: DiffKind;
  text: string;
}

/** Above this many cells the LCS table is skipped and the changed middle is
 * shown as one removed block + one added block (still correct, less minimal). */
const MAX_CELLS = 4_000_000;

export function lineDiff(a: string, b: string): DiffLine[] {
  const A = a.split(/\r?\n/);
  const B = b.split(/\r?\n/);
  let head = 0;
  while (head < A.length && head < B.length && A[head] === B[head]) head++;
  let tail = 0;
  while (tail < A.length - head && tail < B.length - head && A[A.length - 1 - tail] === B[B.length - 1 - tail]) tail++;
  const midA = A.slice(head, A.length - tail);
  const midB = B.slice(head, B.length - tail);

  const out: DiffLine[] = A.slice(0, head).map((text) => ({ kind: "same" as const, text }));
  const n = midA.length;
  const m = midB.length;
  if (n === 0 || m === 0 || n * m > MAX_CELLS) {
    for (const text of midA) out.push({ kind: "del", text });
    for (const text of midB) out.push({ kind: "add", text });
  } else {
    // lcs[i][j] = LCS length of midA[i..] and midB[j..]
    const w = m + 1;
    const lcs = new Uint32Array((n + 1) * w);
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        lcs[i * w + j] = midA[i] === midB[j] ? lcs[(i + 1) * w + j + 1] + 1 : Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
      }
    }
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
      if (midA[i] === midB[j]) {
        out.push({ kind: "same", text: midA[i] });
        i++;
        j++;
      } else if (lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) {
        out.push({ kind: "del", text: midA[i++] });
      } else {
        out.push({ kind: "add", text: midB[j++] });
      }
    }
    while (i < n) out.push({ kind: "del", text: midA[i++] });
    while (j < m) out.push({ kind: "add", text: midB[j++] });
  }
  for (const text of A.slice(A.length - tail)) out.push({ kind: "same", text });
  return out;
}

export function diffStats(lines: DiffLine[]): { added: number; removed: number } {
  let added = 0;
  let removed = 0;
  for (const l of lines) {
    if (l.kind === "add") added++;
    else if (l.kind === "del") removed++;
  }
  return { added, removed };
}

export type DiffRow = DiffLine | { kind: "gap"; hidden: number };

/** Keep `context` unchanged lines around each change; fold the rest into gaps. */
export function collapseContext(lines: DiffLine[], context = 3): DiffRow[] {
  const keep = new Array<boolean>(lines.length).fill(false);
  lines.forEach((l, i) => {
    if (l.kind === "same") return;
    for (let k = Math.max(0, i - context); k <= Math.min(lines.length - 1, i + context); k++) keep[k] = true;
  });
  const rows: DiffRow[] = [];
  let hidden = 0;
  lines.forEach((l, i) => {
    if (keep[i]) {
      if (hidden) rows.push({ kind: "gap", hidden });
      hidden = 0;
      rows.push(l);
    } else hidden++;
  });
  if (hidden) rows.push({ kind: "gap", hidden });
  return rows;
}
