import { apiFetch, toJson } from "../api";

// Fleet supervisor (FleetHealthController, platform-admin, read-only) - the
// per-agent SLO picture across every ACTIVE tenant: windowed error rates,
// job success, eval-score drift, spend anomalies against the 7-day baseline.
// Kill-switch escalation reuses the existing tenantDetailApi stop/resume.

export type AgentHealth = {
  tenantId: string;
  tenantName: string;
  tenantEmergencyStopped: boolean;
  agentId: string;
  agentName: string;
  calls: number;
  failedCalls: number;
  errorRate: number | null;
  costUsd: number;
  tokens: number;
  baselineDailyCostUsd: number;
  spendRatio: number | null;
  jobsFinished: number;
  jobsSucceeded: number;
  jobSuccessRate: number | null;
  latestEvalScore: number | null;
  previousEvalScore: number | null;
  latestEvalVerdict: string | null;
  latestEvalFinishedAt: string | null;
  lastCallAt: string | null;
  alerts: string[];
  health: "OK" | "WARN" | "BREACH" | "STOPPED";
};

export type FleetSlo = {
  windowHours: number;
  maxErrorRate: number;
  minJobSuccessRate: number;
  evalDriftDrop: number;
  spendSpikeRatio: number;
  minCallsForSlo: number;
  minSpendUsd: number;
};

export type FleetHealthResponse = {
  generatedAt: string;
  slo: FleetSlo;
  agents: AgentHealth[];
  unavailableTenants: string[];
};

export const fleetApi = {
  // Every SLO is a query parameter (FleetHealthController): the console's
  // threshold panel re-queries with the operator's bar, no redeploy needed.
  health: (slo: Partial<FleetSlo> = {}): Promise<FleetHealthResponse> => {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(slo)) {
      if (value != null) params.set(key, String(value));
    }
    const qs = params.toString();
    return apiFetch(`/api/platform/fleet/health${qs ? `?${qs}` : ""}`).then(toJson);
  },
};
