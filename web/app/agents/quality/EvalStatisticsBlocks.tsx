"use client";

// Presentational blocks for EvalStatisticsSection.tsx: per-dataset reliability,
// the per-case reliability table, the noise floor card and the warnings list.

import { Dices, Gauge, Layers, Repeat, ShieldAlert, Sigma, Waves } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Card, CardContent } from "../../components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import { Section, StatCard } from "../../components/ax/page-kit";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";
import type { CaseReliabilityResponse, DatasetNoiseFloorResponse, DatasetReliabilityResponse } from "../../lib/api";
import { caseMeanScoreEstimate, casePassEstimate, caseUnscoredRollouts, formatEstimate, formatValue, intervalToEstimate, isCaseFlaky, metric, metricEstimate, orEmptyEstimate, type IntervalEstimate } from "./evalStatisticsApi";
import { FlakyBadge, ScoreWithInterval, UnreplicatedBadge } from "./ScoreWithInterval";

/** The `[lo–hi], n=k` tail of a formatted estimate, for StatCard's `sub` slot
 *  where the point estimate is already the big figure. Returns the honest
 *  words when there is nothing to append, never an empty bracket. */
function intervalTail(estimate: IntervalEstimate | null | undefined): string {
  const e = orEmptyEstimate(estimate);
  const full = formatEstimate(e);
  if (full === "—") return "not measured";
  const head = formatValue(e.value);
  // Drop the separator that only made sense while the point estimate was still
  // in front of it, so an interval-less estimate reads "n=5" and not ", n=5".
  const tail = full
    .slice(head.length)
    .replace(/^\s*,\s*/, "")
    .trim();
  return tail || "no interval";
}

/** Backend-issued caveats that materially limit the numbers above them.
 *  Never decorative — the DTO contract says a warning is only present when
 *  it limits the figures, so every one is printed. */
export function WarningsList({ warnings }: { warnings: string[] }) {
  if (!warnings || warnings.length === 0) return null;
  return (
    <Alert variant="warning">
      <ShieldAlert aria-hidden="true" />
      <AlertTitle>Read these before quoting the numbers</AlertTitle>
      <AlertDescription>
        <ul className="list-disc space-y-1 pl-5">
          {warnings.map((w, i) => (
            <li key={i}>{w}</li>
          ))}
        </ul>
      </AlertDescription>
    </Alert>
  );
}

// ---------------------------------------------------------------------------
// Block 1: pooled reliability of one dataset
// ---------------------------------------------------------------------------

export function DatasetReliabilityBlock({
  dataset,
  onlyUnstable,
  onToggleOnlyUnstable,
}: {
  dataset: DatasetReliabilityResponse;
  onlyUnstable: boolean;
  onToggleOnlyUnstable: () => void;
}) {
  const meanScore = metricEstimate(dataset, "MEAN_SCORE");
  const passAt1 = metricEstimate(dataset, "PASS_AT_1");
  const passHatK = metricEstimate(dataset, "PASS_HAT_K");
  const passHatKPresent = !!metric(dataset, "PASS_HAT_K");
  const ragged = dataset.minRolloutsPerCase !== dataset.maxRolloutsPerCase;

  return (
    <Section
      title="Dataset reliability"
      description="pass@1 answers “can it ever do this”. pass^k answers “can I put it on a phone line”. The gap between them is the number a customer actually buys — pooled over this dataset's recent runs, one rollout per case per run."
    >
      <div className="space-y-5">
        {dataset.unreplicated && (
          <Alert variant="warning">
            <ShieldAlert aria-hidden="true" />
            <AlertTitle>Every case below was observed exactly once</AlertTitle>
            <AlertDescription>
              There is no within-case variance to measure, so no interval can be computed and pass^k does not exist.
              Re-run the dataset before treating any of these numbers as a measurement.
            </AlertDescription>
          </Alert>
        )}

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="Mean judge score"
            value={<span className="font-mono text-2xl">{formatValue(orEmptyEstimate(meanScore).value)}</span>}
            sub={<span className="font-mono">{intervalTail(meanScore)}</span>}
            icon={Sigma}
            accent
          />
          <StatCard
            label="pass@1"
            value={<span className="font-mono text-2xl">{formatValue(orEmptyEstimate(passAt1).value)}</span>}
            sub={<span className="font-mono">{intervalTail(passAt1)}</span>}
            icon={Layers}
          />
          <StatCard
            label={`pass^${dataset.k}`}
            value={
              <span className="font-mono text-2xl">
                {passHatKPresent ? formatValue(orEmptyEstimate(passHatK).value) : "—"}
              </span>
            }
            sub={
              passHatKPresent ? (
                <span className="font-mono">{intervalTail(passHatK)}</span>
              ) : (
                "needs at least 2 rollouts per case"
              )
            }
            icon={Repeat}
          />
          <StatCard
            label="Reliability gap"
            value={<span className="font-mono text-2xl">{formatValue(dataset.reliabilityGap)}</span>}
            sub={`pass@1 minus pass^${dataset.k} — how much of the pass rate does not survive repetition`}
            icon={Waves}
          />
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <Badge variant="secondary">
            {ragged
              ? `${dataset.minRolloutsPerCase}–${dataset.maxRolloutsPerCase} rollouts/case`
              : `${dataset.minRolloutsPerCase} rollouts/case`}
          </Badge>
          <Badge variant="outline">
            pooled over {dataset.runsConsidered} run{dataset.runsConsidered === 1 ? "" : "s"}
          </Badge>
          <Badge variant="outline">{dataset.caseCount} cases</Badge>
          {dataset.unreplicated && <UnreplicatedBadge />}
        </div>

        {(dataset.warnings || []).length > 0 && <WarningsList warnings={dataset.warnings} />}

        <CaseReliabilityTable
          cases={dataset.cases || []}
          onlyUnstable={onlyUnstable}
          onToggleOnlyUnstable={onToggleOnlyUnstable}
        />
      </div>
    </Section>
  );
}

// ---------------------------------------------------------------------------
// Per-case table
// ---------------------------------------------------------------------------

function CaseReliabilityTable({
  cases,
  onlyUnstable,
  onToggleOnlyUnstable,
}: {
  cases: CaseReliabilityResponse[];
  onlyUnstable: boolean;
  onToggleOnlyUnstable: () => void;
}) {
  // "Unstable" deliberately covers both failure modes a reader must not miss:
  // a case whose rollouts disagreed (flaky) and a case with only one rollout
  // behind it (unreplicated). Both mean "do not quote this number".
  const unstable = (c: CaseReliabilityResponse) =>
    isCaseFlaky(c) || (c.rollouts || 0) <= 1 || caseUnscoredRollouts(c) > 0;
  const visible = onlyUnstable ? cases.filter(unstable) : cases;
  const unstableCount = cases.filter(unstable).length;
  const casesPagination = usePagination(visible);

  if (cases.length === 0) {
    return <p className="text-sm text-muted-foreground">This dataset has no per-case statistics.</p>;
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-sm font-semibold text-foreground">Per-case reliability</h3>
        <label className="flex items-center gap-2 text-xs text-muted-foreground">
          <input type="checkbox" checked={onlyUnstable} onChange={onToggleOnlyUnstable} />
          Only unstable cases ({unstableCount})
        </label>
      </div>

      {visible.length === 0 ? (
        <Alert variant="success">
          <Dices aria-hidden="true" />
          <AlertTitle>Every case in this dataset is stable</AlertTitle>
          <AlertDescription>
            All {cases.length} cases agreed across every observed rollout, with none unscored.
          </AlertDescription>
        </Alert>
      ) : (
        <div className="rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-10">#</TableHead>
                <TableHead>Case</TableHead>
                <TableHead className="w-56">pass rate</TableHead>
                <TableHead className="w-56">Mean judge score</TableHead>
                <TableHead className="w-44">Stability</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {casesPagination.pageItems.map((c, idx) => (
                <TableRow key={c.caseKey || `${idx}`}>
                  <TableCell className="font-mono text-xs tabular-nums text-muted-foreground">
                    {idx + 1 + (casesPagination.page - 1) * casesPagination.pageSize}
                  </TableCell>
                  <TableCell className="max-w-xs">
                    <span className="line-clamp-2 text-sm text-foreground" title={c.userMessage}>
                      {c.userMessage}
                    </span>
                  </TableCell>
                  <TableCell>
                    <ScoreWithInterval estimate={casePassEstimate(c)} size="sm" />
                  </TableCell>
                  <TableCell>
                    {c.meanScore === null || c.meanScore === undefined ? (
                      <span className="text-xs text-muted-foreground">—</span>
                    ) : (
                      <ScoreWithInterval estimate={caseMeanScoreEstimate(c)} size="sm" hideUnreplicatedBadge />
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {c.allPassed && (c.rollouts || 0) > 1 && (
                        <Badge variant="outline">
                          {c.rollouts}/{c.rollouts} passed
                        </Badge>
                      )}
                      {isCaseFlaky(c) && <FlakyBadge passed={c.successes} rollouts={c.rollouts} />}
                      {(c.rollouts || 0) <= 1 && <UnreplicatedBadge />}
                      {!c.allPassed && !isCaseFlaky(c) && (c.rollouts || 0) > 1 && (
                        <Badge variant="outline">
                          {c.successes}/{c.rollouts} consistent
                        </Badge>
                      )}
                      {caseUnscoredRollouts(c) > 0 && (
                        <Badge
                          variant="secondary"
                          title="Rollouts that produced no judge score are excluded from the mean — missing data, not a failure."
                        >
                          {caseUnscoredRollouts(c)} unscored
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <PaginationControls
            page={casesPagination.page}
            totalPages={casesPagination.totalPages}
            totalItems={casesPagination.totalItems}
            pageSize={casesPagination.pageSize}
            onPageChange={casesPagination.setPage}
            onPageSizeChange={casesPagination.setPageSize}
            itemLabel="cases"
          />
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Block 2: noise floor
// ---------------------------------------------------------------------------

export function NoiseFloorCard({ floor }: { floor: DatasetNoiseFloorResponse | null }) {
  // Not measured is the default state for every new suite, and it is a
  // genuinely different statement from "the floor is zero". Saying so plainly
  // is the whole value of this card until the dataset has been run twice.
  const measured = floor?.noiseFloor || null;
  if (!measured) {
    return (
      <Alert variant="warning">
        <Gauge aria-hidden="true" />
        <AlertTitle>Noise floor not measured yet</AlertTitle>
        <AlertDescription>
          <p>
            The floor is measured by comparing two completed runs of this dataset and recording how much they disagree.
            Until at least two runs exist, a small A/B difference on this screen cannot be interpreted — and this
            console will not assume the floor is zero on your behalf.
          </p>
          {(floor?.warnings || []).length > 0 && (
            <ul className="mt-2 list-disc space-y-1 pl-5 text-xs">
              {(floor?.warnings || []).map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          )}
        </AlertDescription>
      </Alert>
    );
  }

  const disagreement = intervalToEstimate(
    measured.meanAbsoluteDelta,
    measured.meanAbsoluteDeltaInterval,
    measured.pairedCases
  );

  return (
    <Card>
      <CardContent className="space-y-4 pt-4">
        {measured.configurationSuspect && (
          <Alert variant="warning">
            <ShieldAlert aria-hidden="true" />
            <AlertTitle>These two runs were not identical after all</AlertTitle>
            <AlertDescription>
              The signed disagreement between the two runs excludes zero — the supposedly unchanged configuration
              drifted between them, so this number is drift evidence rather than a clean floor.
            </AlertDescription>
          </Alert>
        )}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <StatCard
            label="Noise floor"
            value={<span className="font-mono text-2xl">±{formatValue(measured.floor)}</span>}
            sub="differences at or below this are indistinguishable from run-to-run variance"
            icon={Waves}
            accent
          />
          <StatCard
            label="Mean disagreement"
            value={<span className="font-mono text-2xl">{formatValue(orEmptyEstimate(disagreement).value)}</span>}
            sub={<span className="font-mono">{intervalTail(disagreement)}</span>}
            icon={Dices}
          />
          <StatCard
            label="Worst case"
            value={<span className="font-mono text-2xl">{formatValue(measured.maxAbsoluteDelta)}</span>}
            sub={
              measured.secondRunFinishedAt
                ? `largest single-case disagreement · measured ${new Date(measured.secondRunFinishedAt).toLocaleString()}`
                : "largest single-case disagreement"
            }
            icon={Repeat}
          />
        </div>
        <p className="text-xs text-muted-foreground">
          Measured on “{floor?.datasetName || "this dataset"}” over {measured.pairedCases} paired case
          {measured.pairedCases === 1 ? "" : "s"}. A floor only describes the suite it was measured on — a different
          dataset has its own.
        </p>
      </CardContent>
    </Card>
  );
}
