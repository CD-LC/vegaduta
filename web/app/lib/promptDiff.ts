// Line diff for the prompt history view: what a version changed against the one before it. Same idea as the
// server's PromptDiff (longest common subsequence over lines) but returns structured rows so the UI can colour them.

export type DiffRow = { type: "same" | "add" | "del"; text: string };

function lines(text: string | null | undefined): string[] {
  return text ? text.replace(/\r\n/g, "\n").split("\n") : [];
}

/** Lines above this product of the two sizes fall back to "all removed, all added" (a pathological paste). */
const MAX_CELLS = 4_000_000;

export function diffLines(before: string | null | undefined, after: string | null | undefined): DiffRow[] {
  const a = lines(before);
  const b = lines(after);
  if (a.length * b.length > MAX_CELLS) {
    return [...a.map((text): DiffRow => ({ type: "del", text })), ...b.map((text): DiffRow => ({ type: "add", text }))];
  }
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ type: "same", text: a[i] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ type: "del", text: a[i++] });
    } else {
      out.push({ type: "add", text: b[j++] });
    }
  }
  while (i < a.length) out.push({ type: "del", text: a[i++] });
  while (j < b.length) out.push({ type: "add", text: b[j++] });
  return out;
}

export function hasChanges(rows: DiffRow[]): boolean {
  return rows.some((r) => r.type !== "same");
}
