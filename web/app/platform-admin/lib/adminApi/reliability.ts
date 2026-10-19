import { apiFetch, toJsonQuiet } from "../api";

// Reliability console reads (ReliabilityHealthController, platform-admin only):
//   GET /api/platform/health/dependencies - per-dependency status, probed live
//     on the server (parallel, hard timeout per probe, cached ~30 s there).
//   GET /api/platform/slo?windowHours=N - availability, latency, LLM error
//     rate, job success and error-budget burn from sampled Micrometer counters.
//
// Every figure is measured or absent: a series the server cannot back with
// data arrives as null / is left out of its list, and `notes` says why. Never
// fill a gap client-side.
//
// Both calls pass `timeoutMs`, which makes apiFetch skip its stale-while-
// revalidate GET cache (that cache may serve a copy up to five minutes old -
// fine for a settings list, wrong for a health screen). `quiet` + toJsonQuiet
// keep a failed 30-second poll from raising a toast every time: the page shows
// its own error state instead.

export type DependencyStatus = "UP" | "DEGRADED" | "DOWN" | "UNKNOWN";

export interface DependencyEntry {
  name: string;
  /** database | cache | auth | ai | messaging | credential */
  category: string;
  status: DependencyStatus;
  /** Null when no probe actually ran (UNKNOWN). */
  latencyMs: number | null;
  lastCheckedAt: string;
  /** Written by the server; carries no hostname, URL or secret. */
  detail: string;
}

export interface DependenciesResponse {
  generatedAt: string;
  cacheTtlSeconds: number;
  overall: DependencyStatus;
  dependencies: DependencyEntry[];
  notes: string[];
}

export interface SloObjectives {
  availabilityTarget: number;
  apiP95TargetMs: number;
  chatTtftP95TargetMs: number;
  latencyQuantile: number;
}

export type SloVerdict = "OK" | "WARN" | "BREACH" | "INSUFFICIENT_DATA";

export interface AvailabilitySlo {
  requests: number;
  serverErrors: number;
  availability: number | null;
  errorRatio: number | null;
  /** Error ratio over the error budget; 1.0 = errors arrive exactly as fast as the objective allows. */
  burnRate: number | null;
  /** 1 - burnRate; negative when the window's budget is overspent. */
  budgetRemaining: number | null;
  verdict: SloVerdict;
}

export interface LatencySlo {
  /** chat_turn | chat_ttft | other_api */
  key: string;
  label: string;
  count: number;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  /** Null for a class with no target (the whole streamed chat answer). */
  targetMs: number | null;
  meetsTarget: boolean | null;
  slowShare: number | null;
  slowShareBoundMs: number | null;
  burnRate: number | null;
  verdict: SloVerdict | null;
}

export interface ProviderErrorRate {
  provider: string;
  calls: number;
  errors: number;
  errorRate: number | null;
}

export interface JobSlo {
  finished: number;
  succeeded: number;
  failed: number;
  successRate: number | null;
}

export interface SloResponse {
  generatedAt: string;
  windowHours: number;
  /** How much of the requested window the samples actually span. */
  coveredSeconds: number;
  /** "platform" = every replica combined; "replica" = only the replica that answered. */
  scope: "platform" | "replica";
  replicas: number;
  objectives: SloObjectives;
  availability: AvailabilitySlo | null;
  latency: LatencySlo[];
  llmProviders: ProviderErrorRate[];
  jobs: JobSlo | null;
  notes: string[];
}

const READ_TIMEOUT_MS = 20_000;

export const reliabilityApi = {
  dependencies: (): Promise<DependenciesResponse> =>
    apiFetch(`/api/platform/health/dependencies`, { timeoutMs: READ_TIMEOUT_MS, quiet: true }).then(toJsonQuiet),
  slo: (windowHours = 24): Promise<SloResponse> =>
    apiFetch(`/api/platform/slo?windowHours=${windowHours}`, { timeoutMs: READ_TIMEOUT_MS, quiet: true }).then(
      toJsonQuiet
    ),
};
