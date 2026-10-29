"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { FlaskConical, Plus, RefreshCw, Sparkles } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { EmptyState, Section, SECTION_ACTION_GROUP_CLASS } from "../../components/ax/page-kit";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";
import {
  type EvalCaseResult,
  type EvalDatasetDetail,
  type EvalDatasetSummary,
  type EvalRunOptions,
  type EvalRunSummary,
  isEvalHookUnsupported,
  markEvalHookUnsupportedIf,
  resolveEvalApi,
  resolveEvalHooksApi,
} from "./evalDatasetsApi";
import CasesSection, { type CaseDraft } from "./evalDatasets/CasesSection";
import CreateDatasetForm, { type CreateDatasetDraft } from "./evalDatasets/CreateDatasetForm";
import DatasetModePanel from "./evalDatasets/DatasetModePanel";
import { autoApproveRefusal, modeForRequest, type DatasetModeValue } from "./evalDatasets/datasetMode";
import RunHistory from "./evalDatasets/RunHistory";
import RunOptionsPanel, { type RunOptionValues } from "./evalDatasets/RunOptionsPanel";
import { LIVE_STATUSES } from "./evalDatasets/evalBadges";
import { repeatsOption } from "./evalRunOptions";
import { DatasetCardHeader } from "./evalDatasets/DatasetCardHeader";
import { EvalDatasetsUnavailable } from "./evalDatasets/EvalDatasetsUnavailable";
import { useEvalDownloads } from "./evalDatasets/useEvalDownloads";

/**
 * Eval datasets + regression-run history for one agent — the offline half of
 * the quality surface (AgentQualityPanel above it is the online half). Owns
 * dataset CRUD, the golden-case editor, run triggering, and the run history
 * with verdicts and diff-vs-previous deltas.
 *
 * Colocated with the /agents/quality route (not under components/agents/)
 * because this workstream owns exactly this route folder — AgentQualityPanel's
 * folder belongs to other work in flight. Same thin-page/fat-panel division
 * as knowledge/page.tsx: the page passes an agentId, this panel owns its own
 * fetch + interaction state.
 *
 * i18n NOTE (cross-workstream): strings are literal English on purpose.
 * web/messages/en.json is actively edited by a concurrent workflow and the
 * t()-miss behavior renders raw dotted key paths — worse than English. Moving
 * these under a `agentEvalsSection` namespace once en.json is free is part of
 * the recorded follow-up, together with the api.ts client methods (see
 * evalDatasetsApi.ts).
 *
 * Load-failure vs empty is kept distinct throughout (the 2026-07-29 audit
 * rule: a failed fetch rendering an empty state "reads as data loss").
 */

/** Poll cadence while a run is live - cheap list call, matches other consoles. */
const POLL_MS = 5000;

// A new dataset pins no mode (null): its runs follow the workspace default, the full pipeline as shipped.
const EMPTY_CREATE_DRAFT: CreateDatasetDraft = {
  name: "",
  description: "",
  threshold: "0.7",
  toolExec: null,
  autoApprove: false,
};
const EMPTY_CASE_DRAFT: CaseDraft = { message: "", expected: "", tools: "" };
const DEFAULT_RUN_OPTS: RunOptionValues = { judgeModel: "", strictJudge: false, repeats: "1", budget: "5", mode: "" };

export default function EvalDatasetsSection({ agentId }: { agentId: string | null }) {
  // resolveEvalApi() is stable per bundle — memo once.
  const evalApi = useMemo(() => resolveEvalApi(), []);
  // Evals hooks (2026-09-09): seed-golden is feature-detected twice - the
  // client method (older web build -> null) and the core route (older core
  // -> 404 on first click -> hidden for good, see evalDatasetsApi.ts).
  const hooksApi = useMemo(() => resolveEvalHooksApi(), []);
  const [seedHidden, setSeedHidden] = useState(() => isEvalHookUnsupported("seedEvalDatasetFromGolden"));
  const [seeding, setSeeding] = useState(false);

  const [datasets, setDatasets] = useState<EvalDatasetSummary[]>([]);
  const [datasetsFailed, setDatasetsFailed] = useState(false);
  const [locked, setLocked] = useState(false); // FREE plan: backend 403s
  const [loading, setLoading] = useState(false);

  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<EvalDatasetDetail | null>(null);
  const [runs, setRuns] = useState<EvalRunSummary[]>([]);
  const [detailFailed, setDetailFailed] = useState(false);

  const [runDetailId, setRunDetailId] = useState<string | null>(null);
  const [caseResults, setCaseResults] = useState<EvalCaseResult[] | null>(null);
  // The tools the sandbox simulated in the open run, from its detail.
  const [simulatedTools, setSimulatedTools] = useState<string[]>([]);

  // Create-dataset form
  const [showCreate, setShowCreate] = useState(false);
  const [createDraft, setCreateDraft] = useState<CreateDatasetDraft>(EMPTY_CREATE_DRAFT);
  const [createRefusal, setCreateRefusal] = useState<string | null>(null);

  // Add-case form
  const [caseDraft, setCaseDraft] = useState<CaseDraft>(EMPTY_CASE_DRAFT);
  const [busy, setBusy] = useState(false);

  // Run options (V343) - applied to the NEXT triggered run; all optional
  // and default server-side (catalog judge, strictJudge off, 1 repeat, $5).
  const [showRunOptions, setShowRunOptions] = useState(false);
  const [runOpts, setRunOpts] = useState<RunOptionValues>(DEFAULT_RUN_OPTS);

  // Shared client-side pagination for the four unbounded record lists below:
  // the datasets list, one expanded dataset's cases, its run history, and one
  // open run's case results (each nested list has at most one owner expanded
  // at a time, so one hook per level suffices).
  const datasetsPagination = usePagination(datasets);
  const casesPagination = usePagination(detail?.cases || []);
  const runsPagination = usePagination(runs);
  const caseResultsPagination = usePagination(caseResults || []);
  const { setPage: setCasesPage } = casesPagination;
  const { setPage: setRunsPage } = runsPagination;
  useEffect(() => {
    setCasesPage(1);
    setRunsPage(1);
  }, [expandedId, setCasesPage, setRunsPage]);
  const { setPage: setCaseResultsPage } = caseResultsPagination;
  useEffect(() => {
    setCaseResultsPage(1);
  }, [runDetailId, setCaseResultsPage]);

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refreshDatasets = useCallback(async () => {
    if (!evalApi || !agentId) return;
    setLoading(true);
    try {
      const data = await evalApi.listEvalDatasets(agentId);
      setDatasets(data || []);
      setDatasetsFailed(false);
      setLocked(false);
    } catch (e: any) {
      if (e?.status === 403) {
        setLocked(true);
        setDatasetsFailed(false);
      } else {
        // Failure, NOT emptiness — keep whatever we had and show the banner.
        setDatasetsFailed(true);
      }
    } finally {
      setLoading(false);
    }
  }, [evalApi, agentId]);

  const refreshDetail = useCallback(
    async (datasetId: string) => {
      if (!evalApi || !agentId) return;
      try {
        const [d, r] = await Promise.all([
          evalApi.getEvalDataset(agentId, datasetId),
          evalApi.listEvalRuns(agentId, datasetId),
        ]);
        setDetail(d);
        setRuns(r || []);
        setDetailFailed(false);
      } catch {
        setDetailFailed(true);
      }
    },
    [evalApi, agentId]
  );

  useEffect(() => {
    setExpandedId(null);
    setDetail(null);
    setRuns([]);
    setRunDetailId(null);
    setCaseResults(null);
    void refreshDatasets();
  }, [agentId, refreshDatasets]);

  // Poll run history while any run is live, so verdicts appear without a
  // manual refresh. Interval is torn down the moment nothing is live.
  useEffect(() => {
    const hasLive = runs.some((r) => LIVE_STATUSES.includes(r.status));
    if (hasLive && expandedId && !pollRef.current) {
      pollRef.current = setInterval(() => {
        void refreshDetail(expandedId);
      }, POLL_MS);
    }
    if ((!hasLive || !expandedId) && pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [runs, expandedId, refreshDetail]);

  const toggleExpand = useCallback(
    (datasetId: string) => {
      if (expandedId === datasetId) {
        setExpandedId(null);
        setDetail(null);
        setRuns([]);
        setRunDetailId(null);
        setCaseResults(null);
        return;
      }
      setExpandedId(datasetId);
      setDetail(null);
      setRuns([]);
      setRunDetailId(null);
      setCaseResults(null);
      void refreshDetail(datasetId);
    },
    [expandedId, refreshDetail]
  );

  const createDataset = useCallback(async () => {
    if (!evalApi || !agentId || !createDraft.name.trim()) return;
    setBusy(true);
    try {
      await evalApi.createEvalDataset(agentId, {
        name: createDraft.name.trim(),
        description: createDraft.description.trim() || null,
        passThreshold: Number.isFinite(parseFloat(createDraft.threshold)) ? parseFloat(createDraft.threshold) : null,
        mode: modeForRequest({ toolExecutionEnabled: createDraft.toolExec, autoApproveTools: createDraft.autoApprove }),
        autoApproveTools: createDraft.toolExec !== false && createDraft.autoApprove,
      });
      setShowCreate(false);
      setCreateDraft(EMPTY_CREATE_DRAFT);
      setCreateRefusal(null);
      await refreshDatasets();
    } catch (e) {
      // api.ts's shared toJson already raised the global error toast. A refused auto-approval is also explained
      // next to its switch, which turns itself off and stays off with the server's sentence.
      const refused = autoApproveRefusal(e);
      if (refused) {
        setCreateRefusal(refused);
        setCreateDraft((prev) => ({ ...prev, autoApprove: false }));
      }
    } finally {
      setBusy(false);
    }
  }, [evalApi, agentId, createDraft, refreshDatasets]);

  // Saves a dataset's replay mode. The update endpoint takes the whole definition, so the rest is sent unchanged.
  const saveMode = useCallback(
    async (dataset: EvalDatasetSummary, mode: DatasetModeValue) => {
      if (!evalApi || !agentId) return;
      await evalApi.updateEvalDataset(agentId, dataset.id, {
        name: dataset.name,
        description: dataset.description,
        passThreshold: dataset.passThreshold,
        mode: modeForRequest(mode),
        autoApproveTools: mode.toolExecutionEnabled !== false && mode.autoApproveTools,
      });
      await refreshDatasets();
    },
    [evalApi, agentId, refreshDatasets]
  );

  // "Seed from golden set" - core copies core/quality's golden-dataset.json
  // cases into a fresh dataset for this agent, so a tenant's first regression
  // set is a real one rather than an empty editor. On success the new
  // dataset is expanded so the imported cases are immediately visible.
  const seedGolden = useCallback(async () => {
    if (!hooksApi || !agentId) return;
    setSeeding(true);
    try {
      const created = await hooksApi.seedEvalDatasetFromGolden(agentId);
      await refreshDatasets();
      if (created?.id) {
        setExpandedId(created.id);
        setDetail(null);
        setRuns([]);
        setRunDetailId(null);
        setCaseResults(null);
        await refreshDetail(created.id);
      }
    } catch (e) {
      // 404 = route not on this core, 403 = plan gate: hide the button, no
      // banner - a missing feature is not a failure. Anything else already
      // raised the global toast via api.ts's toJson.
      if (markEvalHookUnsupportedIf("seedEvalDatasetFromGolden", e)) setSeedHidden(true);
    } finally {
      setSeeding(false);
    }
  }, [hooksApi, agentId, refreshDatasets, refreshDetail]);

  const deleteDataset = useCallback(
    async (datasetId: string) => {
      if (!evalApi || !agentId) return;
      setBusy(true);
      try {
        await evalApi.deleteEvalDataset(agentId, datasetId);
        if (expandedId === datasetId) setExpandedId(null);
        await refreshDatasets();
      } catch {
        /* global toast already shown */
      } finally {
        setBusy(false);
      }
    },
    [evalApi, agentId, expandedId, refreshDatasets]
  );

  const addCase = useCallback(async () => {
    if (!evalApi || !agentId || !expandedId || !caseDraft.message.trim()) return;
    setBusy(true);
    try {
      await evalApi.addEvalDatasetCase(agentId, expandedId, {
        userMessage: caseDraft.message,
        expectedOutput: caseDraft.expected.trim() || null,
        expectedTools: caseDraft.tools
          .split(",")
          .map((t) => t.trim())
          .filter(Boolean),
      });
      setCaseDraft(EMPTY_CASE_DRAFT);
      await refreshDetail(expandedId);
      await refreshDatasets(); // case counts on the cards
    } catch {
      /* global toast already shown */
    } finally {
      setBusy(false);
    }
  }, [evalApi, agentId, expandedId, caseDraft, refreshDetail, refreshDatasets]);

  const deleteCase = useCallback(
    async (caseId: string) => {
      if (!evalApi || !agentId || !expandedId) return;
      try {
        await evalApi.deleteEvalDatasetCase(agentId, expandedId, caseId);
        await refreshDetail(expandedId);
        await refreshDatasets();
      } catch {
        /* global toast already shown */
      }
    },
    [evalApi, agentId, expandedId, refreshDetail, refreshDatasets]
  );

  const triggerRun = useCallback(
    async (datasetId: string) => {
      if (!evalApi || !agentId) return;
      setBusy(true);
      try {
        // V343 options - sent only when set, so a default run stays a
        // plain POST with no body (same as before this panel existed).
        const budgetUsd = parseFloat(runOpts.budget);
        const options: EvalRunOptions = {
          judgeModel: runOpts.judgeModel.trim() || undefined,
          strictJudge: runOpts.strictJudge || undefined,
          repeats: repeatsOption(runOpts.repeats),
          budgetUsd: Number.isFinite(budgetUsd) && budgetUsd !== 5 ? budgetUsd : undefined,
          // Sent only when this run names a mode; otherwise the dataset's pin, then the workspace default, apply.
          mode: runOpts.mode || undefined,
        };
        await evalApi.triggerEvalRun(agentId, datasetId, options);
        if (expandedId !== datasetId) {
          setExpandedId(datasetId);
        }
        await refreshDetail(datasetId);
      } catch {
        /* 409 (already running) / 400 (strictJudge, bounds) via the global toast */
      } finally {
        setBusy(false);
      }
    },
    [evalApi, agentId, expandedId, refreshDetail, runOpts]
  );

  const { downloadJunit, downloadRunJsonl, downloadDatasetJsonl } = useEvalDownloads(agentId, expandedId);

  const cancelRun = useCallback(
    async (runId: string) => {
      if (!evalApi || !agentId || !expandedId) return;
      try {
        await evalApi.cancelEvalRun(agentId, expandedId, runId);
        await refreshDetail(expandedId);
      } catch {
        /* global toast already shown */
      }
    },
    [evalApi, agentId, expandedId, refreshDetail]
  );

  const openRunDetail = useCallback(
    async (runId: string) => {
      if (!evalApi || !agentId || !expandedId) return;
      if (runDetailId === runId) {
        setRunDetailId(null);
        setCaseResults(null);
        setSimulatedTools([]);
        return;
      }
      setRunDetailId(runId);
      setCaseResults(null);
      setSimulatedTools([]);
      try {
        const d = await evalApi.getEvalRun(agentId, expandedId, runId);
        setCaseResults(d.caseResults || []);
        setSimulatedTools(d.simulatedTools || []);
      } catch {
        setCaseResults([]);
      }
    },
    [evalApi, agentId, expandedId, runDetailId]
  );

  // ------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------

  if (!agentId) return null;

  if (!evalApi) return <EvalDatasetsUnavailable reason="client-not-wired" />;

  if (locked) return <EvalDatasetsUnavailable reason="plan-locked" />;

  return (
    <Section
      title="Eval datasets & regression runs"
      description="Golden test cases replayed against this agent and scored by the same judges as live turns — so a prompt or model change has a before/after number."
      actions={
        <div className={SECTION_ACTION_GROUP_CLASS}>
          <Button variant="outline" size="sm" onClick={refreshDatasets} disabled={loading}>
            <RefreshCw size={14} className={loading ? "animate-spin" : undefined} aria-hidden="true" /> Refresh
          </Button>
          {hooksApi && !seedHidden && (
            <Button
              variant="outline"
              size="sm"
              onClick={seedGolden}
              disabled={seeding || busy}
              title="Create a dataset from the platform's built-in golden regression cases"
            >
              <Sparkles size={14} className={seeding ? "animate-pulse" : undefined} aria-hidden="true" />
              {seeding ? "Seeding…" : "Seed from golden set"}
            </Button>
          )}
          <Button size="sm" onClick={() => setShowCreate((v) => !v)}>
            <Plus size={14} aria-hidden="true" /> New dataset
          </Button>
        </div>
      }
    >
      {datasetsFailed && (
        // Failure banner — distinct from the empty state below on purpose.
        <Alert variant="destructive">
          <AlertTitle>Couldn&apos;t load eval datasets</AlertTitle>
          <AlertDescription className="flex items-center gap-3">
            This is a load failure, not an empty list.
            <Button variant="outline" size="sm" onClick={refreshDatasets}>
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {showCreate && (
        <CreateDatasetForm
          agentId={agentId}
          refusal={createRefusal}
          draft={createDraft}
          onChange={(patch) => setCreateDraft((prev) => ({ ...prev, ...patch }))}
          busy={busy}
          onCancel={() => {
            setShowCreate(false);
            setCreateRefusal(null);
          }}
          onCreate={createDataset}
        />
      )}

      {!datasetsFailed && datasets.length === 0 && !loading ? (
        <EmptyState
          icon={FlaskConical}
          title="No eval datasets yet"
          description="Create a dataset of golden questions (and optionally expected answers), then run it after every prompt or model change to catch regressions before your customers do."
          action={
            <div className="flex flex-wrap items-center justify-center gap-2">
              <Button size="sm" onClick={() => setShowCreate(true)}>
                <Plus size={14} aria-hidden="true" /> New dataset
              </Button>
              {hooksApi && !seedHidden && (
                <Button variant="outline" size="sm" onClick={seedGolden} disabled={seeding}>
                  <Sparkles size={14} aria-hidden="true" /> {seeding ? "Seeding…" : "Seed from golden set"}
                </Button>
              )}
            </div>
          }
        />
      ) : (
        <div className="space-y-3">
          {datasetsPagination.pageItems.map((dataset) => {
            const expanded = expandedId === dataset.id;
            return (
              <Card key={dataset.id}>
                <CardContent className="pt-4">
                  <DatasetCardHeader
                    dataset={dataset}
                    expanded={expanded}
                    busy={busy}
                    onToggleExpand={() => toggleExpand(dataset.id)}
                    onRun={() => triggerRun(dataset.id)}
                    onToggleRunOptions={() => setShowRunOptions((v) => !v)}
                    onExportJsonl={() => downloadDatasetJsonl(dataset.id)}
                    onDelete={() => deleteDataset(dataset.id)}
                  />
                  {dataset.description && (
                    <p className="mt-1 ps-6 text-sm text-muted-foreground">{dataset.description}</p>
                  )}

                  {expanded && (
                    <div className="mt-4 space-y-6 border-t border-border pt-4">
                      {detailFailed && (
                        <Alert variant="destructive">
                          <AlertTitle>Couldn&apos;t load this dataset</AlertTitle>
                          <AlertDescription className="flex items-center gap-3">
                            <Button variant="outline" size="sm" onClick={() => refreshDetail(dataset.id)}>
                              Retry
                            </Button>
                          </AlertDescription>
                        </Alert>
                      )}

                      {/* Replay mode: the workspace default, the full pipeline in the sandbox or quick text only, and sandbox auto-approval */}
                      <DatasetModePanel agentId={agentId} dataset={dataset} save={(mode) => saveMode(dataset, mode)} />

                      {/* Cases */}
                      <CasesSection
                        agentId={agentId}
                        dataset={dataset}
                        detail={detail}
                        pagination={casesPagination}
                        draft={caseDraft}
                        onDraftChange={(patch) => setCaseDraft((prev) => ({ ...prev, ...patch }))}
                        busy={busy}
                        onAddCase={addCase}
                        onDeleteCase={deleteCase}
                      />

                      {/* Run options (V343) - applied to the next Run click */}
                      {showRunOptions && (
                        <RunOptionsPanel
                          values={runOpts}
                          onChange={(patch) => setRunOpts((prev) => ({ ...prev, ...patch }))}
                        />
                      )}

                      {/* Run history */}
                      <RunHistory
                        agentId={agentId}
                        datasetId={dataset.id}
                        loaded={Boolean(detail)}
                        runs={runs}
                        runsPagination={runsPagination}
                        runDetailId={runDetailId}
                        caseResults={caseResults}
                        caseResultsPagination={caseResultsPagination}
                        onOpenRunDetail={openRunDetail}
                        onDownloadJunit={downloadJunit}
                        onDownloadRunJsonl={downloadRunJsonl}
                        onCancelRun={cancelRun}
                        simulatedTools={simulatedTools}
                      />
                    </div>
                  )}
                </CardContent>
              </Card>
            );
          })}
          <PaginationControls
            page={datasetsPagination.page}
            totalPages={datasetsPagination.totalPages}
            totalItems={datasetsPagination.totalItems}
            pageSize={datasetsPagination.pageSize}
            onPageChange={datasetsPagination.setPage}
            onPageSizeChange={datasetsPagination.setPageSize}
            itemLabel="datasets"
          />
        </div>
      )}
    </Section>
  );
}
