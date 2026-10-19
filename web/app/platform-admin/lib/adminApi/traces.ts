import { apiFetch, toJson } from "../api";
import { queryString } from "./operations";

// LLM traces - core proxies Langfuse (GET /api/platform/traces[/{id}|/metrics]),
// so the browser never holds Langfuse keys and never needs Langfuse's origin.
// `available: false` is a STATE, not an error: Langfuse is not configured for
// this environment or did not answer, and `reason` says which. The page shows
// that reason inline instead of an error screen.

export interface TraceRow {
  id: string;
  name: string | null;
  timestamp: string | null;
  userId: string | null;
  sessionId: string | null;
  tenantId: string | null;
  latencyMs: number | null;
  totalCostUsd: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  level: string | null;
  tags: string[] | null;
}

export interface TraceListResponse {
  available: boolean;
  reason?: string;
  data: TraceRow[];
  meta: { page: number; limit: number; totalItems: number; totalPages: number };
}

export interface TraceObservation {
  id: string;
  type: string | null;
  name: string | null;
  startTime: string | null;
  endTime: string | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  costUsd: number | null;
  level: string | null;
  statusMessage: string | null;
}

export interface TraceDetailResponse {
  available: boolean;
  reason?: string;
  trace: (TraceRow & { input: string | null; output: string | null }) | null;
  observations: TraceObservation[];
}

export interface TraceDayMetric {
  date: string;
  traces: number | null;
  costUsd: number | null;
  p50LatencyMs: number | null;
  p95LatencyMs: number | null;
}

export interface TraceModelMetric {
  model: string;
  calls: number | null;
  costUsd: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
}

export interface TraceMetricsResponse {
  available: boolean;
  reason?: string;
  byDay: TraceDayMetric[];
  byModel: TraceModelMetric[];
}

export interface TraceFilters {
  from?: string;
  to?: string;
  tenantId?: string;
  userId?: string;
  name?: string;
  page?: number;
  limit?: number;
}

// Langfuse round trips can be slow; timeoutMs also opts out of the SWR GET
// cache (a trace list should be live, not up to five minutes old).
const TRACE_TIMEOUT_MS = 30_000;

export const tracesApi = {
  list: (filters: TraceFilters): Promise<TraceListResponse> =>
    apiFetch(`/api/platform/traces${queryString(filters as any)}`, { timeoutMs: TRACE_TIMEOUT_MS }).then(toJson),
  get: (id: string): Promise<TraceDetailResponse> =>
    apiFetch(`/api/platform/traces/${encodeURIComponent(id)}`, { timeoutMs: TRACE_TIMEOUT_MS }).then(toJson),
  metrics: (range: { from?: string; to?: string }): Promise<TraceMetricsResponse> =>
    apiFetch(`/api/platform/traces/metrics${queryString(range)}`, { timeoutMs: TRACE_TIMEOUT_MS }).then(toJson),
};
