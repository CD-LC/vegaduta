/** GET /api/llm/telemetry - the billing page's spend summary. */
export interface LlmTelemetryResponse {
  callsLast24h: number;
  costTodayUsd: number | null;
  costPerConversationTodayUsd: number | null;
  conversationsToday: number;
  modelSplitLast24h: { model: string; calls: number; percent: number }[] | null;
}

/** An agent's eval gate: its config plus the head version's evaluation. */
export interface EvalGateStatus {
  gate: {
    id: string;
    agentId: string;
    datasetId: string;
    datasetName: string | null;
    enabled: boolean;
    maxRunAgeHours: number | null;
    requireSimulation: boolean;
    maxSimulationAgeHours: number | null;
    updatedBy: string | null;
    updatedAt: string;
  } | null;
  headVersionId: string | null;
  headEvaluation: {
    configured: boolean;
    enabled: boolean;
    allowed: boolean;
    reason: string;
    message: string | null;
    qualifyingRunId: string | null;
    verdict: string | null;
    overallScore: number | null;
    runFinishedAt: string | null;
    simulationRequired: boolean;
    simulationSessionId: string | null;
    simulationVerdict: string | null;
    simulationFinishedAt: string | null;
  } | null;
}

/** GET /api/channels/status - one aggregate row per channel. */
export interface ChannelsStatusResponse {
  channels: {
    key: string;
    label?: string;
    state: string;
    detail?: string;
    sessionCount?: number;
    defaultAgentId?: string;
  }[];
}

/** One voice IVR menu entry (digit -> agent). */
export interface DtmfMapping {
  digit: string;
  agentId: string;
  label?: string;
}

interface ComponentStatusReading {
  status: string;
}

/** GET /api/system/status (SystemStatusController). */
export interface SystemStatusResponse {
  overallStatus: string;
  database?:
    | (ComponentStatusReading & {
        product?: string | null;
        activeConnections?: number | null;
        maxPoolSize?: number | null;
      })
    | null;
  redis?: ComponentStatusReading | null;
  kafka?:
    | (ComponentStatusReading & {
        config?: {
          bootstrapServers?: string | null;
          topic?: string;
          partitions?: number;
          replicationFactor?: number;
          consumerGroup?: string;
          maxRetries?: number;
          retryBackoffMs?: number;
          dltTopic?: string;
        } | null;
        queue?: { pendingCount: number; deadLetteredCount: number } | null;
        dlq?: { messageCount?: number | null } | null;
      })
    | null;
  mcp?: { totalServers: number; enabledServers: number } | null;
  jvm?: {
    heapUsedMb: number;
    heapMaxMb?: number | null;
    uptimeSeconds: number;
    availableProcessors?: number | null;
  } | null;
  llm?: { totalCalls: number; errorCount: number; avgLatencyMs?: number | null } | null;
  sidecar?: ComponentStatusReading | null;
}
