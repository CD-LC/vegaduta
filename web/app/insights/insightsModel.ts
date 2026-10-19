// Pure mapping for the Insights page: core DTOs -> what the screen draws.
// No React, no fetch, no i18n lookups - sentences are returned as { key, values }
// against the `insights` namespace so they can be unit-tested and the page only
// has to call t().
//
// The honesty rules live here, in one place:
//   - A rate is only ever computed from complete server aggregates (totalCalls /
//     errorCount), never from a list the UI capped. The server's own
//     `successRate` is 0 for zero calls, so it is NOT used: zero calls -> null
//     -> "-" on screen, never 0% or 100%.
//   - Cost is the server's estimate; every label that shows it says so.
//   - Latency percentiles over several days are a call-weighted mean of daily
//     percentiles, so they are always printed with "~".
//   - The spend endpoints return only days that had calls. Missing days inside
//     the range the server echoed back are filled with zero so the time axis is
//     even; when the range includes today the last bar is flagged partial.
//   - The job window is whatever the server applied (it clamps to 30 days), read
//     back from windowStart / asOf rather than assumed from the request.
import type {
  AgentJobCountsDto,
  AgentUsageDto,
  DayUsageDto,
  ModelUsageDto,
  SpendSummaryDto,
  UsageMetricsDto,
} from "../lib/api/insights";
import { formatPercent, isFiniteNumber, rate, type ChartState } from "../components/charts/chartMath";

export const PERIODS = [7, 30, 90] as const;
export type PeriodDays = (typeof PERIODS)[number];

/** Every `insights.*` key this file can return in a Msg. insightsI18nKeys.test.ts checks them against en.json. */
export const MODEL_KEYS = [
  "callsSummary",
  "callsSummaryLive",
  "costSummary",
  "costSummaryLive",
  "topSummary",
  "topSummaryOne",
  "jobsSummary",
  "jobsSummaryNoneFinished",
  "jobsWindowDays",
  "jobsWindowDay",
  "jobsWindowHours",
  "jobsWindowHour",
  "jobsWindowMinutes",
  "jobsWindowMinute",
  "jobsWindowUnknown",
  "jobQueued",
  "jobRunning",
  "jobAwaitingApproval",
  "jobSucceeded",
  "jobFailed",
  "teaserSummary",
  "teaserSummaryLive",
] as const;
export type ModelKey = (typeof MODEL_KEYS)[number];

export interface Msg {
  key: ModelKey;
  values?: Record<string, string | number>;
}

export const PLACEHOLDER = "-";
const DAY_MS = 86_400_000;
const MAX_FILLED_DAYS = 400;

// ---------------------------------------------------------------- dates

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Milliseconds (UTC midnight) for a strict yyyy-MM-dd date, or null. */
export function parseIsoDate(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const match = ISO_DATE.exec(value);
  if (!match) return null;
  const ms = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  // Rejects overflow dates such as 2026-02-31.
  return new Date(ms).toISOString().slice(0, 10) === value ? ms : null;
}

function toIsoDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

/**
 * The inclusive from/to to ask the spend endpoints for. UTC on purpose: the
 * server's "today" (and its includesLiveToday flag) is the UTC date.
 */
export function rangeForPeriod(days: PeriodDays, now: Date = new Date()): { from: string; to: string } {
  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  return { from: toIsoDate(today - (days - 1) * DAY_MS), to: toIsoDate(today) };
}

/** ISO-8601 duration for the job counts `window` parameter. The server clamps it to 30 days. */
export function jobWindowForPeriod(days: PeriodDays): string {
  return `P${days}D`;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * "3 Oct" for 2026-10-03. Falls back to the raw string for anything unparsable.
 * Without a locale the label is built by hand, because Intl's English short
 * months differ between runtimes ("Sep" vs "Sept"); pass a locale to localise.
 */
export function formatDayLabel(isoDate: string, locale?: string): string {
  const ms = parseIsoDate(isoDate);
  if (ms === null) return String(isoDate ?? "");
  if (!locale) {
    const date = new Date(ms);
    return `${date.getUTCDate()} ${MONTHS[date.getUTCMonth()]}`;
  }
  try {
    return new Intl.DateTimeFormat(locale, { day: "numeric", month: "short", timeZone: "UTC" }).format(new Date(ms));
  } catch {
    return isoDate;
  }
}

// ---------------------------------------------------------------- numbers

/** A finite number from a JSON number or numeric string (BigDecimal may arrive as either), else null. */
export function toNumber(value: unknown): number | null {
  if (isFiniteNumber(value)) return value;
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

/** A non-negative whole count; anything else is 0. */
export function toCount(value: unknown): number {
  const n = toNumber(value);
  return n !== null && n > 0 ? Math.floor(n) : 0;
}

export function formatCount(value: number): string {
  return toCount(value).toLocaleString("en-US");
}

// Formatted with Intl (not string literals): a dollar amount here is estimated usage cost, never a plan price,
// and the plan-price ratchet (scripts/ci/price-grep.sh) rightly refuses new hardcoded dollar literals.
const USD = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });

/** Currency text for an estimate; a real but tiny amount reads "less than one cent"; "-" for unknown. */
export function formatUsd(value: number | null | undefined): string {
  if (!isFiniteNumber(value) || value < 0) return PLACEHOLDER;
  if (value === 0) return USD.format(0);
  if (value < 0.01) return `<${USD.format(0.01)}`;
  return USD.format(value);
}

/** "~420 ms" / "~1.4 s"; "-" when there is no measurement. Always approximate. */
export function formatLatency(ms: number | null | undefined): string {
  if (!isFiniteNumber(ms) || ms < 0) return PLACEHOLDER;
  if (ms < 1000) return `~${Math.round(ms)} ms`;
  return `~${(ms / 1000).toFixed(1)} s`;
}

/**
 * A 0..1 rate as text. null -> "-". Never rounds a rate with failures up to
 * "100%" or a rate with successes down to "0%".
 */
export function formatRate(value: number | null | undefined): string {
  if (!isFiniteNumber(value)) return PLACEHOLDER;
  const whole = formatPercent(value);
  if (whole === null) return PLACEHOLDER;
  if (value < 1 && whole === "100%") {
    const fine = formatPercent(value, 1);
    return fine === "100.0%" ? ">99.9%" : (fine ?? PLACEHOLDER);
  }
  if (value > 0 && whole === "0%") {
    const fine = formatPercent(value, 1);
    return fine === "0.0%" ? "<0.1%" : (fine ?? PLACEHOLDER);
  }
  return whole;
}

// ---------------------------------------------------------------- KPIs

export interface Kpis {
  calls: number;
  failed: number;
  succeeded: number;
  /** null when there were no calls. */
  successRate: number | null;
  costUsd: number | null;
  callsText: string;
  successRateText: string;
  costText: string;
  p50Text: string;
  p95Text: string;
  /** The range the server says these numbers cover (may be empty strings if it sent none). */
  fromDate: string;
  toDate: string;
  includesLiveToday: boolean;
  /** False for a workspace with no calls in the range: draw the empty state, not charts. */
  hasData: boolean;
}

function callsOf(metrics: UsageMetricsDto | null | undefined): { calls: number; failed: number } {
  const calls = toCount(metrics?.totalCalls);
  return { calls, failed: Math.min(calls, toCount(metrics?.errorCount)) };
}

export function buildKpis(summary: SpendSummaryDto | null | undefined): Kpis {
  const metrics = summary?.metrics;
  const { calls, failed } = callsOf(metrics);
  const succeeded = calls - failed;
  const successRate = rate(succeeded, calls);
  const costUsd = toNumber(metrics?.estimatedCostUsd);
  return {
    calls,
    failed,
    succeeded,
    successRate,
    costUsd,
    callsText: formatCount(calls),
    successRateText: formatRate(successRate),
    costText: formatUsd(costUsd),
    p50Text: calls > 0 ? formatLatency(toNumber(metrics?.latencyP50Ms)) : PLACEHOLDER,
    p95Text: calls > 0 ? formatLatency(toNumber(metrics?.latencyP95Ms)) : PLACEHOLDER,
    fromDate: typeof summary?.fromDate === "string" ? summary.fromDate : "",
    toDate: typeof summary?.toDate === "string" ? summary.toDate : "",
    includesLiveToday: summary?.includesLiveToday === true,
    hasData: calls > 0,
  };
}

// ---------------------------------------------------------------- per day

export interface DayPoint {
  /** yyyy-MM-dd */
  date: string;
  /** "3 Oct" */
  label: string;
  calls: number;
  failed: number;
  costUsd: number;
}

export interface DailySeries {
  days: DayPoint[];
  /** The last bar is today and still counting. */
  partialLast: boolean;
}

/**
 * One point per calendar day of the server-echoed range, zero-filled. If the
 * range is missing or unusable the rows are returned as sent (sorted), and the
 * last bar is only flagged partial when it really is the range's last day.
 */
export function buildDailySeries(
  summary: SpendSummaryDto | null | undefined,
  byDay: DayUsageDto[] | null | undefined,
  locale?: string
): DailySeries {
  const byDate = new Map<string, DayPoint>();
  for (const row of Array.isArray(byDay) ? byDay : []) {
    if (parseIsoDate(row?.date) === null) continue;
    const { calls, failed } = callsOf(row.metrics);
    const cost = toNumber(row.metrics?.estimatedCostUsd);
    byDate.set(row.date, {
      date: row.date,
      label: formatDayLabel(row.date, locale),
      calls,
      failed,
      costUsd: cost !== null && cost > 0 ? cost : 0,
    });
  }

  const from = parseIsoDate(summary?.fromDate);
  const to = parseIsoDate(summary?.toDate);
  const includesLiveToday = summary?.includesLiveToday === true;

  if (from !== null && to !== null && from <= to && (to - from) / DAY_MS < MAX_FILLED_DAYS) {
    const days: DayPoint[] = [];
    for (let ms = from; ms <= to; ms += DAY_MS) {
      const date = toIsoDate(ms);
      days.push(byDate.get(date) ?? { date, label: formatDayLabel(date, locale), calls: 0, failed: 0, costUsd: 0 });
    }
    return { days, partialLast: includesLiveToday };
  }

  const days = [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date));
  const last = days[days.length - 1];
  return { days, partialLast: includesLiveToday && !!last && last.date === summary?.toDate };
}

/** "9 of 10 AI calls succeeded from 29 Sep to 5 Oct." Null when there were no calls. */
export function callsSummary(kpis: Kpis, locale?: string): Msg | null {
  if (!kpis.hasData) return null;
  return {
    key: kpis.includesLiveToday ? "callsSummaryLive" : "callsSummary",
    values: {
      succeeded: formatCount(kpis.succeeded),
      total: formatCount(kpis.calls),
      from: formatDayLabel(kpis.fromDate, locale),
      to: formatDayLabel(kpis.toDate, locale),
    },
  };
}

export function costSummary(kpis: Kpis, locale?: string): Msg | null {
  if (!kpis.hasData) return null;
  return {
    key: kpis.includesLiveToday ? "costSummaryLive" : "costSummary",
    values: {
      cost: kpis.costText,
      from: formatDayLabel(kpis.fromDate, locale),
      to: formatDayLabel(kpis.toDate, locale),
    },
  };
}

// ---------------------------------------------------------------- rankings

export interface RankedRow {
  key: string;
  /** Display name, or null when the page must supply a translated stand-in (see `kind`). */
  label: string | null;
  /** named: `label` is set. unknown: an id with no known name (`shortId`). none: no agent / model at all. */
  kind: "named" | "unknown" | "none";
  shortId?: string;
  calls: number;
  costUsd: number;
  costText: string;
}

export interface Ranking {
  /** Every row that made calls, highest estimated cost first. The page caps what it draws; this is the full list. */
  rows: RankedRow[];
  totalCostUsd: number;
  /** False when calls ran but none carries a cost: there is nothing to rank by. */
  hasCost: boolean;
}

function finishRanking(rows: Array<Omit<RankedRow, "costText">>): Ranking {
  const active = rows
    .filter((row) => row.calls > 0 || row.costUsd > 0)
    .sort((a, b) => b.costUsd - a.costUsd || b.calls - a.calls || a.key.localeCompare(b.key))
    .map((row) => ({ ...row, costText: formatUsd(row.costUsd) }));
  const totalCostUsd = active.reduce((acc, row) => acc + row.costUsd, 0);
  return { rows: active, totalCostUsd, hasCost: totalCostUsd > 0 };
}

function costOf(metrics: UsageMetricsDto | null | undefined): number {
  const cost = toNumber(metrics?.estimatedCostUsd);
  return cost !== null && cost > 0 ? cost : 0;
}

/** by-model rows are per (model, modality); the same model name is merged into one row. */
export function rankModels(byModel: ModelUsageDto[] | null | undefined): Ranking {
  const merged = new Map<string, Omit<RankedRow, "costText">>();
  for (const row of Array.isArray(byModel) ? byModel : []) {
    const name = typeof row?.model === "string" && row.model.trim() !== "" ? row.model.trim() : null;
    const key = name ?? "__none__";
    const current = merged.get(key) ?? { key, label: name, kind: name ? ("named" as const) : ("none" as const), calls: 0, costUsd: 0 };
    current.calls += callsOf(row?.metrics).calls;
    current.costUsd += costOf(row?.metrics);
    merged.set(key, current);
  }
  return finishRanking([...merged.values()]);
}

/** `agentNames` maps agent id -> name (best effort; an id without a name becomes kind "unknown"). */
export function rankAgents(
  byAgent: AgentUsageDto[] | null | undefined,
  agentNames: ReadonlyMap<string, string> = new Map()
): Ranking {
  const merged = new Map<string, Omit<RankedRow, "costText">>();
  for (const row of Array.isArray(byAgent) ? byAgent : []) {
    const id = typeof row?.agentId === "string" && row.agentId !== "" ? row.agentId : null;
    const key = id ?? "__none__";
    const name = id ? agentNames.get(id) : undefined;
    const current =
      merged.get(key) ??
      (id === null
        ? { key, label: null, kind: "none" as const, calls: 0, costUsd: 0 }
        : name
          ? { key, label: name, kind: "named" as const, calls: 0, costUsd: 0 }
          : { key, label: null, kind: "unknown" as const, shortId: id.slice(0, 8), calls: 0, costUsd: 0 });
    current.calls += callsOf(row?.metrics).calls;
    current.costUsd += costOf(row?.metrics);
    merged.set(key, current);
  }
  return finishRanking([...merged.values()]);
}

/**
 * "{name} has the highest estimated cost: <cost> of <total> across 6." `name` is
 * left for the page to fill (it owns the translated stand-ins). Null when
 * there is no cost to rank.
 */
export function rankingSummary(ranking: Ranking): { msg: Msg; top: RankedRow } | null {
  if (!ranking.hasCost || ranking.rows.length === 0) return null;
  const top = ranking.rows[0];
  if (ranking.rows.length === 1) return { top, msg: { key: "topSummaryOne", values: { cost: top.costText } } };
  return {
    top,
    msg: {
      key: "topSummary",
      values: { cost: top.costText, total: formatUsd(ranking.totalCostUsd), count: ranking.rows.length },
    },
  };
}

// ---------------------------------------------------------------- jobs

export interface JobSegment {
  key: "queued" | "running" | "awaitingApproval" | "succeeded" | "failed";
  labelKey: ModelKey;
  count: number;
  state: ChartState;
}

export interface JobsView {
  segments: JobSegment[];
  /** Sum of the five segments. Zero -> empty state. */
  total: number;
  live: number;
  succeeded: number;
  failed: number;
  /** Finished in the window but not drawn in the bar. */
  cancelled: number;
  /** True = whole workspace; false = only the agents the caller can see. */
  tenantWide: boolean;
  /** The window the server applied, as a phrase ("the last 7 days"). */
  window: Msg;
  /** Needs `window` (the translated phrase above) added to its values by the page. */
  summary: Msg | null;
}

const ISO_DURATION = /^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/;

/** Length of the applied job window in ms: from windowStart/asOf, else the `window` field, else null. */
export function jobWindowMs(counts: Pick<AgentJobCountsDto, "window" | "windowStart" | "asOf"> | null | undefined): number | null {
  const start = typeof counts?.windowStart === "string" ? Date.parse(counts.windowStart) : NaN;
  const end = typeof counts?.asOf === "string" ? Date.parse(counts.asOf) : NaN;
  if (Number.isFinite(start) && Number.isFinite(end) && end > start) return end - start;

  const raw = counts?.window;
  // Jackson may write a Duration as seconds.
  if (isFiniteNumber(raw) && raw > 0) return raw * 1000;
  if (typeof raw === "string") {
    const match = ISO_DURATION.exec(raw.trim());
    if (match && (match[1] || match[2] || match[3] || match[4])) {
      const ms =
        Number(match[1] || 0) * DAY_MS + Number(match[2] || 0) * 3_600_000 + Number(match[3] || 0) * 60_000 + Number(match[4] || 0) * 1000;
      return ms > 0 ? ms : null;
    }
  }
  return null;
}

export function describeJobWindow(ms: number | null): Msg {
  if (!isFiniteNumber(ms) || ms <= 0) return { key: "jobsWindowUnknown" };
  const pick = (count: number, one: ModelKey, many: ModelKey): Msg =>
    count === 1 ? { key: one } : { key: many, values: { count } };
  if (ms >= 2 * DAY_MS) return pick(Math.round(ms / DAY_MS), "jobsWindowDay", "jobsWindowDays");
  if (ms >= 3_600_000) return pick(Math.round(ms / 3_600_000), "jobsWindowHour", "jobsWindowHours");
  return pick(Math.max(1, Math.round(ms / 60_000)), "jobsWindowMinute", "jobsWindowMinutes");
}

export function buildJobs(counts: AgentJobCountsDto | null | undefined): JobsView {
  const queued = toCount(counts?.queued);
  const running = toCount(counts?.running);
  const awaitingApproval = toCount(counts?.awaitingApproval);
  const succeeded = toCount(counts?.succeededInWindow);
  const failed = toCount(counts?.failedInWindow);
  const cancelled = toCount(counts?.cancelledInWindow);
  const live = queued + running + awaitingApproval;
  const total = live + succeeded + failed;

  const segments: JobSegment[] = [
    { key: "queued", labelKey: "jobQueued", count: queued, state: "queued" },
    { key: "running", labelKey: "jobRunning", count: running, state: "running" },
    { key: "awaitingApproval", labelKey: "jobAwaitingApproval", count: awaitingApproval, state: "warning" },
    { key: "succeeded", labelKey: "jobSucceeded", count: succeeded, state: "success" },
    { key: "failed", labelKey: "jobFailed", count: failed, state: "error" },
  ];

  let summary: Msg | null = null;
  if (total > 0) {
    summary =
      succeeded + failed > 0
        ? {
            key: "jobsSummary",
            values: { succeeded: formatCount(succeeded), failed: formatCount(failed), live: formatCount(live) },
          }
        : { key: "jobsSummaryNoneFinished", values: { live: formatCount(live) } };
  }

  return {
    segments,
    total,
    live,
    succeeded,
    failed,
    cancelled,
    tenantWide: counts?.tenantWide === true,
    window: describeJobWindow(jobWindowMs(counts)),
    summary,
  };
}

// ---------------------------------------------------------------- home teaser

export interface Teaser {
  series: DailySeries;
  summary: Msg;
}

/** The home-page teaser, or null when there is nothing worth showing (no calls, or unusable data). */
export function buildTeaser(
  summary: SpendSummaryDto | null | undefined,
  byDay: DayUsageDto[] | null | undefined,
  locale?: string
): Teaser | null {
  const kpis = buildKpis(summary);
  if (!kpis.hasData) return null;
  const series = buildDailySeries(summary, byDay, locale);
  if (series.days.length === 0) return null;
  return {
    series,
    summary: {
      key: kpis.includesLiveToday ? "teaserSummaryLive" : "teaserSummary",
      values: {
        succeeded: formatCount(kpis.succeeded),
        total: formatCount(kpis.calls),
        days: series.days.length,
      },
    },
  };
}
