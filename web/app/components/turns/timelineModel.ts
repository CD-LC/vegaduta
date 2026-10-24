import type { TurnJournalEvent } from "../../lib/api/turns";
import { epochMillis, parseSummary } from "./journalChain";

// View model of the turn timeline. The journal (ADR-0020) stores hash-chained events with a short structural
// "key=value" summary; this file turns each into what a person wants to read: what happened (a category and an
// i18n title), whether it was a problem (severity), how long it took, and any token / cost figures the summary
// carries. Pure functions: no React, no API client, so every rule below is testable on its own.
//
// What the journal records (core TurnRecorder): INTAKE, GUARD (guard=<name> outcome=<x>), MODEL_CALL
// (provider/model/route), TOOL_CALL (tool, status, resultSha, ms; the arguments only as payloadHash), APPROVAL
// (tool, decision, request), VERIFY (check, passed) and COMMIT (status). It never stores raw arguments, results or
// prompts, so the timeline shows hashes and statuses, never content. Tokens and cost are shown only when a summary
// carries tokensIn / tokensOut / costMinor; otherwise they are "not recorded", never zero.

export type TimelineCategory =
  | "intake"
  | "model"
  | "tool"
  | "approval"
  | "continuation"
  | "retry"
  | "guardrail"
  | "cache"
  | "verify"
  | "finish";

export const TIMELINE_CATEGORIES: TimelineCategory[] = [
  "intake",
  "model",
  "tool",
  "approval",
  "continuation",
  "retry",
  "guardrail",
  "cache",
  "verify",
  "finish",
];

/** ok = fine, info = neutral, warn = held / refused / retried, error = failed. */
export type Severity = "ok" | "info" | "warn" | "error";

export type TitleValues = Record<string, string | number>;

export interface TimelineEntry {
  seq: number;
  event: TurnJournalEvent;
  category: TimelineCategory;
  severity: Severity;
  /** Key under messages namespace "turnInspector". */
  titleKey: string;
  titleValues: TitleValues;
  /** Key of the one-line reason (retries, failover, refusals) or null. */
  reasonKey: string | null;
  /** Ms since the first event of the turn; null when a timestamp cannot be read. */
  offsetMs: number | null;
  /** Ms since the previous event; null for the first. */
  gapMs: number | null;
  /** Ms the event itself reports (tools report ms=); null otherwise. */
  durationMs: number | null;
  tokensIn: number | null;
  tokensOut: number | null;
  costMinor: number | null;
  /** The parsed summary, for the detail rows. */
  fields: Record<string, string>;
}

function finite(raw: string | undefined): number | null {
  if (raw === undefined || raw === "") return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

function firstFinite(fields: Record<string, string>, names: string[]): number | null {
  for (const name of names) {
    const n = finite(fields[name]);
    if (n !== null) return n;
  }
  return null;
}

/** Routes the recorder is known to write for a MODEL_CALL that is a retry or failover, mapped to a reason key. */
const ROUTE_REASON: Record<string, string> = {
  fallback: "reasonFallbackProvider",
  "retry-tools-disabled": "reasonToolsDisabledRetry",
};

const APPROVAL_OK_OUTCOMES = new Set(["completed", "approved"]);

const REFUSED_OUTCOMES = new Set(["refused", "withheld", "blocked", "denied", "rejected"]);

function isProblemStatus(status: string): boolean {
  return status.startsWith("error") || status === "failed";
}

function classify(e: TurnJournalEvent, f: Record<string, string>): Pick<TimelineEntry, "category" | "severity" | "titleKey" | "titleValues" | "reasonKey"> {
  const type = String(e.type);
  switch (type) {
    case "INTAKE":
      return {
        category: "intake",
        severity: "info",
        titleKey: "tlIntake",
        titleValues: { chars: f.messageChars ?? "?", tools: f.tools ?? "0", attachments: f.attachments ?? "0" },
        reasonKey: null,
      };
    case "MODEL_CALL": {
      const route = f.route ?? "";
      const values: TitleValues = { provider: f.provider ?? "?", model: f.model ?? "?", route: route || "?" };
      if (route.includes("cache")) {
        return { category: "cache", severity: "ok", titleKey: "tlCacheHit", titleValues: values, reasonKey: null };
      }
      if (route === "fallback" || route.startsWith("retry")) {
        return {
          category: "retry",
          severity: "warn",
          titleKey: route === "fallback" ? "tlFailover" : "tlRetryModel",
          titleValues: values,
          reasonKey: ROUTE_REASON[route] ?? "reasonRetryOther",
        };
      }
      return { category: "model", severity: "ok", titleKey: "tlModelCall", titleValues: values, reasonKey: null };
    }
    case "TOOL_CALL": {
      const status = f.status ?? "ok";
      const severity: Severity = status === "ok" ? "ok" : status === "error" ? "error" : "warn";
      const reasonKey = status === "blocked" ? "reasonToolBlocked" : status === "parked" ? "reasonToolParked" : null;
      return {
        category: "tool",
        severity,
        titleKey: "tlToolCall",
        titleValues: { tool: f.tool ?? "?", status },
        reasonKey,
      };
    }
    case "APPROVAL": {
      const decision = f.decision ?? "";
      // A call the platform let through without a person because it is read-only under the Supervised tier
      // (decision=auto-allowed reason=read-only-under-SUPERVISED). Not a decision to review as a problem.
      if (decision === "auto-allowed") {
        return {
          category: "approval",
          severity: "info",
          titleKey: "tlApprovalAutoAllowed",
          titleValues: { tool: f.tool ?? "?" },
          reasonKey: null,
        };
      }
      const pending = decision === "pending";
      const outcome = decision.replace(/^decided:/, "");
      // The recorder writes "pending" when a call is held and "decided:<completed|failed|denied>" when the person's
      // answer is applied. Only a completed (approved and ran) decision is fine; a denial, a failed replay or any
      // outcome this code does not know is shown as needing attention, never as success.
      const succeeded = !pending && APPROVAL_OK_OUTCOMES.has(outcome);
      return {
        category: "approval",
        severity: succeeded ? "ok" : "warn",
        titleKey: pending ? "tlApprovalRequested" : "tlApprovalDecided",
        titleValues: { tool: f.tool ?? "?", decision: pending ? decision : decision.replace(/^decided:/, "") },
        reasonKey: null,
      };
    }
    case "GUARD": {
      const guard = f.guard ?? "?";
      const outcome = f.outcome ?? "?";
      const values: TitleValues = { guard, outcome };
      if (guard === "continuation" || guard === "finish-continuation") {
        return { category: "continuation", severity: "info", titleKey: "tlContinuation", titleValues: values, reasonKey: guard === "finish-continuation" ? "reasonCutOff" : "reasonToolsContinue" };
      }
      if (guard === "finish-tool-call-retry") {
        return { category: "retry", severity: "warn", titleKey: "tlRetry", titleValues: values, reasonKey: "reasonToolCallRetry" };
      }
      if (guard.includes("cache") || outcome.includes("cache")) {
        return { category: "cache", severity: "ok", titleKey: "tlCacheHit", titleValues: values, reasonKey: null };
      }
      return {
        category: "guardrail",
        severity: REFUSED_OUTCOMES.has(outcome) ? "warn" : "ok",
        titleKey: "tlGuard",
        titleValues: values,
        reasonKey: REFUSED_OUTCOMES.has(outcome) ? "reasonGuardAction" : null,
      };
    }
    case "VERIFY": {
      const passed = f.passed !== "false";
      return {
        category: "verify",
        severity: passed ? "ok" : "warn",
        titleKey: passed ? "tlVerifyPassed" : "tlVerifyFailed",
        titleValues: { check: f.check ?? "?" },
        reasonKey: null,
      };
    }
    case "COMMIT": {
      const status = f.status ?? "?";
      const severity: Severity = status === "completed" ? "ok" : isProblemStatus(status) ? "error" : "warn";
      return { category: "finish", severity, titleKey: "tlFinish", titleValues: { status, chars: f.answerChars ?? "0" }, reasonKey: null };
    }
    default:
      return { category: "guardrail", severity: "info", titleKey: "tlOther", titleValues: { type }, reasonKey: null };
  }
}

export function buildTimeline(events: TurnJournalEvent[]): TimelineEntry[] {
  const first = events.length ? epochMillis(events[0].createdAt) : NaN;
  return events.map((event, i) => {
    const fields = parseSummary(event.summary);
    const at = epochMillis(event.createdAt);
    const prev = i > 0 ? epochMillis(events[i - 1].createdAt) : NaN;
    return {
      seq: event.seq,
      event,
      ...classify(event, fields),
      offsetMs: Number.isFinite(at) && Number.isFinite(first) ? at - first : null,
      gapMs: i > 0 && Number.isFinite(at) && Number.isFinite(prev) ? at - prev : null,
      durationMs: String(event.type) === "TOOL_CALL" ? finite(fields.ms) : null,
      tokensIn: firstFinite(fields, ["tokensIn", "inputTokens"]),
      tokensOut: firstFinite(fields, ["tokensOut", "outputTokens"]),
      costMinor: finite(fields.costMinor),
      fields,
    };
  });
}

export interface TimelineFilter {
  /** Empty set = every category. */
  categories: ReadonlySet<TimelineCategory>;
  /** Only entries whose severity is warn or error. */
  problemsOnly: boolean;
  /** Case-insensitive match on the raw summary; blank = no text filter. */
  text: string;
}

export const NO_FILTER: TimelineFilter = { categories: new Set(), problemsOnly: false, text: "" };

export function filterTimeline(entries: TimelineEntry[], filter: TimelineFilter): TimelineEntry[] {
  const needle = filter.text.trim().toLowerCase();
  return entries.filter((e) => {
    if (filter.categories.size > 0 && !filter.categories.has(e.category)) return false;
    if (filter.problemsOnly && e.severity !== "warn" && e.severity !== "error") return false;
    if (needle && !`${e.event.type} ${e.event.summary}`.toLowerCase().includes(needle)) return false;
    return true;
  });
}

export function categoryCounts(entries: TimelineEntry[]): Record<TimelineCategory, number> {
  const out = Object.fromEntries(TIMELINE_CATEGORIES.map((c) => [c, 0])) as Record<TimelineCategory, number>;
  for (const e of entries) out[e.category]++;
  return out;
}

export interface TimelineStats {
  events: number;
  wallMs: number | null;
  modelCalls: number;
  toolCalls: number;
  toolMs: number;
  retries: number;
  approvals: number;
  continuations: number;
  problems: number;
  /** null = no event carried the figure ("not recorded"), never 0. */
  tokensIn: number | null;
  tokensOut: number | null;
  costMinor: number | null;
  /** Status of the last COMMIT, or null while the turn has not committed. */
  finishStatus: string | null;
}

function sumOrNull(values: Array<number | null>): number | null {
  const present = values.filter((v): v is number => v !== null);
  return present.length ? present.reduce((a, b) => a + b, 0) : null;
}

export function timelineStats(entries: TimelineEntry[]): TimelineStats {
  const offsets = entries.map((e) => e.offsetMs).filter((n): n is number => n !== null);
  const commits = entries.filter((e) => e.category === "finish");
  return {
    events: entries.length,
    wallMs: offsets.length > 1 ? Math.max(...offsets) - Math.min(...offsets) : null,
    modelCalls: entries.filter((e) => e.event.type === "MODEL_CALL").length,
    toolCalls: entries.filter((e) => e.category === "tool").length,
    toolMs: entries.reduce((sum, e) => sum + (e.durationMs ?? 0), 0),
    retries: entries.filter((e) => e.category === "retry").length,
    approvals: entries.filter((e) => e.category === "approval").length,
    continuations: entries.filter((e) => e.category === "continuation").length,
    problems: entries.filter((e) => e.severity === "warn" || e.severity === "error").length,
    tokensIn: sumOrNull(entries.map((e) => e.tokensIn)),
    tokensOut: sumOrNull(entries.map((e) => e.tokensOut)),
    costMinor: sumOrNull(entries.map((e) => e.costMinor)),
    finishStatus: commits.length ? (commits[commits.length - 1].fields.status ?? null) : null,
  };
}

/**
 * A timestamp in the viewer's locale and time zone, with the zone named so it is never ambiguous; the raw value when
 * it cannot be read as a date.
 */
export function formatStamp(raw: string | number, locale: string): string {
  const ms = epochMillis(raw);
  if (!Number.isFinite(ms)) return String(raw);
  try {
    return new Intl.DateTimeFormat(locale, {
      year: "numeric", month: "short", day: "numeric", hour: "numeric", minute: "2-digit", second: "2-digit", timeZoneName: "short",
    }).format(new Date(ms));
  } catch {
    return new Date(ms).toISOString();
  }
}

/**
 * The journal's cost figure is in minor units (hundredths) of the summary's currency, or of USD when the summary
 * names none. Shown as money in the viewer's locale, never as a bare number.
 */
export function formatCostMinor(minor: number, locale: string, currency = "USD"): string {
  try {
    return new Intl.NumberFormat(locale, { style: "currency", currency, minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(minor / 100);
  } catch {
    return `${(minor / 100).toFixed(2)} ${currency}`;
  }
}

/** Human duration: "850 ms", "2.4 s", "1 m 5 s". */
export function formatMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`;
  const m = Math.floor(ms / 60_000);
  return `${m} m ${Math.round((ms % 60_000) / 1000)} s`;
}
