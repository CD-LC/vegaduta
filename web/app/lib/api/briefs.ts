import { apiFetch, toJson, jsonBody } from "./core";
import { parseSse } from "../../components/brief/logic";
import type {
  ApplyJob,
  Brief,
  BriefDetail,
  BriefLedger,
  BriefListItem,
  PlanRequest,
  PlanResult,
  PlanStreamEvent,
  ReadinessRow,
  RehearsalSession,
  RehearsalStarted,
  Rung,
  TrustChange,
  TrustProposal,
  TuneResult,
} from "../../components/brief/types";

// Earned Autonomy - Brief API client (core/brief; as-built shapes in
// docs/plans/EARNED-AUTONOMY-CONTRACT.md section 6). Errors come back through
// toJson: `.message` is core's human sentence, `.status` the HTTP status and
// `.errors` the per-section list on a 422.

const enc = encodeURIComponent;

export interface ApplyOptions {
  /** true: returns at once with a jobId to poll; otherwise the call runs to completion. */
  background?: boolean;
  /** A tuned manifest to save as the next version and apply. It can never change the rung. */
  brief?: Brief;
  /** With `brief`: refuse (409) if the brief moved on since the proposal was made. */
  baseVersion?: number;
  note?: string;
}

export const briefsApi = {
  /** Non-streaming plan. */
  planBrief: async (req: PlanRequest): Promise<PlanResult> =>
    apiFetch("/api/briefs/plan", { method: "POST", ...jsonBody(req) }).then(toJson<PlanResult>),

  /**
   * SSE plan (`thinking`, `section`, `done`, `error`). Resolves with the final result;
   * `onEvent` sees every event as it arrives so the UI can stream the thinking lines.
   */
  planBriefStream: async (req: PlanRequest, onEvent: (e: PlanStreamEvent) => void, signal?: AbortSignal): Promise<PlanResult> => {
    const res = await apiFetch("/api/briefs/plan", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify(req),
      signal,
    });
    if (!res.ok) await toJson(res); // throws the friendly error
    const reader = res.body?.getReader();
    if (!reader) throw new Error("The planner did not stream a response.");
    const decoder = new TextDecoder();
    let buffer = "";
    let result: PlanResult | null = null;
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parsed = parseSse(buffer);
      buffer = parsed.rest;
      for (const ev of parsed.events) {
        onEvent(ev);
        if (ev.type === "error") throw new Error(ev.message || "The planner could not finish.");
        if (ev.type === "done") result = ev.result;
      }
    }
    if (!result) throw new Error("The planner stopped before it finished.");
    return result;
  },

  createBrief: async (brief: Brief, note?: string): Promise<{ id: string; version: number; status: string }> =>
    apiFetch("/api/briefs", { method: "POST", ...jsonBody({ brief, note }) }).then(toJson<{ id: string; version: number; status: string }>),

  /** With `entityId`, only the briefs bound to that entity - the server reads brief_binding instead of the client fetching every detail. */
  listBriefs: async (entityId?: string): Promise<BriefListItem[]> =>
    apiFetch(entityId ? `/api/briefs?entityId=${enc(entityId)}` : "/api/briefs").then(toJson<BriefListItem[]>),

  getBrief: async (id: string): Promise<BriefDetail> => apiFetch(`/api/briefs/${enc(id)}`).then(toJson<BriefDetail>),

  /** PUT: a new version of a DRAFT or FAILED brief. An applied brief changes through tune + apply. */
  updateBrief: async (id: string, brief: Brief, note?: string): Promise<{ id: string; version: number; status: string }> =>
    apiFetch(`/api/briefs/${enc(id)}`, { method: "PUT", ...jsonBody({ brief, note }) }).then(toJson<{ id: string; version: number; status: string }>),

  /** Recompute the readiness rows against the live vault, plan and registry. */
  briefReadiness: async (id: string): Promise<{ readiness: ReadinessRow[]; summary: Record<string, number> }> =>
    apiFetch(`/api/briefs/${enc(id)}/readiness`, { method: "POST" }).then(toJson<{ readiness: ReadinessRow[]; summary: Record<string, number> }>),

  /** 202 `{ jobId, status }` for background, else the finished ApplyResult (which also carries jobId). */
  applyBrief: async (id: string, opts: ApplyOptions = { background: true }): Promise<{ jobId: string }> =>
    apiFetch(`/api/briefs/${enc(id)}/apply`, { method: "POST", ...jsonBody(opts) }).then(toJson<{ jobId: string }>),

  getApplyJob: async (id: string, jobId: string): Promise<ApplyJob> =>
    apiFetch(`/api/briefs/${enc(id)}/apply/${enc(jobId)}`).then(toJson<ApplyJob>),

  tuneBrief: async (id: string, instruction: string): Promise<TuneResult> =>
    apiFetch(`/api/briefs/${enc(id)}/tune`, { method: "POST", ...jsonBody({ instruction }) }).then(toJson<TuneResult>),

  rehearseBrief: async (id: string): Promise<RehearsalStarted> =>
    apiFetch(`/api/briefs/${enc(id)}/rehearse`, { method: "POST" }).then(toJson<RehearsalStarted>),

  listRehearsals: async (id: string): Promise<RehearsalSession[]> =>
    apiFetch(`/api/briefs/${enc(id)}/rehearsals`).then(toJson<RehearsalSession[]>),

  trustProposal: async (id: string): Promise<TrustProposal> =>
    apiFetch(`/api/briefs/${enc(id)}/trust/proposal`, { method: "POST" }).then(toJson<TrustProposal>),

  setTrustRung: async (id: string, rung: Rung): Promise<TrustChange> =>
    apiFetch(`/api/briefs/${enc(id)}/trust/${enc(rung)}`, { method: "POST" }).then(toJson<TrustChange>),

  /** 200 ApplyResult, or 202 `{ jobId, status }` with background. */
  rollbackBrief: async (id: string, version: number, opts: { background?: boolean } = {}): Promise<ApplyJob | { jobId: string; status: string }> =>
    apiFetch(`/api/briefs/${enc(id)}/versions/${version}/rollback`, { method: "POST", ...jsonBody(opts) }).then(toJson<ApplyJob | { jobId: string; status: string }>),

  getBriefLedger: async (id: string): Promise<BriefLedger> => apiFetch(`/api/briefs/${enc(id)}/ledger`).then(toJson<BriefLedger>),
};
