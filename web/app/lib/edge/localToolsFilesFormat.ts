// Pure formatting and argument helpers for the local folder tools (split out of
// localToolsFiles.ts). No I/O: every function here maps values to text.

import type { LocalWalkSummary } from "../localcode/types";

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/** Decimal KB/MB, matching how fsAccess states its own 512 KB limit. */
export function formatBytes(bytes: number): string {
  if (typeof bytes !== "number" || !Number.isFinite(bytes) || bytes < 0) return "? B";
  if (bytes < 1000) return `${Math.round(bytes)} B`;
  if (bytes < 1000 * 1000) return `${(bytes / 1000).toFixed(1)} KB`;
  return `${(bytes / (1000 * 1000)).toFixed(1)} MB`;
}

export function formatAge(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000));
  if (seconds < 60) return `${seconds}s`;
  return `${Math.round(seconds / 60)}m`;
}

/**
 * Normalise a caller-supplied path to fsAccess's own rule, or null if it tries
 * to leave the opened folder.
 *
 * This MIRRORS fsAccess's private pathSegments(): split on / and \, drop empty
 * and ".", refuse "..". It is not a second security boundary and must not be
 * read as one — fsAccess enforces the same rule on every read and is what
 * actually confines the handle. The mirror exists for one reason: fsAccess's
 * readLocalFile answers a traversal path with "", which is indistinguishable
 * from an empty file, so refusing here is the only way the model learns that
 * the path was the problem instead of reporting the file as blank.
 */
export function normalizeRepoPath(raw: string): string | null {
  const parts = raw.split(/[\\/]+/);
  const segments: string[] = [];
  for (const part of parts) {
    if (part === "" || part === ".") continue;
    if (part === "..") return null;
    segments.push(part);
  }
  return segments.length > 0 ? segments.join("/") : null;
}

/** A required string argument, or a sentence explaining what was wrong with it.
 *  Returns the sentence rather than throwing — see "refusals are returned". */
export function readStringArg(
  args: Record<string, unknown>,
  key: string,
  maxChars: number
): { ok: true; value: string } | { ok: false; detail: string } {
  const raw = args[key];
  if (typeof raw !== "string") {
    return { ok: false, detail: `"${key}" must be a string (got ${raw === null ? "null" : typeof raw}).` };
  }
  if (raw.length > maxChars) {
    return { ok: false, detail: `"${key}" is ${raw.length} characters; this tool accepts at most ${maxChars}.` };
  }
  const trimmed = raw.trim();
  if (!trimmed) return { ok: false, detail: `"${key}" is empty.` };
  return { ok: true, value: trimmed };
}

/** A positive-integer argument with a default and a ceiling. An out-of-range
 *  value is clamped rather than refused: the person asked a question, and
 *  answering it with five results instead of fifty is a better outcome than a
 *  lecture about the argument. */
export function readCountArg(args: Record<string, unknown>, key: string, fallback: number, max: number): number {
  const raw = args[key];
  if (typeof raw !== "number" || !Number.isFinite(raw)) return fallback;
  const value = Math.floor(raw);
  if (value < 1) return 1;
  if (value > max) return max;
  return value;
}

/**
 * Join lines until `budget` characters, and report how many were kept.
 * `kept < lines.length` is what the caller turns into an explicit "showing N of
 * M" line — a listing cut without that line is the one failure mode this whole
 * module is written against.
 *
 * `clipped` covers the case that would otherwise defeat the whole budget: a
 * single line longer than the budget (a minified bundle is one line). Keeping
 * it whole would push this module's own "showing lines 1-40 of 900" footer past
 * runLocalTool's 2000-char cut, so the footer — the part that says the answer
 * is partial — would be the part thrown away. It is clipped instead, and the
 * caller says so.
 */
export function joinWithinBudget(lines: string[], budget: number): { text: string; kept: number; clipped: boolean } {
  const out: string[] = [];
  let used = 0;
  let clipped = false;
  for (const line of lines) {
    const cost = line.length + 1;
    if (used + cost > budget) {
      if (out.length > 0) break;
      out.push(`${line.slice(0, Math.max(1, budget - 1))}…`);
      clipped = true;
      break;
    }
    out.push(line);
    used += cost;
  }
  return { text: out.join("\n"), kept: out.length, clipped };
}

/** The skip counters, rendered only when something was actually skipped. A
 *  line of zeroes on every listing trains a reader to stop looking at it, which
 *  is exactly when it matters.
 *
 *  The "512 KB" here, and the "5000-file cap" in the INCOMPLETE line below, are
 *  fsAccess's MAX_LOCAL_FILE_BYTES and MAX_LOCAL_FILES, read from that file on
 *  2026-09-12 and COPIED as prose rather than imported: a static import of
 *  either would pull fsAccess into the bundle for every tier-1 turn, which is
 *  exactly what the dynamic import above avoids. They are numbers in a sentence
 *  shown to a person, not values anything computes with, so a drift would
 *  mislabel a skip — never mis-skip a file. */
export function skipLine(summary: LocalWalkSummary): string | null {
  const parts: string[] = [];
  if (summary.skippedLarge > 0) parts.push(`${summary.skippedLarge} over the 512 KB size limit`);
  if (summary.skippedBinary > 0) parts.push(`${summary.skippedBinary} binary`);
  if (summary.skippedUnknownType > 0)
    parts.push(`${summary.skippedUnknownType} of a file type this index does not cover`);
  if (summary.unreadable > 0) parts.push(`${summary.unreadable} this browser could not read`);
  if (parts.length === 0) return null;
  return `[not listed: ${parts.join(", ")}]`;
}

/** One line of context per hit — the first non-blank line of the chunk, clipped.
 *  A citation with no text at all makes the model guess what it found; a full
 *  chunk per hit would leave room for one hit. */
export function firstMeaningfulLine(text: string): string {
  if (typeof text !== "string") return "";
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (trimmed) return trimmed.length > 110 ? `${trimmed.slice(0, 110)}…` : trimmed;
  }
  return "";
}
