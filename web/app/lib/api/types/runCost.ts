import type { ApprovalRequestResponse } from "./audit";
import type { NotificationResponse } from "./usage";

// GET /api/agents/{id}/sessions/{sessionId}/llm-usage/summary - mirrors
// RunCostTimeline.Summary / .Step in core/chat field for field. Costs are
// BigDecimal server-side (JSON numbers); per-call token/cost fields are
// nullable because a provider may not report them, and `unpricedCalls`
// counts the calls whose cost is unknown - so totalCostUsd is a floor, not
// a bill. PREMIUM-gated: FREE plans get 403.
export interface RunCostStep {
  index: number;
  id: string;
  offsetMs: number;
  latencyMs: number;
  providerName: string | null;
  model: string | null;
  success: boolean;
  promptTokens: number | null;
  completionTokens: number | null;
  costUsd: number | null;
  cumulativeCostUsd: number | null;
  cacheReadTokens: number | null;
  cacheWriteTokens: number | null;
  cacheHitRatio: number | null;
  agentVersionId: string | null;
  traceId: string | null;
  spanId: string | null;
  startedAt: string | null;
  // Thinking tokens a provider billed OUTSIDE completionTokens (Gemini), recovered from the
  // row's totals. null = unknown (OpenAI/Anthropic fold reasoning into completionTokens).
  // Already inside costUsd - informational, never add it to the cost.
  reasoningTokens?: number | null;
}

export interface RunCostSummary {
  sessionId: string;
  agentId: string;
  callCount: number;
  failedCalls: number;
  unpricedCalls: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  totalCostUsd: number | null;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cacheAccountedCalls: number;
  cacheHitRatio: number | null;
  startedAt: string | null;
  endedAt: string | null;
  wallClockMs: number;
  summedLatencyMs: number;
  steps: RunCostStep[];
  // Run total of the steps' reasoningTokens; null when no step reported any.
  reasoningTokens?: number | null;
}

export interface SentinelOutcomeItem {
  runId: string;
  scheduleId: string;
  agentId: string | null;
  agentName: string | null;
  outcome: string;
  outcomeNote: string | null;
  jobId: string | null;
  startedAt: string;
}

export interface AmbientDigest {
  since: string;
  sentinelScheduleCount: number;
  pendingApprovals: { total: number; items: ApprovalRequestResponse[] };
  sentinelOutcomes: SentinelOutcomeItem[];
  jobs: { observed: number; truncated: boolean; byStatus: Record<string, number> };
  agentNotifications: NotificationResponse[];
}

// ===================================================================
// Wave-2 monetization console surfaces (2026-07-31, audit D3/D5/D6 -
// docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md). The backing controllers
// are being built in a PARALLEL core session, so every GET below routes
// through toJsonQuietOn: against an older core these answer 404 (route
// missing) and the calling UI must hide the whole section silently rather
// than raise the global error toast. Field shapes follow the agreed
// contract; the invoice summary keeps an index signature because its
// exact field names aren't backend-confirmed yet - the UI probes
// defensively.
// -------------------------------------------------------------------
