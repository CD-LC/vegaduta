"use client";

import { useCallback, useMemo, useState } from "react";
import { ArrowLeftRight, CircleHelp, Info, ShieldAlert, TrendingDown, TrendingUp } from "lucide-react";
import Select from "../../components/Select";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import type { PairedComparisonResponse } from "../../lib/api";
import type { EvalRunSummary } from "./evalDatasetsApi";
import {
  classifyComparison,
  formatSigned,
  formatValue,
  gapEstimate,
  isPlanGate,
  markEvalStatsUnsupportedIf,
  orEmptyEstimate,
  type ComparisonReading,
  type EvalStatisticsApi,
} from "./evalStatisticsApi";
import { GapIntervalBar, ScoreWithInterval } from "./ScoreWithInterval";

/**
 * Paired A/B comparison of two eval runs — the one control on this screen
 * whose job is to REFUSE to answer.
 *
 * Everything else in the console reports. This card decides, and the decision
 * it most often has to reach is "we cannot tell". Two guards, both implemented
 * in classifyComparison() so they are testable without React:
 *
 *   1. the paired-gap interval straddles zero, or
 *   2. |meanDelta| is at or below the noise floor the gap was tested against
 *
 * …and the card renders an explicit "Cannot tell" state instead of a winner
 * badge. The guards can only ever WEAKEN a directional claim, never create
 * one, so a backend that forgets the check cannot make this card declare a
 * winner that the numbers printed beside it contradict.
 *
 * The backend's verdict vocabulary is richer than a boolean: VARIANT_BETTER
 * and BASELINE_BETTER are the only two outcomes allowed to wear a winner
 * badge; CANNOT_TELL (interval contains zero), INSIDE_NOISE_FLOOR (real but
 * below the measured floor) and INSUFFICIENT_DATA are answers, not errors.
 * The server also writes its own one-sentence `explanation`, which this card
 * prints verbatim rather than paraphrasing.
 *
 * i18n NOTE: literal English strings on purpose — see ScoreWithInterval.tsx's
 * header for the en.json cross-workstream reasoning.
 */

/** The statuses whose scores are actually comparable. A cancelled or failed
 *  run has a partial case set; pairing against it is how you get a confident
 *  number from half a suite. */
const COMPARABLE_STATUSES = ["SUCCEEDED"];

/**
 * The shared `Select` component types its handler loosely (an `any` event) so it
 * can stay a drop-in for a native <select> across ~13 call sites with
 * different value shapes (see Select.tsx's own note). A narrower parameter
 * type is still assignable to that signature, so this workstream keeps its own
 * call sites typed rather than inheriting the `any`.
 */
type SelectChangeEvent = { target: { value: string } };

export interface PairedComparisonCardProps {
  agentId: string;
  /** Run history for the selected dataset, newest first (as listEvalRuns
   *  returns it). The backend derives the dataset from the runs — the card
   *  only needs the two run ids. */
  runs: EvalRunSummary[];
  statsApi: EvalStatisticsApi;
  /**
   * The dataset's separately measured noise floor, used ONLY as a fallback
   * when the comparison response does not echo one of its own
   * (`noiseFloorTested`). Both come from the same measurement; the
   * distinction is surfaced in the UI so the reader knows which number the
   * verdict was taken against.
   */
  measuredNoiseFloor: number | null;
  /** Called when the card discovers the compare route is not on this core, so
   *  the parent can stop offering it. */
  onUnsupported: () => void;
}

export default function PairedComparisonCard({
  agentId,
  runs,
  statsApi,
  measuredNoiseFloor,
  onUnsupported,
}: PairedComparisonCardProps) {
  // Only completed runs are offered. Newest-first, so index 0 is the natural
  // candidate and index 1 the natural baseline — the comparison a human
  // almost always wants is "did my last change help".
  const comparable = useMemo(() => runs.filter((r) => COMPARABLE_STATUSES.includes(r.status)), [runs]);

  const [candidateRunId, setCandidateRunId] = useState<string | null>(null);
  const [baselineRunId, setBaselineRunId] = useState<string | null>(null);
  const [comparison, setComparison] = useState<PairedComparisonResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [locked, setLocked] = useState(false);
  const [failed, setFailed] = useState(false);

  // Defaults are derived rather than stored in an effect: with the run list as
  // the only input there is nothing to synchronise, and an effect here would
  // fight the user's own selection every time polling refreshed the list.
  const effectiveCandidate = candidateRunId || comparable[0]?.id || null;
  const effectiveBaseline = baselineRunId || comparable.find((r) => r.id !== effectiveCandidate)?.id || null;

  const runOptions = comparable.map((r) => ({
    value: r.id,
    label: `${new Date(r.createdAt).toLocaleString()} — score ${formatValue(r.overallScore)}`,
  }));

  const compare = useCallback(async () => {
    if (!effectiveBaseline || !effectiveCandidate || effectiveBaseline === effectiveCandidate) return;
    setLoading(true);
    setFailed(false);
    try {
      // The backend calls the challenger `variantRunId`; this card calls it
      // the candidate — same run, one name per surface.
      const result = await statsApi.compareEvalRunsPaired(agentId, effectiveBaseline, effectiveCandidate);
      setComparison(result);
      setLocked(false);
    } catch (err) {
      setComparison(null);
      if (markEvalStatsUnsupportedIf("compareEvalRunsPaired", err)) {
        onUnsupported();
      } else if (isPlanGate(err)) {
        setLocked(true);
      } else {
        // Load failure, kept distinct from "no comparison yet" — a failed
        // fetch rendering as an empty result reads as "no difference", which
        // is a conclusion we did not reach.
        setFailed(true);
      }
    } finally {
      setLoading(false);
    }
  }, [agentId, effectiveBaseline, effectiveCandidate, statsApi, onUnsupported]);

  const swap = useCallback(() => {
    setCandidateRunId(effectiveBaseline);
    setBaselineRunId(effectiveCandidate);
    setComparison(null);
  }, [effectiveBaseline, effectiveCandidate]);

  /**
   * The comparison the verdict is actually taken against. When the backend did
   * not echo a noise floor, the dataset's separately measured floor is
   * substituted — same measurement, just fetched by the other endpoint — so
   * an older core that computes gaps but not floors still gets the floor
   * guard applied.
   */
  const effectiveComparison = useMemo<PairedComparisonResponse | null>(() => {
    if (!comparison) return null;
    if (comparison.noiseFloorTested !== null && comparison.noiseFloorTested !== undefined) return comparison;
    if (measuredNoiseFloor === null || measuredNoiseFloor === undefined) return comparison;
    return { ...comparison, noiseFloorTested: measuredNoiseFloor };
  }, [comparison, measuredNoiseFloor]);

  const floorWasSubstituted =
    !!comparison &&
    (comparison.noiseFloorTested === null || comparison.noiseFloorTested === undefined) &&
    measuredNoiseFloor !== null &&
    measuredNoiseFloor !== undefined;

  const reading = useMemo(() => classifyComparison(effectiveComparison), [effectiveComparison]);

  if (comparable.length < 2) {
    return (
      <Card>
        <CardContent className="pt-4">
          <div className="flex items-start gap-3">
            <ArrowLeftRight size={16} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground">Paired A/B needs two completed runs</p>
              <p className="text-sm text-muted-foreground">
                This dataset has {comparable.length === 0 ? "no" : "one"} completed run. Run it again after a prompt,
                model or tool change and the two runs can be compared case by case — with an interval, so the comparison
                can come back &ldquo;we cannot tell&rdquo; instead of picking a winner.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardContent className="space-y-4 pt-4">
        {/* Run pickers */}
        <div className="grid gap-3 lg:grid-cols-[1fr_auto_1fr_auto] lg:items-end">
          <div className="space-y-1">
            <label className="text-xs font-medium text-muted-foreground" htmlFor="paired-baseline-run">
              Baseline (A)
            </label>
            <Select
              id="paired-baseline-run"
              className="w-full"
              value={effectiveBaseline}
              onChange={(e: SelectChangeEvent) => {
                setBaselineRunId(e.target.value);
                setComparison(null);
              }}
              options={runOptions}
              searchable
              placeholder="Select the baseline run"
            />
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={swap}
            title="Swap baseline and candidate — the gap's sign flips, the verdict should not."
            className="lg:mb-0.5"
          >
            <ArrowLeftRight size={14} aria-hidden="true" /> Swap
          </Button>
          <div className="space-y-1">
            <label className="text-xs font-medium text-muted-foreground" htmlFor="paired-candidate-run">
              Candidate (B)
            </label>
            <Select
              id="paired-candidate-run"
              className="w-full"
              value={effectiveCandidate}
              onChange={(e: SelectChangeEvent) => {
                setCandidateRunId(e.target.value);
                setComparison(null);
              }}
              options={runOptions}
              searchable
              placeholder="Select the candidate run"
            />
          </div>
          <Button
            size="sm"
            onClick={compare}
            disabled={loading || !effectiveBaseline || !effectiveCandidate || effectiveBaseline === effectiveCandidate}
            className="lg:mb-0.5"
          >
            {loading ? "Comparing…" : "Compare"}
          </Button>
        </div>

        {effectiveBaseline === effectiveCandidate && (
          <p className="text-xs text-muted-foreground">
            Pick two different runs. Comparing a run against itself measures the harness, not the agent.
          </p>
        )}

        {locked && (
          <Alert>
            <ShieldAlert aria-hidden="true" />
            <AlertTitle>Paired comparison is a Premium feature</AlertTitle>
            <AlertDescription>
              The eval-statistics endpoints sit behind the same Premium plan gate as the rest of the quality suite
              above.
            </AlertDescription>
          </Alert>
        )}

        {failed && (
          <Alert variant="destructive">
            <AlertTitle>Couldn&apos;t compute the comparison</AlertTitle>
            <AlertDescription className="flex flex-wrap items-center gap-3">
              This is a load failure, not a result of &ldquo;no difference&rdquo;.
              <Button variant="outline" size="sm" onClick={compare}>
                Retry
              </Button>
            </AlertDescription>
          </Alert>
        )}

        {!comparison && !loading && !failed && !locked && (
          <p className="text-sm text-muted-foreground">
            Pick two completed runs and hit Compare. The result is a signed, paired gap with an interval — not a winner.
          </p>
        )}

        {effectiveComparison && (
          <ComparisonResult
            comparison={effectiveComparison}
            reading={reading}
            floorWasSubstituted={floorWasSubstituted}
          />
        )}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// The verdict
// ---------------------------------------------------------------------------

function verdictBadge(reading: ComparisonReading) {
  if (reading.state === "CANDIDATE_BETTER") {
    return (
      <Badge variant="state" state="var(--state-success)" className="gap-1.5">
        <TrendingUp size={11} aria-hidden="true" /> Candidate is better
      </Badge>
    );
  }
  if (reading.state === "BASELINE_BETTER") {
    return (
      <Badge variant="state" state="var(--state-error)" className="gap-1.5">
        <TrendingDown size={11} aria-hidden="true" /> Candidate regressed
      </Badge>
    );
  }
  if (reading.state === "INDISTINGUISHABLE") {
    // The headline this whole screen was built to be able to print. The
    // backend's CANNOT_TELL and INSIDE_NOISE_FLOOR both land here; the flag
    // says which reason applies, matching the server's own vocabulary.
    return (
      <Badge variant="state" state="var(--state-warning)" className="gap-1.5">
        <CircleHelp size={11} aria-hidden="true" />
        {reading.withinNoiseFloor && !reading.straddlesZero
          ? "Cannot tell — inside the noise floor"
          : "Cannot tell — interval contains zero"}
      </Badge>
    );
  }
  return (
    <Badge variant="outline" className="gap-1.5">
      <CircleHelp size={11} aria-hidden="true" /> Not enough data to tell
    </Badge>
  );
}

function ComparisonResult({
  comparison,
  reading,
  floorWasSubstituted,
}: {
  comparison: PairedComparisonResponse;
  reading: ComparisonReading;
  floorWasSubstituted: boolean;
}) {
  const gap = orEmptyEstimate(gapEstimate(comparison));
  const undecided = reading.state === "INDISTINGUISHABLE" || reading.state === "INSUFFICIENT_DATA";

  return (
    <div className="space-y-4 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center gap-3">
        {verdictBadge(reading)}
        <span className="font-mono text-sm tabular-nums text-foreground">
          gap {formatSigned(gap.value)}
          {gap.ciLow !== null && gap.ciHigh !== null && (
            <span className="text-muted-foreground">
              {" "}
              [{formatSigned(gap.ciLow)} to {formatSigned(gap.ciHigh)}]
            </span>
          )}
        </span>
        <span className="text-xs text-muted-foreground">
          {comparison.pairedCases} paired case{comparison.pairedCases === 1 ? "" : "s"}
        </span>
        {comparison.unreplicated && (
          <Badge variant="state" state="var(--state-warning)" className="gap-1.5">
            unreplicated
          </Badge>
        )}
      </div>

      {/* The reason, printed verbatim. A verdict without its reason is the
          same single-number problem one level up. */}
      <p className={undecided ? "text-sm text-foreground" : "text-sm text-muted-foreground"}>{reading.reason}</p>

      <GapIntervalBar estimate={gap} noiseFloor={comparison.noiseFloorTested} />

      <div className="grid gap-3 sm:grid-cols-3">
        <PairedCount label="Candidate won" count={comparison.casesVariantBetter} total={comparison.pairedCases} />
        <PairedCount label="Tied" count={comparison.casesTied} total={comparison.pairedCases} />
        <PairedCount label="Candidate lost" count={comparison.casesBaselineBetter} total={comparison.pairedCases} />
      </div>

      {comparison.noiseFloorTested === null || comparison.noiseFloorTested === undefined ? (
        <Alert variant="warning">
          <Info aria-hidden="true" />
          <AlertTitle>No noise floor measured for this dataset yet</AlertTitle>
          <AlertDescription>
            Without a floor, the only check applied to this gap is whether its interval contains zero. A gap can clear
            zero and still be smaller than this dataset&apos;s own run-to-run variance. Run the unchanged configuration
            twice to measure the floor before acting on a small difference.
          </AlertDescription>
        </Alert>
      ) : (
        <p className="text-xs text-muted-foreground">
          Checked against a noise floor of {formatValue(comparison.noiseFloorTested)}
          {floorWasSubstituted
            ? " (taken from this dataset's noise-floor measurement; the comparison response did not carry one)"
            : ""}
          .
        </p>
      )}

      {comparison.signTestPValue !== null && comparison.signTestPValue !== undefined && (
        <p className="font-mono text-xs tabular-nums text-muted-foreground">
          sign test p={Number(comparison.signTestPValue).toPrecision(3)} over{" "}
          {comparison.casesVariantBetter + comparison.casesBaselineBetter} discordant case
          {comparison.casesVariantBetter + comparison.casesBaselineBetter === 1 ? "" : "s"}
        </p>
      )}

      {reading.downgradedFromServerVerdict && (
        <Alert variant="warning">
          <ShieldAlert aria-hidden="true" />
          <AlertTitle>The backend called a winner; this console did not</AlertTitle>
          <AlertDescription>
            The comparison endpoint returned <span className="font-mono">{comparison.verdict}</span>, but the gap it
            returned alongside it does not support a direction. The console shows the weaker claim on purpose. Worth
            reporting — the two should agree.
          </AlertDescription>
        </Alert>
      )}

      {(comparison.warnings || []).length > 0 && (
        <Alert variant="warning">
          <ShieldAlert aria-hidden="true" />
          <AlertTitle>Read these before quoting the comparison</AlertTitle>
          <AlertDescription>
            <ul className="list-disc space-y-1 pl-5">
              {comparison.warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      <div className="border-t border-border pt-3">
        <ScoreWithInterval estimate={gap} label="Signed paired gap (candidate − baseline)" size="sm" />
      </div>
    </div>
  );
}

function PairedCount({ label, count, total }: { label: string; count: number; total: number }) {
  const share = total > 0 ? count / total : null;
  return (
    <div className="rounded-md border border-border/60 p-3">
      <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className="mt-1 font-mono text-lg font-semibold tabular-nums text-foreground">{count}</div>
      <div className="text-xs text-muted-foreground">
        {share === null ? "of no paired cases" : `${Math.round(share * 100)}% of paired cases`}
      </div>
    </div>
  );
}
