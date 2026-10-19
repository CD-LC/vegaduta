// agentkit — file-slice retrieval: send the relevant ranges, not whole files.
//
// A 600-line file is ~6k tokens; the whole prompt budget of a 4k-window model
// is ~2.7k. So "read_file" can never mean "put the file in the prompt". It
// means: put the part that matters in the prompt, say exactly which part that
// was, and tell the model how to ask for another part.
//
// Which part matters, in order of preference:
//   1. the range the model asked for (start_line / end_line);
//   2. the lines a recent search_text matched in this file, each with a little
//      context, merged where they overlap;
//   3. the head of the file.
// Every result is line-numbered and hard-capped per file (budget.ts
// perFileCapTokens). Pure; no I/O.

import { estimateTokens } from "./tokens";
import type { FileSlice } from "./types";

/** Lines of context kept either side of a search hit. */
export const HIT_CONTEXT_LINES = 6;
/** At most this many hit lines per file are remembered / expanded. */
export const MAX_HITS_PER_FILE = 12;

const GAP_MARKER = "  ⋮";

export interface LineRange {
  start: number; // 1-based inclusive
  end: number; // 1-based inclusive
}

/** Windows around each hit line, clamped to the file and merged when they touch
 *  or overlap — two hits four lines apart are one excerpt, not two with the
 *  same lines repeated. */
export function mergeHitRanges(hitLines: number[], totalLines: number, context: number = HIT_CONTEXT_LINES): LineRange[] {
  const valid = hitLines
    .filter((n) => Number.isFinite(n) && n >= 1 && n <= totalLines)
    .map((n) => Math.trunc(n))
    .sort((a, b) => a - b);
  const merged: LineRange[] = [];
  for (const line of valid) {
    const start = Math.max(1, line - context);
    const end = Math.min(totalLines, line + context);
    const last = merged[merged.length - 1];
    if (last && start <= last.end + 1) last.end = Math.max(last.end, end);
    else merged.push({ start, end });
  }
  return merged;
}

function numbered(lines: string[], range: LineRange): string[] {
  const out: string[] = [];
  for (let n = range.start; n <= range.end; n += 1) out.push(`${n}\t${lines[n - 1] ?? ""}`);
  return out;
}

export interface SliceRequest {
  path: string;
  contents: string;
  capTokens: number;
  step: number;
  startLine?: number | null;
  endLine?: number | null;
  hitLines?: number[];
}

/**
 * Build the excerpt of one file that goes into the working set.
 *
 * Lines are added whole and in order until the cap is reached — never a line
 * cut in half, which would hand the model a syntax error that is not in the
 * file. `ranges` on the result lists the line ranges that are REALLY in
 * `text` (cut where the cap cut them), and describeSlice() builds the header
 * the model sees from those — "lines 40-52, 120-133 of 610" for an excerpt
 * with a gap, never one span that covers lines the model was not shown.
 * `startLine`/`endLine` are the outer bounds only.
 */
export function sliceFile(request: SliceRequest): FileSlice {
  const lines = request.contents.split(/\r?\n/);
  // A trailing newline yields one empty final element that is not a line.
  if (lines.length > 1 && lines[lines.length - 1] === "") lines.pop();
  const totalLines = lines.length;
  const cap = Math.max(1, request.capTokens);

  let ranges: LineRange[];
  const askedStart = request.startLine ?? null;
  const askedEnd = request.endLine ?? null;
  if (askedStart !== null || askedEnd !== null) {
    const start = Math.min(Math.max(1, Math.trunc(askedStart ?? 1)), Math.max(1, totalLines));
    const end = Math.min(Math.max(start, Math.trunc(askedEnd ?? totalLines)), Math.max(1, totalLines));
    ranges = [{ start, end }];
  } else {
    const whole: LineRange = { start: 1, end: Math.max(1, totalLines) };
    const wholeFits = estimateTokens(numbered(lines, whole).join("\n")) <= cap;
    const hits = (request.hitLines || []).slice(0, MAX_HITS_PER_FILE);
    ranges = !wholeFits && hits.length > 0 ? mergeHitRanges(hits, totalLines) : [whole];
    if (ranges.length === 0) ranges = [whole];
  }

  const out: string[] = [];
  let used = 0;
  let first = 0;
  let last = 0;
  let full = false;
  const emitted: LineRange[] = [];
  for (let r = 0; r < ranges.length && !full; r += 1) {
    const rows = numbered(lines, ranges[r]);
    let gapPending = r > 0;
    for (let i = 0; i < rows.length; i += 1) {
      const cost = estimateTokens(rows[i] + "\n") + (gapPending ? estimateTokens(GAP_MARKER + "\n") : 0);
      if (used + cost > cap) {
        full = true;
        break;
      }
      if (gapPending) {
        out.push(GAP_MARKER);
        gapPending = false;
      }
      out.push(rows[i]);
      used += cost;
      const lineNo = ranges[r].start + i;
      if (first === 0) first = lineNo;
      last = lineNo;
      const open = emitted[emitted.length - 1];
      if (open && i > 0) open.end = lineNo;
      else emitted.push({ start: lineNo, end: lineNo });
    }
  }

  const slice: FileSlice = {
    path: request.path,
    startLine: first,
    endLine: last,
    totalLines,
    text: out.join("\n"),
    touchedStep: request.step,
  };
  if (emitted.length > 0) slice.ranges = emitted;
  return slice;
}

/** "40-52, 120-133" — the ranges really in the excerpt. Falls back to the outer
 *  bounds for a slice saved before ranges were recorded. */
export function describeRanges(slice: Pick<FileSlice, "startLine" | "endLine" | "ranges">): string {
  const ranges = slice.ranges && slice.ranges.length > 0 ? slice.ranges : [{ start: slice.startLine, end: slice.endLine }];
  return ranges.map((r) => `${r.start}-${r.end}`).join(", ");
}

/** The header line the model sees above an excerpt. Says what is NOT there, so
 *  the model knows to ask rather than assume the file ends at the cut. */
export function describeSlice(slice: FileSlice): string {
  if (slice.startLine === 0) {
    return `${slice.path} (${slice.totalLines} lines) — nothing fit the context window; re-read a narrow range with start_line/end_line.`;
  }
  const gaps = (slice.ranges?.length ?? 1) > 1;
  const partial = gaps || slice.startLine > 1 || slice.endLine < slice.totalLines;
  return (
    `${slice.path} — lines ${describeRanges(slice)} of ${slice.totalLines}` +
    (partial
      ? gaps
        ? " (partial: lines between the ranges are NOT shown; read_file with start_line/end_line for other ranges)"
        : " (partial: read_file with start_line/end_line for other ranges)"
      : "")
  );
}
