import { apiFetch, friendlyStatusFallback, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { LlmRequestSnapshot } from "../../components/agents/RequestInspector";
import type { AgentJobResponse, AgentResponse, AgentScheduleRunResponse, AgentSessionLlmCallEntry, AgentVersionResponse, RunCostSummary, SimulationPersona, SimulationSession, SimulationRun, SimulationRunDetail } from "./types";

export const agentLifecycleApi = {
  // ---- Agent jobs (core/agents/.../job) ----------------------------------
  // Agents that run WITHOUT a caller waiting: POST returns 202 with an id
  // immediately and the work continues after this request disconnects.
  //
  // Two list endpoints, deliberately not merged: listAgentJobs is scoped to
  // one agent, listTenantAgentJobs is the tenant-wide operator view.
  //
  // `status` is repeatable server-side and accepts the literal "live"
  // sentinel, which the server expands to its own AgentJobStatus.live() set
  // (QUEUED + RUNNING + AWAITING_APPROVAL). Always pass "live" rather than
  // listing those three here: the sentinel is what keeps this client from
  // drifting from the server's definition, and AWAITING_APPROVAL is
  // specifically NOT terminal (a job parked on a human approval gate is
  // still going) - hardcoding a list is exactly how it gets mistakenly
  // filed under "finished".
  submitAgentJob: (agentId: string, payload: { goal: string; timeoutSeconds?: number; resultSchemaJson?: string; maxToolCalls?: number }) =>
    apiFetch(`/api/agents/${agentId}/jobs`, { method: "POST", ...jsonBody(payload) }).then(toJson<AgentJobResponse>),
  listAgentJobs: (agentId: string, opts: { status?: string[]; limit?: number; offset?: number } = {}) =>
    apiFetch(`/api/agents/${agentId}/jobs${agentJobQuery(opts)}`).then(toJson),
  getAgentJob: (agentId: string, jobId: string) =>
    apiFetch(`/api/agents/${agentId}/jobs/${jobId}`).then(toJson<AgentJobResponse>),
  // Idempotent server-side: cancelling an already-finished job returns its
  // current row with 200, not a 409 - so the button never needs to guard
  // against a job that finished between render and click.
  cancelAgentJob: (agentId: string, jobId: string) =>
    apiFetch(`/api/agents/${agentId}/jobs/${jobId}/cancel`, { method: "POST" }).then(toJson<AgentJobResponse>),
  // NOTE the paging contract (AgentJobController#listForTenant): rows the
  // caller may not view are filtered out AFTER paging, so a full page can
  // come back short - or empty - without meaning the history has ended.
  // Callers MUST page on the offset they requested, never on "did I get a
  // full page?".
  listTenantAgentJobs: (opts: { status?: string[]; agentId?: string; limit?: number; offset?: number } = {}) =>
    apiFetch(`/api/agent-jobs${agentJobQuery(opts)}`).then(toJson),
  // GET /api/agent-jobs/counts - live counts (queued / running / awaiting
  // approval, as of now) plus terminal counts over `window` (ISO-8601
  // duration, clamped server-side to [1 minute, 30 days] and ECHOED back with
  // windowStart / asOf - caption from the echo, not from what was asked).
  // Quiet on 403/404/405 so a card on an older core hides instead of toasting.
  getAgentJobCounts: (agentId: string, window?: string) => {
    const params = new URLSearchParams({ agentId });
    if (window) params.set("window", window);
    return apiFetch(`/api/agent-jobs/counts?${params.toString()}`).then(toJsonQuietOn([403, 404, 405]));
  },

  getAgentJobProgress: (agentId: string, jobId: string, after?: string) =>
    apiFetch(`/api/agents/${agentId}/jobs/${jobId}/progress${after ? `?after=${encodeURIComponent(after)}` : ""}`).then(toJson),
  getAgentJobTrace: (agentId: string, jobId: string, after?: string) =>
    apiFetch(`/api/agents/${agentId}/jobs/${jobId}/trace${after ? `?after=${encodeURIComponent(after)}` : ""}`).then(toJson<unknown[]>),

  // Live trace. This is a GET, so the browser's own EventSource would be the
  // obvious tool - except EventSource cannot set an Authorization header, and
  // every call here is bearer-authenticated. So it reads the fetch body as a
  // stream and splits on "data:" lines by hand, exactly as runQuickTaskStream
  // above already does for its POST stream.
  //
  // The server closes the stream itself once the job reaches a terminal state
  // (after one final catch-up poll, so "done" can never arrive before the
  // entries that led to it). Returns an abort function for the caller's
  // cleanup - without it, navigating away leaves the reader attached and the
  // server-side poller alive until its own max-duration cap.
  // Generic over the caller's entry type - each data: line is JSON.parse'd and
  // handed over unvalidated (see JobDetail's trace entry shape).
  streamAgentJobTrace: <E = unknown>(agentId: string, jobId: string, onEntry: (entry: E) => void) => {
    const controller = new AbortController();
    const run = async () => {
      const res = await apiFetch(`/api/agents/${agentId}/jobs/${jobId}/trace/stream`, {
        signal: controller.signal,
        headers: { Accept: "text/event-stream" },
      });
      if (!res.ok || !res.body) {
        throw new Error(friendlyStatusFallback(res.status));
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
       
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";
        for (const line of lines) {
          if (!line.startsWith("data:")) continue;
          const raw = line.slice(5).replace(/^ /, "");
          if (!raw) continue;
          try {
            onEntry(JSON.parse(raw));
          } catch {
            // One malformed line must not kill the stream - skip it.
          }
        }
      }
    };
    return { done: run(), abort: () => controller.abort() };
  },

  // Result schema (per AGENT, snapshotted onto each job at enqueue - clearing
  // it never retroactively changes a job already in flight). GET 404s when
  // unset, which is a real answer ("no schema"), not an error - hence the
  // quiet-on-404 handling at the call site.
  getAgentResultSchema: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/result-schema`).then(toJsonQuietOn([404])),
  setAgentResultSchema: (agentId: string, schemaJson: string) =>
    apiFetch(`/api/agents/${agentId}/result-schema`, { method: "PUT", ...jsonBody({ schemaJson }) }).then(toJson),
  clearAgentResultSchema: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/result-schema`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),

  // Agent cron trigger. Shaped like the workflow schedule endpoints above on
  // purpose (PUT upserts, DELETE disables but KEEPS the configuration, GET
  // reads) - the server comment says an operator who has configured one has
  // already learned the other.
  //
  // GET 404 means "no schedule at all", which is distinct from a configured
  // schedule that is merely disabled (200 with enabled:false). The UI needs
  // that difference: one offers "add a schedule", the other "re-enable".
  getAgentSchedule: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/schedule`).then(toJsonQuietOn([404])),
  // Exactly one of cron / intervalSeconds (>= 60). targetType defaults to AGENT;
  // WORKFLOW requires workflowId and then goalTemplate is the run's input text.
  upsertAgentSchedule: (
    agentId: string,
    payload: {
      cron?: string;
      intervalSeconds?: number;
      timezone?: string;
      goalTemplate: string;
      targetType?: "AGENT" | "WORKFLOW";
      workflowId?: string;
      // Sentinel mode (V413): AGENT targets only - the cadence becomes a watch
      // brief whose ```sentinel verdict the backend sweep turns into a
      // notification or a parked agent.proposal approval.
      sentinel?: boolean;
    }
  ) => apiFetch(`/api/agents/${agentId}/schedule`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  disableAgentSchedule: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/schedule`, { method: "DELETE" }).then(toJson),
  resumeAgentSchedule: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/schedule/resume`, { method: "POST" }).then(toJson),
  // Fires once now through the same guards as the tick. A refusal comes back
  // 200 with status "SKIPPED" and the reason in `error`, not as an HTTP error.
  runAgentScheduleNow: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/schedule/run-now`, { method: "POST" }).then(toJson<AgentScheduleRunResponse>),
  listAgentScheduleRuns: (agentId: string, limit = 20) =>
    apiFetch(`/api/agents/${agentId}/schedule/runs?limit=${limit}`).then(toJson<AgentScheduleRunResponse[]>),

  // Agent configuration versions (AgentVersionController, migration V212 +
  // the V260 eval gate). Snapshots are taken SERVER-SIDE on every save, so
  // there is deliberately no "create version" call here - this surface is
  // list / inspect / restore / label only.
  //
  // Labels are pointers, not deploys: STAGING/PRODUCTION mark which snapshot
  // a tenant considers known-good; they do NOT route traffic (see
  // AgentVersionLabel's javadoc). Restore is the only action that changes
  // what the agent does - and it appends a new version row, which is what
  // makes a restore itself undoable.
  //
  // A PRODUCTION promote can come back 412 when the tenant's eval gate has
  // no qualifying passing run for this exact configuration - the thrown
  // error carries the full gate report on err.gate (see toJson above). A
  // caller holding MANAGE_AGENTS may then retry with force:true + reason;
  // the override is audited server-side. listVersions/getAgentVersion are
  // deliberately NOT edit-locked (same read-open posture as the prompt-only
  // /prompt/versions history); restore/setLabel take the same EDITOR +
  // edit-lock gate every other mutating agent endpoint does.
  listAgentVersions: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/versions`).then(toJson<AgentVersionResponse[]>),
  // The one call that returns the decoded config bundle - list rows omit it
  // on purpose (a changelog row doesn't need several KB of JSON each).
  getAgentVersion: (agentId: string, versionId: string) =>
    apiFetch(`/api/agents/${agentId}/versions/${versionId}`).then(toJson<AgentVersionResponse>),
  // Answers with the restored agent itself (AgentResponse), not a version row.
  restoreAgentVersion: (agentId: string, versionId: string) =>
    apiFetch(`/api/agents/${agentId}/versions/${versionId}/restore`, { method: "POST" }).then(toJson<AgentResponse>),
  // label: "STAGING" | "PRODUCTION" | null (null CLEARS the label - see
  // AgentVersionLabelUpdateRequest's "null is a target state" convention).
  setAgentVersionLabel: (
    agentId: string,
    versionId: string,
    label: "STAGING" | "PRODUCTION" | null,
    opts?: { force?: boolean; reason?: string }
  ) =>
    apiFetch(`/api/agents/${agentId}/versions/${versionId}/label`, {
      method: "PUT",
      ...jsonBody({ label, force: opts?.force, reason: opts?.reason }),
    }).then(toJson),

  // C1 versioning actions (AgentVersionController): promote with optional
  // force + reason (same gate as setAgentVersionLabel), rollback PRODUCTION to
  // the previous version (409 when there is none), and a field-level diff
  // between two version ordinals.
  promoteAgentVersion: (
    agentId: string,
    versionId: string,
    label: "STAGING" | "PRODUCTION",
    opts?: { force?: boolean; reason?: string }
  ) =>
    apiFetch(`/api/agents/${agentId}/versions/${versionId}/promote`, {
      method: "POST",
      ...jsonBody({ label, force: opts?.force, reason: opts?.reason }),
    }).then(toJson<AgentVersionResponse>),
  rollbackAgentVersion: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/versions/rollback`, { method: "POST" }).then(toJson<AgentVersionResponse>),
  diffAgentVersions: (agentId: string, from: number, to: number) =>
    apiFetch(`/api/agents/${agentId}/versions/diff?from=${from}&to=${to}`).then(
      toJson<{
        from: AgentVersionResponse;
        to: AgentVersionResponse;
        fields: { field: string; from: unknown; to: unknown }[];
        unchanged: boolean;
      }>
    ),

  // Per-call LLM usage for ONE chat session (AgentSessionLlmUsageController,
  // core/chat) - rows off the tenant's own llm_call_log filtered to this
  // agent: tokens/cost/latency per real provider call. This is what lets
  // the playground metrics row show real per-turn numbers: the caller diffs
  // the ascending-by-startedAt list against the count it saw before its
  // turn started. Authenticated like every other /api/agents/** read -
  // deliberately NOT the admin PERM_VIEW_USAGE surface, which serves a
  // different audience. toJsonQuietOn([403]): FREE-plan tenants get 403
  // (same gate as the admin usage surface), which is an entitlement state
  // the metrics row degrades around, not an error worth a toast.
  getAgentSessionLlmUsage: (agentId: string, sessionId: string) =>
    apiFetch(`/api/agents/${agentId}/sessions/${encodeURIComponent(sessionId)}/llm-usage`).then(toJsonQuietOn<AgentSessionLlmCallEntry[]>([403])),
  // One run's cost in a single read (RunCostTimeline.Summary: totals, cache
  // ratio, per-call steps with cumulative cost) - same controller, same
  // PREMIUM gate. Quiet on 403 (FREE plan: an entitlement state) and 404
  // (older core without the route); the cost card hides itself on both.
  // skipGetCache: it is re-read right after each turn and must not be served
  // the pre-turn copy.
  getSessionLlmUsageSummary: (agentId: string, sessionId: string): Promise<RunCostSummary> =>
    apiFetch(`/api/agents/${agentId}/sessions/${encodeURIComponent(sessionId)}/llm-usage/summary`, {
      skipGetCache: true,
    }).then(toJsonQuietOn<RunCostSummary>([403, 404])),
  // Exactly what each model call of ONE chat session was sent (final prompt,
  // sampling options, tool list) - AgentSessionLlmRequestController in
  // core/chat, an in-memory tenant-scoped ring. Quiet on failure: the
  // playground simply shows no inspector rather than an error toast.
  getAgentSessionLlmRequests: (agentId: string, sessionId: string) =>
    apiFetch(`/api/agents/${agentId}/sessions/${encodeURIComponent(sessionId)}/llm-requests`).then(toJsonQuietOn<LlmRequestSnapshot[]>([403, 404])),
  // A/B experiments on agent versions (traffic split between the live agent
  // and a hidden treatment clone cloned from one of its stored versions).
  listAgentExperiments: (agentId: string) => apiFetch(`/api/agents/${agentId}/experiments`).then(toJson),
  createAgentExperiment: (
    agentId: string,
    body: { name: string; treatmentVersionId: string; trafficPercent: number; autoStopErrorThresholdPct?: number }
  ) => apiFetch(`/api/agents/${agentId}/experiments`, { method: "POST", ...jsonBody(body) }).then(toJson),
  getAgentExperiment: (agentId: string, experimentId: string) =>
    apiFetch(`/api/agents/${agentId}/experiments/${experimentId}`).then(toJson),
  startAgentExperiment: (agentId: string, experimentId: string) =>
    apiFetch(`/api/agents/${agentId}/experiments/${experimentId}/start`, { method: "POST" }).then(toJson),
  stopAgentExperiment: (agentId: string, experimentId: string, reason?: string) =>
    apiFetch(`/api/agents/${agentId}/experiments/${experimentId}/stop`, {
      method: "POST",
      ...jsonBody({ reason }),
    }).then(toJson),
  getAgentExperimentMetrics: (agentId: string, experimentId: string) =>
    apiFetch(`/api/agents/${agentId}/experiments/${experimentId}/metrics`).then(toJson),

  // Simulation / adversarial persona testing.
  // quietOn: statuses the CALLER treats as an expected state and handles
  // itself (the attack section hides on a plan 403 / older-core 404), so they
  // raise no global toast. Omitted = the usual toast.
  listSimulationPersonas: (agentId: string, quietOn?: number[]) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/personas`).then(
      quietOn ? toJsonQuietOn<SimulationPersona[]>(quietOn) : toJson<SimulationPersona[]>,
    ),
  createSimulationPersona: (
    agentId: string,
    body: { key: string; name: string; description: string; objective: string }
  ) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/personas`, { method: "POST", ...jsonBody(body) }).then(
      toJson<SimulationPersona>
    ),
  // budgetUsd (V344) is the per-run cost cap (default $10 server-side).
  createSimulationSession: (
    agentId: string,
    body: { personaKeys: string[]; maxTurns: number; budgetUsd?: number }
  ) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/sessions`, { method: "POST", ...jsonBody(body) }).then(
      toJson<SimulationSession>
    ),
  // Cooperative session cancel (V344): live runs observe the flag between
  // turns and end CANCELLED; a drained-on-cancel session reports CANCELLED.
  cancelSimulationSession: (agentId: string, sessionId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/sessions/${sessionId}/cancel`, {
      method: "POST",
    }).then(toJson<SimulationSession>),
  // Deletes a CUSTOM persona only - built-ins are platform coverage and the
  // backend answers 400 for them (V344).
  deleteSimulationPersona: (agentId: string, key: string) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/personas/${encodeURIComponent(key)}`, {
      method: "DELETE",
    }).then(toJson),
  listSimulationSessions: (agentId: string, quietOn?: number[]) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/sessions`).then(
      quietOn ? toJsonQuietOn<SimulationSession[]>(quietOn) : toJson<SimulationSession[]>,
    ),
  getSimulationSession: (agentId: string, sessionId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/sessions/${sessionId}`).then(toJson<SimulationSession>),
  listSimulationRuns: (agentId: string, sessionId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/sessions/${sessionId}/runs`).then(toJson<SimulationRun[]>),
  getSimulationRun: (agentId: string, runId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/runs/${runId}`).then(toJson<SimulationRunDetail>),
  // Attack-success-rate report for one simulation session (core/quality
  // AttackReportService) - computed from the session's attack-scenario runs
  // on every read. skipGetCache: it is polled while runs are live. Quiet on
  // 404: that is "scenarios switched off / older core / session gone", a
  // state the section hides on, not an error worth a toast. Quiet on 403 and
  // 5xx too: it is a background poll, and the section shows its own inline
  // line when the result cannot be loaded.
  getSimulationAttackReport: (agentId: string, sessionId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/simulations/sessions/${sessionId}/attack-report`, {
      skipGetCache: true,
    }).then(toJsonQuietOn([403, 404, 500, 502, 503, 504])),
};

// Shared query builder for the two job list endpoints. `status` is repeated
// rather than comma-joined because that is what the server binds
// (?status=RUNNING&status=QUEUED); a comma-joined value would arrive as one
// unknown status string and 400.
function agentJobQuery(opts: { status?: string[]; agentId?: string; limit?: number; offset?: number }): string {
  const params = new URLSearchParams();
  (opts.status ?? []).forEach((s) => params.append("status", s));
  if (opts.agentId) params.set("agentId", opts.agentId);
  if (opts.limit != null) params.set("limit", String(opts.limit));
  if (opts.offset != null) params.set("offset", String(opts.offset));
  const qs = params.toString();
  return qs ? `?${qs}` : "";
}

