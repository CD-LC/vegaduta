"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { FlaskConical, Info, RefreshCw, ShieldAlert, Sigma } from "lucide-react";
import Select from "../../components/Select";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { EmptyState, Section } from "../../components/ax/page-kit";
import type { DatasetNoiseFloorResponse, EvalReliabilityReport } from "../../lib/api";
import type { EvalRunSummary } from "./evalDatasetsApi";
import { resolveEvalApi } from "./evalDatasetsApi";
import { isEvalStatsFeatureUnsupported, isPlanGate, markEvalStatsUnsupportedIf, resolveEvalStatsApi } from "./evalStatisticsApi";
import PairedComparisonCard from "./PairedComparisonCard";
import { DatasetReliabilityBlock, NoiseFloorCard, WarningsList } from "./EvalStatisticsBlocks";
import { useTranslations } from "../../lib/i18n";

/**
 * Honest eval statistics for one agent — the repeat-aware half of the quality
 * surface.
 *
 * THE PRINCIPLE THIS SECTION IMPLEMENTS
 * -------------------------------------
 * A single-run score is not a measurement. The panels above this one report
 * the latest scored turn and the latest run's overall score as bare numbers;
 * those are point estimates with n=1 behind them, and nothing in them can
 * distinguish a real regression from ordinary run-to-run variance. This
 * section renders the same underlying quantities as measurements instead:
 * point estimate, interval, and the number of INDEPENDENT observations behind
 * it (`0.62 [0.48–0.75], n=5`), with an explicit `unreplicated` badge whenever
 * that count is one.
 *
 * It has three blocks, in the order a reader needs them — all sourced from
 * core/quality's EvalReliabilityController (`/api/agents/{id}/quality/
 * reliability`, `/reliability/noise-floor`, `/reliability/compare`):
 *
 *  1. Reliability of a chosen dataset, pooled over its recent SUCCEEDED
 *     runs — pass@1 next to pass^k, plus the gap between them, which is the
 *     number an operations buyer cares about (pass@1: "can it ever do this";
 *     pass^k: "can I put it on a phone line"). The backend's unit of analysis
 *     is the DATASET, not one run: each run in the window contributes at
 *     most one rollout per case, and the report's `runIds` say exactly which
 *     runs were pooled.
 *  2. That dataset's measured noise floor — the paired disagreement between
 *     two runs of the UNCHANGED configuration. Without it, no small
 *     difference on this screen can be interpreted.
 *  3. Paired A/B of two runs of the same dataset, which is allowed to
 *     conclude "we cannot tell" (CANNOT_TELL) or "inside the noise floor".
 *
 * FAILURE POSTURE
 * ---------------
 * Three independent gates, each handled separately because they mean
 * different things and the wrong conflation produces a lie:
 *  - the shared api client lacks the three methods -> explicit "wiring
 *    pending" notice (see evalStatisticsApi.ts for the contract).
 *  - the core route answers 404/501               -> that block hides itself.
 *    A feature that is not deployed is not a failure.
 *  - the core answers 403                         -> PREMIUM+ plan gate, same
 *    inline upgrade posture as AgentQualityPanel's 403 branch.
 * Anything else is a LOAD FAILURE and gets a retry banner, never an empty
 * state: an empty interval would read as "no difference", which is a
 * conclusion we did not reach.
 *
 * i18n NOTE: literal English strings on purpose — see ScoreWithInterval.tsx's
 * header for the en.json cross-workstream reasoning.
 */

/**
 * The shared `Select` component types its handler loosely (an `any` event) so it
 * can stay a drop-in for a native <select> across ~13 call sites with
 * different value shapes (see Select.tsx's own note). A narrower parameter
 * type is still assignable to that signature, so this workstream keeps its own
 * call sites typed rather than inheriting the `any`.
 */
type SelectChangeEvent = { target: { value: string } };

export default function EvalStatisticsSection({ agentId }: { agentId: string | null }) {
    const tHC = useTranslations("hcAgentsQualityEvalStatisticsSection");
  // Both resolvers are stable per bundle — memo once. The run LISTING
  // contract is reused from evalDatasetsApi rather than redeclared, so there
  // is one definition of "how do I list runs" in this folder.
  const evalApi = useMemo(() => resolveEvalApi(), []);
  const statsApi = useMemo(() => resolveEvalStatsApi(), []);

  // Per-feature core availability. Seeded from the module-level memo so a
  // 404 discovered once stays discovered across remounts (agent switches).
  const [statsHidden, setStatsHidden] = useState(() => isEvalStatsFeatureUnsupported("getEvalReliability"));
  const [floorHidden, setFloorHidden] = useState(() => isEvalStatsFeatureUnsupported("getDatasetNoiseFloor"));
  const [compareHidden, setCompareHidden] = useState(() => isEvalStatsFeatureUnsupported("compareEvalRunsPaired"));

  const [locked, setLocked] = useState(false);

  // The pooled reliability report — the one fetch blocks 1+2 read from.
  const [report, setReport] = useState<EvalReliabilityReport | null>(null);
  const [reportFailed, setReportFailed] = useState(false);
  const [reportLoading, setReportLoading] = useState(false);

  // Dataset selection (the report only contains datasets with ≥1 SUCCEEDED
  // run — exactly the set statistics can be computed over).
  const [datasetId, setDatasetId] = useState<string | null>(null);

  // Run listing for the A/B pickers (block 3 only).
  const [runs, setRuns] = useState<EvalRunSummary[]>([]);
  const [runsFailed, setRunsFailed] = useState(false);

  const [floor, setFloor] = useState<DatasetNoiseFloorResponse | null>(null);
  const [floorFailed, setFloorFailed] = useState(false);

  const [onlyUnstable, setOnlyUnstable] = useState(false);

  // -----------------------------------------------------------------------
  // Loaders
  // -----------------------------------------------------------------------

  const loadReport = useCallback(async () => {
    if (!statsApi || !agentId) return;
    setReportLoading(true);
    setReportFailed(false);
    try {
      const data = await statsApi.getEvalReliability(agentId);
      setReport(data);
    } catch (err) {
      setReport(null);
      if (markEvalStatsUnsupportedIf("getEvalReliability", err)) {
        setStatsHidden(true);
      } else if (isPlanGate(err)) {
        setLocked(true);
      } else {
        setReportFailed(true);
      }
    } finally {
      setReportLoading(false);
    }
  }, [statsApi, agentId]);

  const loadRuns = useCallback(async () => {
    if (!evalApi || !agentId || !datasetId) return;
    try {
      const data = await evalApi.listEvalRuns(agentId, datasetId);
      setRuns(data || []);
      setRunsFailed(false);
    } catch (err) {
      if (isPlanGate(err)) {
        setLocked(true);
      } else {
        setRunsFailed(true);
      }
    }
  }, [evalApi, agentId, datasetId]);

  const loadFloor = useCallback(async () => {
    if (!statsApi || !agentId || !datasetId) return;
    setFloorFailed(false);
    try {
      const data = await statsApi.getDatasetNoiseFloor(agentId, datasetId);
      setFloor(data);
    } catch (err) {
      setFloor(null);
      if (markEvalStatsUnsupportedIf("getDatasetNoiseFloor", err)) {
        setFloorHidden(true);
      } else if (isPlanGate(err)) {
        setLocked(true);
      } else {
        setFloorFailed(true);
      }
    }
  }, [statsApi, agentId, datasetId]);

  // -----------------------------------------------------------------------
  // Effects — one per selection level, so switching an agent does not refetch
  // a floor that belongs to the previous agent's dataset.
  // -----------------------------------------------------------------------

  useEffect(() => {
    setDatasetId(null);
    setRuns([]);
    setFloor(null);
    setReport(null);
    setLocked(false);
    if (!agentId) return;
    void loadReport();
  }, [agentId, loadReport]);

  // Default to the first report entry — datasets are newest-first by their
  // most recent run, so index 0 is the suite a reader means by "how is the
  // agent doing" unless they say otherwise.
  useEffect(() => {
    if (!report || datasetId) return;
    setDatasetId(report.datasets[0]?.datasetId ?? null);
  }, [report, datasetId]);

  useEffect(() => {
    setRuns([]);
    setFloor(null);
    setFloorFailed(false);
    if (!datasetId) return;
    void loadRuns();
  }, [datasetId, loadRuns]);

  useEffect(() => {
    if (!agentId || !datasetId || floorHidden) return;
    void loadFloor();
  }, [agentId, datasetId, floorHidden, loadFloor]);

  // -----------------------------------------------------------------------
  // Render
  // -----------------------------------------------------------------------

  if (!agentId) return null;

  const sectionDescription =
    "A score with no repeat count is not a measurement. Every figure below carries the number of independent " +
    "observations behind it and an interval, and an A/B whose interval straddles zero is reported as “cannot tell” " +
    "rather than as a winner.";

  // Gate 1: the shared API client lacks the statistics methods (an older web
  // bundle). The backend contract these detect is documented in
  // evalStatisticsApi.ts — pooled per-dataset reliability, per-dataset noise
  // floor, paired run comparison.
  if (!statsApi || !evalApi) {
    return (
      <Section
        title={tHC("s11")}
        description={sectionDescription}
      >
        <Alert>
          <Info aria-hidden="true" />
          <AlertTitle>{tHC("s18")}</AlertTitle>
          <AlertDescription>
            <p>
              {tHC("s17")}
              <span className="font-mono text-xs">getEvalReliability</span>,{" "}
              <span className="font-mono text-xs">getDatasetNoiseFloor</span>,{" "}
              <span className="font-mono text-xs">compareEvalRunsPaired</span> in{" "}
              <span className="font-mono text-xs">app/lib/api/quality.ts</span>{tHC("s16")}
            </p>
            <p className="mt-2">
              {tHC("s15")}
            </p>
          </AlertDescription>
        </Alert>
      </Section>
    );
  }

  // Gate 2: PREMIUM+ plan gate, same posture as AgentQualityPanel's 403.
  if (locked) {
    return (
      <Section
        title={tHC("s11")}
        description={sectionDescription}
      >
        <Alert>
          <ShieldAlert aria-hidden="true" />
          <AlertTitle>{tHC("s14")}</AlertTitle>
          <AlertDescription>
            {tHC("s13")}
          </AlertDescription>
        </Alert>
      </Section>
    );
  }

  // Gate 3: every block's route is missing — the statistics backend is not on
  // this core. One honest notice beats hidden blocks and a bare heading.
  if (statsHidden && floorHidden && compareHidden) {
    return (
      <Section
        title={tHC("s11")}
        description={sectionDescription}
      >
        <EmptyState
          icon={Sigma}
          title={tHC("s12")}
          description="The repeat-aware statistics endpoints (reliability, noise floor, paired comparison) aren't mounted on the core this console is talking to. Nothing is wrong with your agent — this console simply has nothing honest to show until that ships."
        />
      </Section>
    );
  }

  const datasets = report?.datasets || [];
  const selectedDataset = datasets.find((d) => d.datasetId === datasetId) || null;

  return (
    <Section
      title={tHC("s11")}
      description={sectionDescription}
    >
      <div className="space-y-8">
        {statsHidden ? null : reportFailed ? (
          <Alert variant="destructive">
            <AlertTitle>{tHC("s10")}</AlertTitle>
            <AlertDescription className="flex flex-wrap items-center gap-3">
              {tHC("s9")}
              <Button variant="outline" size="sm" onClick={loadReport}>
                Retry
              </Button>
            </AlertDescription>
          </Alert>
        ) : !report ? (
          <p className="text-sm text-muted-foreground">
            {reportLoading ? "Loading reliability statistics…" : "No reliability report returned for this agent."}
          </p>
        ) : datasets.length === 0 ? (
          <EmptyState
            icon={FlaskConical}
            title={tHC("s8")}
            description="Reliability statistics are computed over an agent's completed eval runs. Run a dataset from the section above — twice is better than once, because a single run is still only one observation."
          />
        ) : (
          <div className="space-y-8">
            {/* Dataset selection — the report only lists datasets with at
                least one SUCCEEDED run, which is exactly the set statistics
                can be computed over. */}
            <div className="flex flex-wrap items-end gap-3">
              <div className="space-y-1">
                <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-stats-dataset">
                  Dataset
                </label>
                <Select
                  id="eval-stats-dataset"
                  className="min-w-60"
                  value={datasetId}
                  onChange={(e: SelectChangeEvent) => setDatasetId(e.target.value)}
                  options={datasets.map((d) => ({
                    value: d.datasetId,
                    label: `${d.datasetName} (${d.caseCount} case${d.caseCount === 1 ? "" : "s"})`,
                  }))}
                  searchable
                  placeholder={tHC("s7")}
                />
              </div>
              <Button variant="outline" size="sm" onClick={loadReport} disabled={reportLoading}>
                <RefreshCw size={14} className={reportLoading ? "animate-spin" : undefined} aria-hidden="true" />{" "}
                Refresh
              </Button>
            </div>

            {(report.warnings || []).length > 0 && <WarningsList warnings={report.warnings} />}

            {/* --------------------------------------------------------- */}
            {/* 1. Reliability of the selected dataset                     */}
            {/* --------------------------------------------------------- */}
            {selectedDataset && (
              <DatasetReliabilityBlock
                dataset={selectedDataset}
                onlyUnstable={onlyUnstable}
                onToggleOnlyUnstable={() => setOnlyUnstable((v) => !v)}
              />
            )}

            {/* --------------------------------------------------------- */}
            {/* 2. This dataset's noise floor                              */}
            {/* --------------------------------------------------------- */}
            {!floorHidden && datasetId && (
              <Section
                title={tHC("s6")}
                description="Two runs of the UNCHANGED configuration, compared pairwise. Any “improvement” smaller than this is not one."
              >
                {floorFailed ? (
                  <Alert variant="destructive">
                    <AlertTitle>{tHC("s5")}</AlertTitle>
                    <AlertDescription className="flex flex-wrap items-center gap-3">
                      {tHC("s4")}
                      <Button variant="outline" size="sm" onClick={loadFloor}>
                        Retry
                      </Button>
                    </AlertDescription>
                  </Alert>
                ) : (
                  <NoiseFloorCard floor={floor} />
                )}
              </Section>
            )}

            {/* --------------------------------------------------------- */}
            {/* 3. Paired A/B                                              */}
            {/* --------------------------------------------------------- */}
            {!compareHidden && datasetId && (
              <Section
                title={tHC("s3")}
                description="Same cases, same order, compared pair by pair — with an interval, so this can legitimately come back “we cannot tell”."
              >
                {runsFailed && (
                  <Alert variant="destructive">
                    <AlertTitle>{tHC("s2")}</AlertTitle>
                    <AlertDescription className="flex flex-wrap items-center gap-3">
                      {tHC("s1")}
                      <Button variant="outline" size="sm" onClick={loadRuns}>
                        Retry
                      </Button>
                    </AlertDescription>
                  </Alert>
                )}
                <PairedComparisonCard
                  agentId={agentId}
                  runs={runs}
                  statsApi={statsApi}
                  measuredNoiseFloor={floor?.noiseFloor?.floor ?? null}
                  onUnsupported={() => setCompareHidden(true)}
                />
              </Section>
            )}
          </div>
        )}
      </div>
    </Section>
  );
}

