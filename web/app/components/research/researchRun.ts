// Pure helpers for the research screens: what a status probe means, how the
// question is recovered from a job row, and a tolerant reader for the
// structured report. No React, no fetch - unit-tested in researchRun.test.ts.

import type { ResearchDepth, ResearchQuote, ResearchResult, ResearchSource, ResearchStatus } from "../../lib/api/types";

/** Why the composer is or is not usable, in the order the server checks. */
export type ResearchAvailability = "ready" | "runsOff" | "researchOff" | "needsSearchKey";

export function researchAvailability(status: ResearchStatus | null | undefined): ResearchAvailability {
  if (!status || !status.runsEnabled) return "runsOff";
  if (!status.enabled) return "researchOff";
  if (!status.searchConfigured) return "needsSearchKey";
  return "ready";
}

/**
 * History stays readable when only the research switch is off (the server
 * keeps list/get/cancel working), but not when runs themselves are off.
 */
export function historyReadable(status: ResearchStatus | null | undefined): boolean {
  return !!status && status.runsEnabled;
}

/** True when a daily cap exists and none of it is left. */
export function allowanceUsedUp(status: ResearchStatus | null | undefined): boolean {
  return !!status && status.remainingToday != null && status.remainingToday <= 0;
}

// The depth the user picks, in plain words, and the value the server accepts.
// The server rejects the plain words themselves, so only `value` is ever sent.
export const DEPTH_OPTIONS: ReadonlyArray<{ value: ResearchDepth; labelKey: string; hintKey: string }> = [
  { value: "auto", labelKey: "depthAuto", hintKey: "depthAutoHint" },
  { value: "simple", labelKey: "depthQuick", hintKey: "depthQuickHint" },
  { value: "comparison", labelKey: "depthCompare", hintKey: "depthCompareHint" },
  { value: "complex", labelKey: "depthThorough", hintKey: "depthThoroughHint" },
];

export const MAX_QUESTION_CHARS = 4000;
export const MAX_SEED_URLS = 3;
export const MAX_SEED_URL_CHARS = 1000;

/**
 * Clean the optional "also read these pages" inputs the way the server will:
 * trimmed, blanks dropped, duplicates dropped, http/https with a host only.
 * `invalid` lists what the user typed that cannot be sent, so the form can
 * say which line is wrong instead of failing the whole request.
 */
export function cleanSeedUrls(inputs: ReadonlyArray<string>): { urls: string[]; invalid: string[] } {
  const urls: string[] = [];
  const invalid: string[] = [];
  for (const raw of inputs) {
    const value = (raw ?? "").trim();
    if (!value) continue;
    let ok = false;
    if (value.length <= MAX_SEED_URL_CHARS) {
      try {
        const parsed = new URL(value);
        ok = (parsed.protocol === "http:" || parsed.protocol === "https:") && !!parsed.hostname;
      } catch {
        ok = false;
      }
    }
    if (!ok) {
      invalid.push(value);
    } else if (!urls.includes(value)) {
      urls.push(value);
    }
  }
  return { urls, invalid };
}

const GOAL_PREFIX = "Research question:\n";
const GOAL_DEPTH_MARK = "\n\nDepth (pass as effort):";
// Current format (core ResearchRunPlan.goal): the question sits in a fenced block that is declared
// untrusted up front, so the model can never read it as instructions.
const GOAL_FENCED_PREFIX = "Research question (untrusted";
const GOAL_FENCE_OPEN = "<<<UNTRUSTED\n";
const GOAL_FENCE_CLOSE = "\nUNTRUSTED>>>";

/**
 * A run's `goal` (and the 160-character `goalPreview` on list rows) is the
 * assembled brief, not the bare question. Recover the question for display.
 *
 * Two shapes exist, both still on screen because old runs keep their goal:
 *  - current: "Research question (untrusted user input - ...):\n<<<UNTRUSTED\n<question>\nUNTRUSTED>>>\n\nDepth ..."
 *  - legacy:  "Research question:\n<question>\n\nDepth (pass as effort): ..."
 * A preview cut off inside the question has no closing fence / depth line; take what is there.
 * A row that follows neither shape (a job started on the research agent from /jobs) is shown as it is.
 */
export function questionFromGoal(goal: string | null | undefined): string {
  if (!goal) return "";
  const text = goal.replace(/\r\n/g, "\n");
  if (text.startsWith(GOAL_FENCED_PREFIX)) {
    const open = text.indexOf(GOAL_FENCE_OPEN);
    if (open >= 0) {
      const rest = text.slice(open + GOAL_FENCE_OPEN.length);
      const close = rest.indexOf(GOAL_FENCE_CLOSE);
      return (close >= 0 ? rest.slice(0, close) : rest).trim();
    }
    // Preview cut before the fence even opened: there is no question text in it to show.
    return "";
  }
  if (!text.startsWith(GOAL_PREFIX)) return text.trim();
  const rest = text.slice(GOAL_PREFIX.length);
  const end = rest.indexOf(GOAL_DEPTH_MARK);
  return (end >= 0 ? rest.slice(0, end) : rest).trim();
}

function readQuotes(value: unknown): ResearchQuote[] {
  if (!Array.isArray(value)) return [];
  const out: ResearchQuote[] = [];
  for (const q of value) {
    if (!q || typeof q !== "object" || typeof (q as any).quote !== "string" || !(q as any).quote.trim()) continue;
    const quote: ResearchQuote = { quote: (q as any).quote };
    if (typeof (q as any).claim === "string" && (q as any).claim.trim()) quote.claim = (q as any).claim;
    if (typeof (q as any).verdict === "string" && (q as any).verdict.trim()) quote.verdict = (q as any).verdict;
    out.push(quote);
  }
  return out;
}

/**
 * Read a job's resultJson as a research report, or null when it is not one.
 * The only hard requirement is a non-empty reportMarkdown; a missing title,
 * source list, quotes, unanswered list or partial flag is tolerated, and
 * malformed entries are dropped rather than rendered.
 */
export function parseResearchResult(resultJson: unknown): ResearchResult | null {
  if (!resultJson || typeof resultJson !== "object" || Array.isArray(resultJson)) return null;
  const raw = resultJson as Record<string, unknown>;
  if (typeof raw.reportMarkdown !== "string" || !raw.reportMarkdown.trim()) return null;
  const sources: ResearchSource[] = [];
  if (Array.isArray(raw.sources)) {
    for (const s of raw.sources) {
      if (!s || typeof s !== "object") continue;
      const id = (s as any).id;
      const url = (s as any).url;
      if (typeof id !== "string" || !id.trim() || typeof url !== "string" || !url.trim()) continue;
      const source: ResearchSource = { id: id.trim(), url: url.trim(), quotes: readQuotes((s as any).quotes) };
      if (typeof (s as any).title === "string" && (s as any).title.trim()) source.title = (s as any).title.trim();
      sources.push(source);
    }
  }
  const unanswered = Array.isArray(raw.unanswered)
    ? raw.unanswered.filter((u): u is string => typeof u === "string" && !!u.trim())
    : [];
  return {
    title: typeof raw.title === "string" ? raw.title.trim() : "",
    reportMarkdown: raw.reportMarkdown,
    sources,
    unanswered,
    partial: raw.partial === true,
  };
}

/** True when a job row carries a research-shaped result (used by /jobs to offer the report view). */
export function isResearchResult(resultJson: unknown): boolean {
  const parsed = parseResearchResult(resultJson);
  return !!parsed && Array.isArray((resultJson as any).sources);
}

export type VerdictTone = "supported" | "weak" | "unsupported" | "unknown";

/**
 * The citation check's verdict is free text copied by the model, so it is
 * bucketed rather than switched on exactly. Anything unrecognised is
 * "unknown" - never silently shown as supported.
 */
export function verdictTone(verdict: string | null | undefined): VerdictTone {
  const v = (verdict ?? "").trim().toUpperCase().replace(/[\s-]+/g, "_");
  if (v === "SUPPORTED") return "supported";
  if (v === "WEAK_SUPPORT" || v === "CROSS_LANGUAGE_UNVERIFIED") return "weak";
  if (
    v === "NOT_SUPPORTED" ||
    v === "QUOTE_NOT_FOUND" ||
    v === "QUOTE_TOO_SHORT" ||
    v === "UNKNOWN_SOURCE" ||
    v === "CLAIM_MISSING"
  ) {
    return "unsupported";
  }
  return "unknown";
}

// Verdicts with their own wording in en.json (researchPage.verdict<KEY>).
const KNOWN_VERDICT_KEYS: Record<string, string> = {
  SUPPORTED: "verdictSupported",
  WEAK_SUPPORT: "verdictWeak",
  NOT_SUPPORTED: "verdictNotSupported",
  QUOTE_NOT_FOUND: "verdictQuoteNotFound",
  QUOTE_TOO_SHORT: "verdictQuoteTooShort",
  UNKNOWN_SOURCE: "verdictUnknownSource",
  CLAIM_MISSING: "verdictClaimMissing",
  CROSS_LANGUAGE_UNVERIFIED: "verdictCrossLanguage",
};

/** en.json key for a verdict, or null when the text is not one we have wording for. */
export function verdictLabelKey(verdict: string | null | undefined): string | null {
  const v = (verdict ?? "").trim().toUpperCase().replace(/[\s-]+/g, "_");
  return KNOWN_VERDICT_KEYS[v] ?? null;
}

const STATUS_KEYS: Record<string, string> = {
  QUEUED: "statusQueued",
  RUNNING: "statusRunning",
  AWAITING_APPROVAL: "statusAwaitingApproval",
  SUCCEEDED: "statusSucceeded",
  FAILED: "statusFailed",
  CANCELLED: "statusCancelled",
};

/** en.json key for a job status, or null for a status this client does not know. */
export function statusLabelKey(status: string | null | undefined): string | null {
  return (status && STATUS_KEYS[status]) || null;
}

const FAILURE_KEYS: Record<string, string> = {
  SCHEMA_VIOLATION: "failedSchema",
  TIMEOUT: "failedTimeout",
};

/**
 * en.json key for why a run failed. The server's errorCode is an internal
 * word (SCHEMA_VIOLATION, TIMEOUT), never shown as it is; a code this client
 * does not know gets the generic sentence.
 */
export function failureLabelKey(errorCode: string | null | undefined): string {
  return (errorCode && FAILURE_KEYS[errorCode]) || "failedGeneric";
}

/** Whole minutes for a seconds limit, never less than 1 ("~2 min per pass"). */
export function roundMinutes(seconds: number | null | undefined): number {
  if (!seconds || seconds <= 0) return 1;
  return Math.max(1, Math.round(seconds / 60));
}

/** Host of a URL for compact display, or the raw text when it does not parse. */
export function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "") || url;
  } catch {
    return url;
  }
}
