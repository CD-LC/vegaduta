import { apiFetch, toJson, toJsonQuietOn } from "./core";

// Read-only numbers for the Insights page (app/insights) and the home teaser.
//
// Two sources, both existing core endpoints:
//   - /api/admin/spend-reports/** (core/chat SpendReportController): the
//     workspace's LLM usage from the daily rollup, plus a live query for today.
//     Gated by the VIEW_USAGE permission, so a member without it gets 403. That
//     is an expected state here (the page then shows the job counts only), so
//     403 raises no global toast.
//   - /api/agent-jobs/counts (core/agents AgentJobOperatorController): job
//     counts. Open to every signed-in member; `tenantWide` in the answer says
//     whether it covers the workspace or only the caller's visible agents.
//
// Deliberately NOT spread into the `api` barrel (lib/api.ts): only two screens
// read this, and they import `insightsApi` directly.

/** core SpendReport.UsageMetrics. `successRate` is 0 (not null) for zero calls - do not display it as is. */
export interface UsageMetricsDto {
  totalCalls: number;
  errorCount: number;
  successRate: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens: number;
  messageVolume: number;
  distinctSessions: number;
  /** BigDecimal on the server: a JSON number, tolerated as a string too. */
  estimatedCostUsd: number | string | null;
  avgLatencyMs: number | null;
  /** Call-weighted mean of the daily p50s, so approximate over more than one day. */
  latencyP50Ms: number | null;
  latencyP95Ms: number | null;
  cacheHitRate: number | null;
}

/** core SpendReport.Summary. `fromDate` / `toDate` echo the range the server actually used. */
export interface SpendSummaryDto {
  fromDate: string;
  toDate: string;
  /** True when the range reaches today (UTC): today's numbers are live and still growing. */
  includesLiveToday: boolean;
  metrics: UsageMetricsDto;
}

/** Only days that had calls are returned - a quiet day is absent, not zero. */
export interface DayUsageDto {
  date: string;
  metrics: UsageMetricsDto;
}

export interface ModelUsageDto {
  model: string | null;
  modality: string | null;
  metrics: UsageMetricsDto;
}

export interface ProviderUsageDto {
  providerName: string | null;
  metrics: UsageMetricsDto;
}

export interface AgentUsageDto {
  /** Null for calls that were not made by an agent. */
  agentId: string | null;
  metrics: UsageMetricsDto;
}

export interface ModalityUsageDto {
  modality: string | null;
  metrics: UsageMetricsDto;
}

/** core AgentJobCountsResponse. */
export interface AgentJobCountsDto {
  queued: number;
  running: number;
  awaitingApproval: number;
  /** queued + running + awaitingApproval, counted now (not windowed). */
  live: number;
  succeededInWindow: number;
  failedInWindow: number;
  cancelledInWindow: number;
  /** The window the server applied (it clamps to 1 minute..30 days): ISO-8601 duration, or seconds. */
  window: string | number | null;
  windowStart: string | null;
  asOf: string | null;
  agentId: string | null;
  /** True = whole workspace (admin). False = only the agents the caller can see. */
  tenantWide: boolean;
}

/** Inclusive ISO dates (yyyy-MM-dd). Omit both for the server default (last 30 days). */
export interface SpendRange {
  from?: string;
  to?: string;
}

function rangeQuery(range: SpendRange = {}): string {
  const params = new URLSearchParams();
  if (range.from) params.set("from", range.from);
  if (range.to) params.set("to", range.to);
  const query = params.toString();
  return query ? `?${query}` : "";
}

const spend = <T>(path: string, range?: SpendRange): Promise<T> =>
  apiFetch(`/api/admin/spend-reports/${path}${rangeQuery(range)}`).then(toJsonQuietOn([403]));

export const insightsApi = {
  getSpendSummary: (range?: SpendRange) => spend<SpendSummaryDto>("summary", range),
  getSpendByDay: (range?: SpendRange) => spend<DayUsageDto[]>("by-day", range),
  getSpendByModel: (range?: SpendRange) => spend<ModelUsageDto[]>("by-model", range),
  getSpendByProvider: (range?: SpendRange) => spend<ProviderUsageDto[]>("by-provider", range),
  getSpendByAgent: (range?: SpendRange) => spend<AgentUsageDto[]>("by-agent", range),
  getSpendByModality: (range?: SpendRange) => spend<ModalityUsageDto[]>("by-modality", range),

  /**
   * Live job counts plus finished-job counts over `window` (ISO-8601 duration,
   * e.g. "P7D"; server default 24 hours, clamped to 30 days and echoed back).
   */
  getAgentJobCounts: (opts: { agentId?: string; window?: string } = {}): Promise<AgentJobCountsDto> => {
    const params = new URLSearchParams();
    if (opts.agentId) params.set("agentId", opts.agentId);
    if (opts.window) params.set("window", opts.window);
    const query = params.toString();
    return apiFetch(`/api/agent-jobs/counts${query ? `?${query}` : ""}`).then(toJson<AgentJobCountsDto>);
  },
};

/** True for the "you may not see workspace-wide usage" answer, as opposed to a real failure. */
export function isForbidden(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { status?: unknown }).status === 403;
}
