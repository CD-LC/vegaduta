// Local-code: a dependency-free unified-diff generator.
//
// Why hand-rolled instead of `diff`/`jsdiff` from npm: this module is part of
// the browser-local code assistant, whose whole premise is that nothing about
// the person's source leaves their machine. Every dependency added to that
// path is another package that could, in some future version, phone home from
// inside the bundle - and a line-level LCS is a table fill plus a backtrack,
// small enough to read in one sitting (lcsOps below is the whole algorithm).
// The cost of owning it is far below the cost of auditing someone else's.
//
// Scope, stated honestly: what comes out of here is a diff for a HUMAN to
// read before approving a write. It is deliberately NOT a patch you can feed
// to `git apply` - there is no rename/mode metadata and no `diff --git`
// header, because applyLocalEdits (edit.ts) never applies a patch: it writes
// the complete updated file it was given. Keeping the two facts apart is the
// point.
//
// The one piece of real unified-diff syntax this DOES carry is git's
// "\ No newline at end of file" marker, and it is here for a correctness
// reason rather than fidelity: splitLines maps "a\nb" and "a\nb\n" to the
// same two lines, so without the marker a change that is only the
// end-of-file newline renders as an empty diff while still being a real
// difference in the bytes edit.ts is about to write. An empty diff means "no
// changes needed" to every caller, so that silence would hide a write. See
// the eol handling in diffHunks.

/** One line of a hunk, already carrying its unified-diff prefix. */
export type DiffLinePrefix = " " | "-" | "+";

export interface DiffHunk {
  /** 1-based first line of the hunk in the original. 0 when the hunk is a
   * pure insertion (unified-diff convention: "insert after line 0"). */
  oldStart: number;
  oldCount: number;
  newStart: number;
  newCount: number;
  /** Prefixed lines, e.g. `" const x = 1"`, `"-old"`, `"+new"`. May also
   * contain NO_EOL_MARKER lines, which belong to the line above them and -
   * exactly as in git's own output - are NOT counted in oldCount/newCount. */
  lines: string[];
}

/** git's own wording, byte for byte, so a reviewer who has seen one diff in
 * their life recognises it. Rendered after the line it describes. */
export const NO_EOL_MARKER = "\\ No newline at end of file";

export interface DiffResult {
  hunks: DiffHunk[];
  added: number;
  removed: number;
  /** True when the file pair was too large for the exact LCS (see
   * MAX_LCS_CELLS) and the result degraded to one whole-file replacement
   * hunk. The diff is still CORRECT - applying it reproduces `updated` - it
   * is just uselessly coarse to read: every line of the original removed,
   * every line of the update added. The UI should say so rather than let
   * someone squint at that and call it review. Carried out through
   * unifiedDiffResult() and onto ProposedEdit.coarse; a flag the renderer
   * cannot see is a flag that may as well not exist. */
  coarse: boolean;
}

/** What unifiedDiffResult() hands back: the rendered text plus the facts the
 * string cannot carry. Added because `coarse` used to be computed here and
 * dropped on the floor by the string-only unifiedDiff(), leaving the UI no
 * way to warn that a diff was approximate. */
export interface RenderedDiff {
  /** The unified diff, or "" when the two sides are byte-identical. */
  text: string;
  coarse: boolean;
  added: number;
  removed: number;
}

/** Default lines of surrounding context per hunk - same default as `diff -u`
 * and git, so a reviewer reading this diff is reading a shape they know. */
export const DEFAULT_CONTEXT_LINES = 3;

/**
 * Ceiling on the LCS dynamic-programming table, in CELLS.
 *
 * What the guard actually bounds: lcsOps allocates one Uint32Array of
 * (n + 1) * (m + 1) cells, where n and m are the line counts AFTER diffHunks
 * has trimmed the common prefix and suffix - and diffHunks compares exactly
 * that product against this constant before calling it. So the cap bounds a
 * single flat allocation to MAX_LCS_CELLS 32-bit cells, and the work to the
 * same number of table writes. Both grow as the product, not the sum, which
 * is the whole reason there is a ceiling: two sides that each double turn one
 * table into four.
 *
 * For a sense of scale, in the terms the guard uses (working the inequality
 * backwards for the equal-length case): n = m fits while (n + 1)^2 does not
 * exceed 4,000,000, i.e. up to 1999 changed lines on each side - a boundary
 * edit.test.ts asserts on both sides, deriving it from this constant rather
 * than restating it, so the two cannot drift apart. Past the cap
 * the table is both slow AND a memory spike on the UI thread, and this runs
 * on the main thread with no worker - so we degrade loudly (coarse:true)
 * rather than freeze the tab.
 *
 * The trim is what keeps real edits clear of this: it strips every identical
 * leading and trailing line, so a single changed line leaves one line per
 * side no matter how long the file is. Reaching the cap takes a rewrite that
 * differs from the top of the file to the bottom.
 */
export const MAX_LCS_CELLS = 4_000_000;

/**
 * Split into lines WITHOUT inventing a trailing empty line. "a\nb\n" is two
 * lines; a naive split("\n") reports three and every hunk after it is off by
 * one. CRLF files keep their "\r" at end-of-line: the diff then compares
 * exactly what is on disk, which is the behaviour we want for a tool whose
 * next step is writing bytes back to that disk.
 */
export function splitLines(text: string): string[] {
  if (text === "") return [];
  const lines = text.split("\n");
  if (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
  return lines;
}

interface Op {
  type: DiffLinePrefix;
  text: string;
  /** This op carries the LAST line of the original, and the original had no
   * end-of-file newline: NO_EOL_MARKER is rendered after it. */
  noEolOld?: boolean;
  /** Same for the updated side. */
  noEolNew?: boolean;
}

/**
 * Longest-common-subsequence over lines, backtracked into an edit script.
 * This is just the quadratic core: it assumes nothing, checks nothing, and
 * allocates the full table for whatever it is handed. The prefix/suffix trim
 * and the MAX_LCS_CELLS check both live in diffHunks, which is its only
 * caller - it is module-private so that stays true. Any new caller owns both.
 */
function lcsOps(a: string[], b: string[]): Op[] {
  const n = a.length;
  const m = b.length;
  const width = m + 1;
  // Uint32Array over a nested JS array: one flat allocation, no per-row
  // object headers. Every value stored is an LCS length, which cannot exceed
  // min(n, m) - and MAX_LCS_CELLS already caps that product far below what a
  // 32-bit cell holds, so nothing here can saturate.
  const table = new Uint32Array((n + 1) * width);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      table[i * width + j] =
        a[i] === b[j]
          ? table[(i + 1) * width + (j + 1)] + 1
          : Math.max(table[(i + 1) * width + j], table[i * width + (j + 1)]);
    }
  }
  const ops: Op[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      ops.push({ type: " ", text: a[i] });
      i++;
      j++;
    } else if (table[(i + 1) * width + j] >= table[i * width + (j + 1)]) {
      // Tie goes to "removal first", which groups a changed line as -old/+new
      // (the order every reviewer expects) rather than +new/-old.
      ops.push({ type: "-", text: a[i] });
      i++;
    } else {
      ops.push({ type: "+", text: b[j] });
      j++;
    }
  }
  while (i < n) ops.push({ type: "-", text: a[i++] });
  while (j < m) ops.push({ type: "+", text: b[j++] });
  return ops;
}

/**
 * Whether NO_EOL_MARKER belongs directly after this op's line. A context line
 * is shared by both sides, so it only earns a marker when NEITHER side ends
 * with a newline - the mixed case is impossible here, because diffHunks
 * splits that final context line into a -/+ pair before we get this far.
 */
function needsNoEolMarker(op: Op): boolean {
  if (op.type === "-") return op.noEolOld === true;
  if (op.type === "+") return op.noEolNew === true;
  return op.noEolOld === true && op.noEolNew === true;
}

function buildHunks(ops: Op[], context: number): DiffHunk[] {
  const total = ops.length;
  const oldNo = new Array<number>(total);
  const newNo = new Array<number>(total);
  let o = 1;
  let n = 1;
  for (let i = 0; i < total; i++) {
    oldNo[i] = o;
    newNo[i] = n;
    if (ops[i].type !== "+") o++;
    if (ops[i].type !== "-") n++;
  }

  const changed: number[] = [];
  for (let i = 0; i < total; i++) if (ops[i].type !== " ") changed.push(i);
  if (changed.length === 0) return [];

  const hunks: DiffHunk[] = [];
  const flush = (firstChange: number, lastChange: number) => {
    const start = Math.max(0, firstChange - context);
    const end = Math.min(total - 1, lastChange + context);
    const lines: string[] = [];
    let oldCount = 0;
    let newCount = 0;
    for (let i = start; i <= end; i++) {
      lines.push(ops[i].type + ops[i].text);
      if (ops[i].type !== "+") oldCount++;
      if (ops[i].type !== "-") newCount++;
      // After the counts, never before: the marker is an annotation on the
      // preceding line, not a line of either file.
      if (needsNoEolMarker(ops[i])) lines.push(NO_EOL_MARKER);
    }
    hunks.push({
      // A hunk that consumes no lines on one side points at the line it sits
      // AFTER, not at a line it doesn't have - "@@ -0,0 +1,4 @@" for a new
      // file, "@@ -12,0 +13,2 @@" for an insertion after line 12.
      oldStart: oldCount === 0 ? oldNo[start] - 1 : oldNo[start],
      oldCount,
      newStart: newCount === 0 ? newNo[start] - 1 : newNo[start],
      newCount,
      lines,
    });
  };

  let groupFirst = changed[0];
  let groupLast = changed[0];
  for (let k = 1; k < changed.length; k++) {
    // Merge two change runs when their context windows would touch or
    // overlap; otherwise the reader gets the same lines printed twice in
    // adjacent hunks.
    if (changed[k] - groupLast <= context * 2) {
      groupLast = changed[k];
      continue;
    }
    flush(groupFirst, groupLast);
    groupFirst = changed[k];
    groupLast = changed[k];
  }
  flush(groupFirst, groupLast);
  return hunks;
}

/**
 * Structured diff between two file contents. Trims the common prefix and
 * suffix before running the quadratic LCS, so the table is sized by what
 * CHANGED rather than by the file - which is both why a one-line edit in a
 * large file costs almost nothing and why MAX_LCS_CELLS is measured on the
 * trimmed lengths rather than on the originals.
 */
export function diffHunks(
  original: string,
  updated: string,
  context: number = DEFAULT_CONTEXT_LINES
): DiffResult {
  const a = splitLines(original);
  const b = splitLines(updated);

  let prefix = 0;
  while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++;
  let suffix = 0;
  while (
    suffix < a.length - prefix &&
    suffix < b.length - prefix &&
    a[a.length - 1 - suffix] === b[b.length - 1 - suffix]
  ) {
    suffix++;
  }

  const midA = a.slice(prefix, a.length - suffix);
  const midB = b.slice(prefix, b.length - suffix);

  let coarse = false;
  let midOps: Op[];
  if ((midA.length + 1) * (midB.length + 1) > MAX_LCS_CELLS) {
    coarse = true;
    midOps = [
      ...midA.map((text): Op => ({ type: "-", text })),
      ...midB.map((text): Op => ({ type: "+", text })),
    ];
  } else {
    midOps = lcsOps(midA, midB);
  }

  const ops: Op[] = [
    ...a.slice(0, prefix).map((text): Op => ({ type: " ", text })),
    ...midOps,
    ...a.slice(a.length - suffix).map((text): Op => ({ type: " ", text })),
  ];

  // End-of-file newline. splitLines deliberately drops it, so it is invisible
  // in `ops` - but edit.ts writes bytes, and "a\nb" -> "a\nb\n" is a real
  // write that would otherwise render as no diff at all. An empty string has
  // no last line to annotate, so it counts as "has one" and gets no marker.
  const oldHasEol = original === "" || original.endsWith("\n");
  const newHasEol = updated === "" || updated.endsWith("\n");
  if (oldHasEol !== newHasEol && ops.length > 0) {
    const last = ops[ops.length - 1];
    // The final line is byte-different between the two sides even when its
    // text matches, so showing it as shared context would be a lie. Split it
    // into -old/+new, which is both what git does and what gives the two
    // markers somewhere to attach. Done before the counts below, so a
    // newline-only change honestly reports 1 added / 1 removed.
    if (last.type === " ") {
      ops.pop();
      ops.push({ type: "-", text: last.text }, { type: "+", text: last.text });
    }
  }
  if (!oldHasEol) {
    for (let i = ops.length - 1; i >= 0; i--) {
      if (ops[i].type !== "+") {
        ops[i].noEolOld = true;
        break;
      }
    }
  }
  if (!newHasEol) {
    for (let i = ops.length - 1; i >= 0; i--) {
      if (ops[i].type !== "-") {
        ops[i].noEolNew = true;
        break;
      }
    }
  }

  let added = 0;
  let removed = 0;
  for (const op of ops) {
    if (op.type === "+") added++;
    else if (op.type === "-") removed++;
  }

  return { hunks: buildHunks(ops, context), added, removed, coarse };
}

/**
 * Render a unified diff AND report what the string cannot say for itself.
 * `text` is "" - not a header-only stub - exactly when the two sides are
 * byte-identical, so callers can keep treating empty-string as "nothing to
 * review" without parsing anything. Byte-identical is the honest test now:
 * a difference of only the end-of-file newline produces a real hunk with
 * NO_EOL_MARKER rather than silence.
 */
export function unifiedDiffResult(
  original: string,
  updated: string,
  path: string,
  context: number = DEFAULT_CONTEXT_LINES
): RenderedDiff {
  const result = diffHunks(original, updated, context);
  if (result.hunks.length === 0) {
    return { text: "", coarse: result.coarse, added: result.added, removed: result.removed };
  }
  const safePath = path && path.trim() ? path.trim() : "file";
  const out: string[] = [`--- a/${safePath}`, `+++ b/${safePath}`];
  for (const hunk of result.hunks) {
    // Always the explicit ",count" form, including ",1". git omits it for
    // single-line ranges; keeping it unconditional makes the header trivially
    // parseable and the tests unambiguous, and every unified-diff reader
    // accepts it.
    out.push(`@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@`);
    for (const line of hunk.lines) out.push(line);
  }
  return {
    text: out.join("\n") + "\n",
    coarse: result.coarse,
    added: result.added,
    removed: result.removed,
  };
}

/** Text-only convenience over unifiedDiffResult(). Anything that must tell
 * the person the diff was approximate needs unifiedDiffResult() instead -
 * that fact does not survive the string. */
export function unifiedDiff(
  original: string,
  updated: string,
  path: string,
  context: number = DEFAULT_CONTEXT_LINES
): string {
  return unifiedDiffResult(original, updated, path, context).text;
}
