import { apiFetch, toJsonQuietOn, jsonBody } from "./core";
import type {
  AgentCapabilityEntry,
  AgentJobResponse,
  AgentJobSummaryResponse,
  DocumentGenerationJob,
  DocumentGenerationRequest,
  ResearchRunStartPayload,
  ResearchStatus,
} from "./types";

// Deep research (core ResearchRunController, /api/research). A run is an
// ordinary agent job on the caller's own research agent, so every run route
// answers with the same job row shapes the /jobs screens read - progress
// notes and the trace come from the existing agent-job endpoints, using the
// agentId on the row.
//
// Expected states are kept quiet (no global error toast) because the page
// renders them inline:
//  - status: 404/503 = a core build without the feature; the page shows
//    "not available" and the entry cards elsewhere simply hide.
//  - start: 400 validation, 403 plan ceiling, 404 runs switched off,
//    429 daily allowance used, 503 research switched off / no search key -
//    each arrives with a ready-to-show sentence in the error body.
//  - list/get/cancel/progress: 404 = runs switched off, or not the caller's run.
export const researchApi = {
  getResearchStatus: (): Promise<ResearchStatus> =>
    apiFetch("/api/research/status").then(toJsonQuietOn<ResearchStatus>([404, 503])),
  startResearchRun: (payload: ResearchRunStartPayload) =>
    apiFetch("/api/research/runs", { method: "POST", ...jsonBody(payload) }).then(
      toJsonQuietOn<AgentJobResponse>([400, 403, 404, 429, 503])
    ),
  listResearchRuns: (opts: { limit?: number; offset?: number } = {}) => {
    const params = new URLSearchParams();
    if (opts.limit != null) params.set("limit", String(opts.limit));
    if (opts.offset != null) params.set("offset", String(opts.offset));
    const qs = params.toString();
    return apiFetch(`/api/research/runs${qs ? `?${qs}` : ""}`).then(toJsonQuietOn<AgentJobSummaryResponse[]>([404]));
  },
  getResearchRun: (jobId: string) =>
    apiFetch(`/api/research/runs/${encodeURIComponent(jobId)}`).then(toJsonQuietOn<AgentJobResponse>([404])),
  // Idempotent server-side: a finished run returns its row. A refusal is shown inline by the run page, not as a toast too.
  cancelResearchRun: (jobId: string) =>
    apiFetch(`/api/research/runs/${encodeURIComponent(jobId)}/cancel`, { method: "POST" }).then(toJsonQuietOn<AgentJobResponse>([403, 404, 409])),
  // Same endpoint as agentLifecycleApi.getAgentJobProgress, but quiet on 404:
  // the run page polls it, and a poll must not raise a toast every cycle.
  getResearchRunProgress: (agentId: string, jobId: string) =>
    apiFetch(`/api/agents/${agentId}/jobs/${jobId}/progress`).then(toJsonQuietOn([403, 404])),
  // Same endpoint as mediaApi.generateDocument, but quiet on every way the
  // document service refuses a request: today a FREE plan, the feature being
  // switched off and a used-up daily/monthly cap all arrive as 500 (the quota
  // exception has no handler mapping in core yet); 403/404/429/503 are what a
  // mapped refusal or a build without the service would answer. The export
  // menu shows one inline line instead - an expected refusal is not a toast.
  generateResearchDocument: (request: DocumentGenerationRequest): Promise<DocumentGenerationJob> =>
    apiFetch(`/api/documentgen/documents`, { method: "POST", ...jsonBody(request) }).then(
      toJsonQuietOn([403, 404, 429, 500, 503])
    ),
  // Quiet twins of agentsApi.listAgentCapabilities / setAgentCapability for
  // the best-effort "switch on what the template asked for" step after an
  // agent is created: the agent exists either way, so a refusal here must not
  // raise an error toast over the success message.
  listAgentCapabilitiesQuiet: (agentId: string): Promise<AgentCapabilityEntry[]> =>
    apiFetch(`/api/agents/${agentId}/capabilities`).then(toJsonQuietOn([400, 403, 404, 409, 503])),
  setAgentCapabilityQuiet: (agentId: string, capabilityKey: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/capabilities/${encodeURIComponent(capabilityKey)}`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJsonQuietOn([400, 403, 404, 409, 503])),
};
