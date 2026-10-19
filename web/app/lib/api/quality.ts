import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import { isKnownMissing, rememberMissing } from "./negativeCache";
import type { CalibrationResultRow, CalibrationSetSummary } from "../../agents/quality/judgeCalibration";
import type { QualitySummaryResponse, QualityHistoryResponse, QualityStatisticsResponse, QualityConfigResponse, EvalReliabilityReport, PairedComparisonResponse, DatasetNoiseFloorResponse, EvalGateStatus } from "./types";

/**
 * Whether GET .../quality/summary can have anything to say. It answers 404
 * when the agent has no evaluations, while history answers 200 with empty
 * lists for the same state - so an empty history means "do not ask". A history
 * that failed to load (null) proves nothing, so the summary is still tried.
 */
export function hasQualityRecords(history: QualityHistoryResponse | null | undefined): boolean {
  if (!history) return true;
  if (typeof history.recordCount === "number") return history.recordCount > 0;
  return Array.isArray(history.timestamps) && history.timestamps.length > 0;
}


// The judge-calibration group answers 404 on a deployment that has it switched off
// (app.quality.eval.calibration.enabled), and the answer cannot change while the tab is open. A browser logs every
// 404 however quietly it is handled, so the first one is remembered for the session (negativeCache.ts) and later
// reads reject with the same 404 without asking again.
const CALIBRATION_OFF_KEY = "judge-calibration";

function calibrationRead<T>(path: string): Promise<T> {
  if (isKnownMissing(CALIBRATION_OFF_KEY)) {
    return Promise.reject(Object.assign(new Error("Not found"), { status: 404 }));
  }
  return apiFetch(path)
    .then(toJsonQuietOn<T>([403, 404]))
    .catch((err) => {
      if (err?.status === 404) rememberMissing(CALIBRATION_OFF_KEY);
      throw err;
    });
}

export const qualityApi = {
  // Agent Quality panel (QualityController, core/quality) - every endpoint
  // here 403s for FREE-plan tenants (TenantLimitExceededException, "upgrade
  // your plan" message - see that controller's assertPremiumPlan()), so all
  // four route through toJsonQuietOn([403]) same as the LLM-usage endpoints
  // above: an expected plan-gate state, not a failure, so no global error
  // toast on every panel load. AgentQualityPanel.tsx catches the thrown
  // error and reads err.status === 403 to render its own inline upgrade
  // card. getSummary additionally quiets 404 - the service's own "no
  // evaluations recorded for this agent yet" response, not a failure either.
  getAgentQualitySummary: (agentId: string): Promise<QualitySummaryResponse | null> =>
    apiFetch(`/api/agents/${agentId}/quality/summary`).then(toJsonQuietOn([403, 404])),
  getAgentQualityHistory: (agentId: string, limit = 100): Promise<QualityHistoryResponse | null> =>
    apiFetch(`/api/agents/${agentId}/quality/history?limit=${limit}`).then(toJsonQuietOn([403])),
  getAgentQualityStatistics: (agentId: string): Promise<QualityStatisticsResponse | null> =>
    apiFetch(`/api/agents/${agentId}/quality/statistics`).then(toJsonQuietOn([403])),
  // Which eval types are enabled for the tenant's real plan tier, echoed
  // back with the resolved plan name - also 403s on FREE (see above), so any
  // caller that gets a body is PREMIUM or UNLIMITED.
  getAgentQualityConfig: (agentId: string): Promise<QualityConfigResponse | null> =>
    apiFetch(`/api/agents/${agentId}/quality/config`).then(toJsonQuietOn([403])),

  // Eval datasets + regression runs (EvalDatasetController, core/quality,
  // migration V213) - same PREMIUM+ gate (assertPremiumPlan) as the quality
  // endpoints above, so every read routes through toJsonQuietOn([403]): a
  // FREE-plan 403 is an expected state the panel renders inline, exactly
  // like getAgentQualitySummary. Writes stay on plain toJson - a failed
  // mutation deserves the global toast (including the 409 the backend
  // answers for delete/trigger while a run is live).
  // web/app/agents/quality/evalDatasetsApi.ts FEATURE-DETECTS exactly these
  // twelve method names (resolveEvalApi()) and lights the whole evals
  // surface up now that they exist - rename in both places or not at all.
  listEvalDatasets: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets`).then(toJsonQuietOn([403])),
  createEvalDataset: (agentId: string, payload: object) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  getEvalDataset: (agentId: string, datasetId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}`).then(toJsonQuietOn([403])),
  updateEvalDataset: (agentId: string, datasetId: string, payload: object) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  deleteEvalDataset: (agentId: string, datasetId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}`, { method: "DELETE" }).then(toJson),
  addEvalDatasetCase: (agentId: string, datasetId: string, payload: object) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/cases`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  updateEvalDatasetCase: (agentId: string, datasetId: string, caseId: string, payload: object) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/cases/${caseId}`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  deleteEvalDatasetCase: (agentId: string, datasetId: string, caseId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/cases/${caseId}`, { method: "DELETE" }).then(toJson),
  // triggerEvalRun accepts optional V343 run options (judgeModel override,
  // strictJudge self-grading guard, repeats 1-5, budgetUsd default $5) and an
  // idempotencyKey sent as the Idempotency-Key header (the CI contract: a
  // retried trigger returns the same run row, never a duplicate or a 409).
  triggerEvalRun: (
    agentId: string,
    datasetId: string,
    options?: { judgeModel?: string; strictJudge?: boolean; repeats?: number; budgetUsd?: number; idempotencyKey?: string }
  ) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs`, {
      method: "POST",
      ...(options && Object.values(options).some((v) => v !== undefined && v !== null && v !== "")
        ? {
            ...jsonBody({
              judgeModel: options.judgeModel || undefined,
              strictJudge: options.strictJudge === true ? true : undefined,
              repeats: options.repeats,
              budgetUsd: options.budgetUsd,
            }),
            headers: {
              "Content-Type": "application/json",
              ...(options.idempotencyKey ? { "Idempotency-Key": options.idempotencyKey } : {}),
            },
          }
        : {}),
    }).then(toJson),
  listEvalRuns: (agentId: string, datasetId: string, limit = 25) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs?limit=${limit}`).then(toJsonQuietOn([403])),
  getEvalRun: (agentId: string, datasetId: string, runId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs/${runId}`).then(toJsonQuietOn([403])),
  cancelEvalRun: (agentId: string, datasetId: string, runId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs/${runId}/cancel`, { method: "POST" }).then(toJson),
  // JUnit XML for a run (V343 CI hook) - text, not JSON: toJson on a 2xx
  // would fail parsing, so errors go through toJson (shared toast + throw)
  // and only a 2xx reaches res.text().
  getEvalRunJunit: (agentId: string, datasetId: string, runId: string): Promise<string> =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs/${runId}/junit`).then(async (res) => {
      if (!res.ok) {
        await toJson(res); // throws with the shared error handling
      }
      return res.text();
    }),
  // NDJSON run export (V395 work) - the optimizer-friendly counterpart of the
  // audit bundle: header line + one JSON per case. Same text-not-JSON shape
  // as getEvalRunJunit: only a 2xx reaches res.text().
  getEvalRunJsonl: (agentId: any, datasetId: any, runId: any): Promise<string> =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/runs/${runId}/export.jsonl`).then(async (res) => {
      if (!res.ok) {
        await toJson(res);
      }
      return res.text();
    }),
  getEvalDatasetJsonl: (agentId: any, datasetId: any): Promise<string> =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/export.jsonl`).then(async (res) => {
      if (!res.ok) {
        await toJson(res);
      }
      return res.text();
    }),

  // Promote gate (EvalGateController, core/quality, V260/V343) - binds PRODUCTION
  // promotion to a qualifying PASSED run on the exact config AND dataset
  // snapshot hash. Reads quiet on 403/404 (older core without the gate, or a
  // FREE tenant - both render inline); writes stay loud.
  getEvalGate: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/gate`).then(toJsonQuietOn<EvalGateStatus>([403, 404])),
  upsertEvalGate: (
    agentId: string,
    payload: {
      datasetId: string;
      enabled?: boolean;
      maxRunAgeHours?: number | null;
      // V394 simulation leg - opt-in: when true, promote also requires the
      // latest judged simulation session on this exact config to be all-PASSED.
      requireSimulation?: boolean;
      maxSimulationAgeHours?: number | null;
    }
  ) =>
    apiFetch(`/api/agents/${agentId}/quality/gate`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  deleteEvalGate: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/gate`, { method: "DELETE" }).then(toJson),
  checkEvalGate: (agentId: string, versionId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/gate/check?versionId=${versionId}`).then(toJsonQuietOn([403, 404])),
  // Evals hooks (2026-09-09) - two endpoints newer than the twelve above,
  // landing in core/quality alongside this change. Both route 404 through
  // toJsonQuietOn so an OLDER core (route not mounted yet) is an expected
  // state the caller feature-detects on (err.status === 404 -> hide the
  // control), never a global "couldn't be found" toast on every chat turn.
  // 403 stays quiet for the same FREE-plan reason as every read above.
  //
  // importEvalDatasetCase: POST .../cases/import { sessionId, turnRef } - the
  // backend reads the persisted user message + assistant reply for that turn
  // (the same agent+session+turnRef triple TurnFeedback keys RL reward on)
  // and creates a dataset case from it. userMessage/agentResponse ride along
  // as what the browser rendered, so a core that cannot resolve the turnRef
  // (an edge-answered turn, or a session that was never persisted) still
  // has the text to fall back on.
  importEvalDatasetCase: (
    agentId: string,
    datasetId: string,
    payload: { sessionId: string | null; turnRef: string; userMessage?: string; agentResponse?: string }
  ) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/${datasetId}/cases/import`, {
      method: "POST",
      ...jsonBody({
        sessionId: payload.sessionId || undefined,
        turnRef: payload.turnRef,
        userMessage: payload.userMessage || undefined,
        agentResponse: payload.agentResponse || undefined,
      }),
    }).then(toJsonQuietOn([403, 404])),
  // seedEvalDatasetFromGolden: POST .../datasets/seed-golden - copies
  // core/quality's golden-dataset.json cases into a new dataset for this agent
  // so a tenant starts from a real regression set instead of an empty
  // editor. Returns the created DatasetResponse.
  seedEvalDatasetFromGolden: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/quality/datasets/seed-golden`, { method: "POST" }).then(toJsonQuietOn([403, 404])),

  // Eval reliability statistics (EvalReliabilityController, core/quality's new
  // `stats` package) - the two endpoints that turn a bare 0.62 into
  // `0.62 [0.48-0.75], n=5`. Both are reads; the controller computes on read
  // and stores nothing, deliberately (an endpoint that could trigger k
  // rollouts is an endpoint that can spend a tenant's model budget).
  //
  // Quiet on 403 AND 404 for two different expected states:
  //   403 -> the same PREMIUM+ gate as every quality/eval read above
  //          (EvalReliabilityController#assertPremiumPlan), rendered inline by
  //          the caller, never a toast.
  //   404 -> this core predates the `stats` package. It is brand-new work, so
  //          most deployments answer 404 today and the caller must feature-
  //          detect on err.status === 404 and hide the surface rather than
  //          claim the agent has no statistics.
  // Note what a 200 does NOT mean: an agent with no replicated runs answers
  // 200 with an EMPTY `datasets` list and populated `warnings`. "We have
  // nothing replicated yet" is the state the console most needs to show, so it
  // arrives as data, not as an error.
  //
  // NOT to be confused with getAgentReliability above, which is the agent's
  // run-health summary (call counts, failure rate) on the bare
  // /api/agents/{id}/reliability path - a different endpoint and a different
  // sense of the word. This one is nested under /quality/ and is statistical.
  // getEvalReliability pools each dataset's recent SUCCEEDED runs (one
  // rollout per case per run) - the unit is the DATASET, not the run, and
  // maxRuns is the pooling window (clamped server-side).
  getEvalReliability: (agentId: string, maxRuns?: number): Promise<EvalReliabilityReport | null> =>
    apiFetch(
      `/api/agents/${agentId}/quality/reliability` + (maxRuns ? `?maxRuns=${maxRuns}` : "")
    ).then(toJsonQuietOn([403, 404])),
  // Paired A/B of two SUCCEEDED runs of the SAME dataset (the backend derives
  // the dataset from the runs - there is no datasetId in this path). The
  // response's `verdict` is allowed to be CANNOT_TELL / INSIDE_NOISE_FLOOR /
  // INSUFFICIENT_DATA and callers MUST render a winner for VARIANT_BETTER and
  // BASELINE_BETTER only; `explanation` is the server's own one-sentence
  // reason and is meant to be shown verbatim.
  compareEvalRunsPaired: (
    agentId: string,
    baselineRunId: string,
    variantRunId: string
  ): Promise<PairedComparisonResponse | null> =>
    apiFetch(
      `/api/agents/${agentId}/quality/reliability/compare` +
        `?baselineRunId=${encodeURIComponent(baselineRunId)}` +
        `&variantRunId=${encodeURIComponent(variantRunId)}`
    ).then(toJsonQuietOn([403, 404])),
  // The third endpoint of the same controller: the measured noise floor for
  // ONE dataset (the floor is also nested inside each getEvalReliability
  // dataset entry - this route answers it standalone, with `warnings` saying
  // WHY it is absent or suspect when it is, which the nested copy does not
  // carry). datasetId is required server-side. Same 403/404 quiet statuses
  // as the two above - plan gate and not-on-this-core are both expected.
  getDatasetNoiseFloor: (
    agentId: any,
    datasetId: any
  ): Promise<DatasetNoiseFloorResponse | null> =>
    apiFetch(
      `/api/agents/${agentId}/quality/reliability/noise-floor` +
        `?datasetId=${encodeURIComponent(datasetId)}`
    ).then(toJsonQuietOn([403, 404])),

  // Trajectory evals (TrajectoryEvalController, core/quality; behind
  // app.quality.trajectory-eval.enabled, default off). Reads are quiet on
  // 403 (Premium gate) and 404 (feature off / older core): the surface simply
  // hides. The write is quiet on the statuses the editor shows inline (400
  // invalid spec, 409 the case already has a response grader).
  getTrajectoryEvalConfig: (agentId: any) =>
    apiFetch(`/api/agents/${agentId}/quality/trajectory-eval/config`).then(toJsonQuietOn([403, 404])),
  getTrajectoryEvalCase: (agentId: any, datasetId: any, caseId: any) =>
    apiFetch(`/api/agents/${agentId}/quality/trajectory-eval/datasets/${datasetId}/cases/${caseId}`).then(
      toJsonQuietOn([403, 404])
    ),
  setTrajectoryEvalCase: (agentId: any, datasetId: any, caseId: any, trajectory: unknown) =>
    apiFetch(`/api/agents/${agentId}/quality/trajectory-eval/datasets/${datasetId}/cases/${caseId}`, {
      method: "PUT",
      ...jsonBody({ trajectory }),
    }).then(toJsonQuietOn([400, 404, 409])),
  getTrajectoryEvalRun: (agentId: any, datasetId: any, runId: any) =>
    apiFetch(`/api/agents/${agentId}/quality/trajectory-eval/datasets/${datasetId}/runs/${runId}`).then(
      toJsonQuietOn([403, 404])
    ),
  getTrajectoryEvalActual: (agentId: any, datasetId: any, runId: any, resultId: any) =>
    apiFetch(
      `/api/agents/${agentId}/quality/trajectory-eval/datasets/${datasetId}/runs/${runId}/results/${resultId}/actual`
    ).then(toJsonQuietOn([403, 404])),

  // Judge calibration sets (wave E, core/quality JudgeCalibrationSetController). The whole group answers
  // 404 unless app.quality.eval.calibration.enabled (and 403 on FREE), both expected STATES the panel hides
  // itself on - so reads and the run are quiet on [403, 404]; a 400 (a set below the minimum size, bad rows)
  // is a user-fixable validation message the panel renders inline, so writes are quiet on 400 as well.
  listJudgeCalibrationSets: (agentId: any) =>
    calibrationRead<CalibrationSetSummary[]>(`/api/agents/${agentId}/quality/judge-calibration-sets`),
  createJudgeCalibrationSet: (agentId: any, payload: { name: string; items: unknown[] }) =>
    apiFetch(`/api/agents/${agentId}/quality/judge-calibration-sets`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJsonQuietOn([400, 403, 404])),
  deleteJudgeCalibrationSet: (agentId: any, setId: any) =>
    apiFetch(`/api/agents/${agentId}/quality/judge-calibration-sets/${setId}`, { method: "DELETE" }).then(toJson),
  runJudgeCalibration: (agentId: any, calibrationSetId: any) =>
    apiFetch(`/api/agents/${agentId}/quality/judge-calibration`, {
      method: "POST",
      ...jsonBody({ calibrationSetId }),
    }).then(toJsonQuietOn([400, 403, 404])),
  listJudgeCalibrationHistory: (agentId: any, setId?: any, limit = 20) =>
    calibrationRead<CalibrationResultRow[]>(
      `/api/agents/${agentId}/quality/judge-calibration-history?limit=${limit}${setId ? `&setId=${setId}` : ""}`
    ),

  // Ecosystem Maintenance (cross-tenant super-admin console) - this entire
  // getPlatform*/create|update|deletePlatformHostedLlmProvider* group used
  // to live here, backing the tenant console's own /platform page. That
  // page has moved to the standalone web-admin/ app (see its own
  // app/lib/api.js for the ported copy of these same wrappers) so more
  // admin-only functionality can grow there without entangling this
  // tenant-facing console - nothing in web/ calls PlatformController's
  // endpoints anymore, so the wrappers were removed here rather than kept
  // as dead code.
};
