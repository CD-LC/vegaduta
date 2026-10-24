"use client";

// Agent Lab - Consistency check results. Everything shown is derived from the
// run and the context it was started with (never from the form, which may
// have changed since): the headline state, how the samples group, the judge's
// pairwise verdicts behind the grouping, and the answers themselves.
//
// Wording rule: "answers disagree" is only ever said when the verdicts
// support it whichever way the missing ones are read (consistencyMath). When
// they do not, the state is Inconclusive and the samples are listed without
// being put into groups at all.
import { useMemo, useState, type CSSProperties, type ReactElement } from "react";
import { CircleCheck, CircleHelp, OctagonAlert, TriangleAlert } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import ClusterShareBar, { ClusterSwatch } from "./ClusterShareBar";
import EquivalenceGrid from "./EquivalenceGrid";
import { assembleConsistency, type ConsistencyResult } from "./consistencyMath";
import type { ConsistencyContext } from "./consistencyPlan";
import type { LabCellResult, LabRun } from "./labApi";
import { clampText } from "./labTurns";

const QUESTION_PREVIEW_CHARS = 300;
const ANSWER_PREVIEW_CHARS = 480;

export default function ConsistencyResults(props: { run: LabRun; context: ConsistencyContext | null }): ReactElement {
  const { run, context } = props;
  const t = useTranslations("agentLab");
  const result = useMemo(() => assembleConsistency(run), [run]);
  const cells = useMemo(() => new Map(run.cells.map((cell) => [cell.key, cell])), [run.cells]);
  // Samples are numbered by their place in the request, so a failed sample
  // leaves a gap instead of renumbering the ones after it.
  const numbers = useMemo(() => new Map(run.cells.map((cell, index) => [cell.key, index + 1])), [run.cells]);
  const [openGroups, setOpenGroups] = useState<ReadonlySet<number>>(new Set());

  const conclusive = result.state !== "insufficient";
  const failed = run.cells.filter((cell) => cell.status === "ERROR");
  const sizes = result.clusters.map((cluster) => cluster.indices.length);

  const toggleGroup = (index: number) =>
    setOpenGroups((current) => {
      const next = new Set(current);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });

  return (
    <section className="space-y-4">
      {context?.message && (
        <p className="text-xs text-muted-foreground">
          <span className="font-medium text-foreground">{t("ccQuestionLabel")}:</span>{" "}
          {clampText(context.message, QUESTION_PREVIEW_CHARS)}
        </p>
      )}

      <div className="space-y-1">
        <StateBanner result={result} />
        {conclusive && result.unknownPairs > 0 && result.groupingCertain && (
          <p className="text-xs text-muted-foreground">{t("ccUnknownNote", { count: result.unknownPairs })}</p>
        )}
        {conclusive && result.unknownPairs > 0 && !result.groupingCertain && (
          // The headline holds either way, the grouping does not: say which
          // reading the groups and the entropy below are drawn from.
          <p className="text-xs text-muted-foreground">
            {t("ccUnknownGroupsNote", { count: result.unknownPairs, shown: result.clusters.length, alt: result.altClusterCount })}
          </p>
        )}
        {conclusive && result.contradictions > 0 && (
          <p className="text-xs text-muted-foreground">{t("ccContradictionNote", { count: result.contradictions })}</p>
        )}
        {run.status !== "SUCCEEDED" && <p className="text-xs font-medium text-foreground">{t("partialResults")}</p>}
        {context?.usedTools && !context.withTools && <p className="text-xs text-muted-foreground">{t("ccToolsWarning")}</p>}
      </div>

      {conclusive && (
        <div className="space-y-2">
          <p className="text-xs text-muted-foreground">
            <span className="font-medium text-foreground">
              {t("ccEntropy", {
                bits: result.entropyBits.toFixed(2),
                percent: Math.round(result.normalised * 100),
                n: result.n,
              })}
              .
            </span>{" "}
            {t("ccEntropyHelp")}
          </p>
          <ClusterShareBar sizes={sizes} />
        </div>
      )}

      {conclusive ? (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-foreground">{t("ccGroupsTitle")}</h3>
          <ul className="space-y-2">
            {result.clusters.map((cluster, index) => {
              const open = openGroups.has(index);
              const shown = open ? cluster.keys : cluster.keys.slice(0, 1);
              return (
                <li key={cluster.keys[0]} className="space-y-2 rounded-lg border border-border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <ClusterSwatch index={index} />
                    <span className="text-sm font-medium text-foreground">{t("ccGroupLabel", { n: index + 1 })}</span>
                    <span className="text-xs text-muted-foreground">
                      {t("ccGroupCount", { count: cluster.keys.length, total: result.n })}
                    </span>
                    {cluster.keys.length > 1 && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        className="ml-auto"
                        aria-expanded={open}
                        onClick={() => toggleGroup(index)}
                      >
                        {open ? t("ccGroupHide") : t("ccGroupShowAll")}
                      </Button>
                    )}
                  </div>
                  <ul className="space-y-2">
                    {shown.map((key) => (
                      <SampleAnswer key={key} sampleNumber={numbers.get(key) ?? 0} cell={cells.get(key)} />
                    ))}
                  </ul>
                </li>
              );
            })}
          </ul>
        </div>
      ) : (
        // Inconclusive: the finished samples, deliberately NOT grouped - a
        // grouping built on missing verdicts would show disagreement that
        // nobody measured.
        result.keys.length > 0 && (
          <ul className="space-y-2">
            {result.keys.map((key) => (
              <SampleAnswer key={key} sampleNumber={numbers.get(key) ?? 0} cell={cells.get(key)} />
            ))}
          </ul>
        )
      )}

      {result.n >= 2 && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-foreground">{t("ccMatrixTitle")}</h3>
          <EquivalenceGrid matrix={result.matrix} sampleNumbers={result.keys.map((key) => numbers.get(key) ?? 0)} />
        </div>
      )}

      {failed.length > 0 && (
        <ul className="space-y-1 text-xs text-destructive">
          {failed.map((cell) => (
            <li key={cell.key}>{t("ccSampleFailed", { n: numbers.get(cell.key) ?? 0, reason: cell.errorMessage ?? "" })}</li>
          ))}
        </ul>
      )}

      <div className="space-y-1 text-xs text-muted-foreground">
        <p>{t("ccMethodNote")}</p>
        <p>{t("ccCaveat")}</p>
      </div>
    </section>
  );
}

/** Icon + label + colour for the state, and the one sentence that goes with
 * it. Explicit branches, so every key is a literal the i18n key test sees. */
function StateBanner(props: { result: ConsistencyResult }): ReactElement {
  const { result } = props;
  const t = useTranslations("agentLab");

  let icon: ReactElement;
  let label: string;
  let detail: string;
  // The state hue, or undefined for the neutral "Inconclusive" treatment.
  let hue: string | undefined;
  if (result.state === "consistent") {
    icon = <CircleCheck aria-hidden="true" className="size-4 shrink-0" />;
    label = t("ccStateConsistent");
    detail = t("ccDetailConsistent", { n: result.n });
    hue = "var(--state-success)";
  } else if (result.state === "mixed") {
    icon = <TriangleAlert aria-hidden="true" className="size-4 shrink-0" />;
    label = t("ccStateMixed");
    detail = t("ccDetailMixed", { majority: result.majority, n: result.n });
    hue = "var(--state-warning)";
  } else if (result.state === "unstable") {
    icon = <OctagonAlert aria-hidden="true" className="size-4 shrink-0" />;
    label = t("ccStateUnstable");
    detail = t("ccDetailUnstable", { n: result.n });
    hue = "var(--state-error)";
  } else {
    icon = <CircleHelp aria-hidden="true" className="size-4 shrink-0" />;
    label = t("ccStateInsufficient");
    if (result.reason === "unverified") detail = t("ccDetailUnverified", { count: result.unknownPairs });
    else if (result.reason === "contradictory") detail = t("ccDetailContradictory", { count: result.contradictions });
    else detail = t("ccDetailInsufficient", { n: result.n, k: result.k });
  }

  // Same tint recipe as the shared .state-pill (globals.css): the hue at low
  // strength for fill and edge, full strength for the icon and label only.
  const frame: CSSProperties | undefined = hue
    ? {
        backgroundColor: `color-mix(in srgb, ${hue} 10%, transparent)`,
        borderColor: `color-mix(in srgb, ${hue} 32%, transparent)`,
      }
    : undefined;

  return (
    <div
      data-consistency={result.state}
      className={`space-y-1 rounded-lg border px-3 py-2 ${hue ? "" : "border-border bg-muted/40"}`}
      style={frame}
    >
      <p
        className={`flex items-center gap-2 text-sm font-semibold ${hue ? "" : "text-muted-foreground"}`}
        style={hue ? { color: hue } : undefined}
      >
        {icon}
        {label}
      </p>
      <p className="text-sm text-foreground">{detail}</p>
    </div>
  );
}

/** One sample's answer, shortened until asked for in full. */
function SampleAnswer(props: { sampleNumber: number; cell: LabCellResult | undefined }): ReactElement {
  const { sampleNumber, cell } = props;
  const t = useTranslations("agentLab");
  const [expanded, setExpanded] = useState(false);
  const answer = cell?.answer ?? "";
  const long = answer.length > ANSWER_PREVIEW_CHARS;
  return (
    <li className="space-y-1">
      <p className="text-xs font-medium text-muted-foreground">{t("ccSampleLabel", { n: sampleNumber })}</p>
      <p className="whitespace-pre-wrap break-words text-sm text-foreground">
        {long && !expanded ? clampText(answer, ANSWER_PREVIEW_CHARS) : answer}
      </p>
      {long && (
        <Button
          type="button"
          variant="link"
          size="sm"
          className="h-auto px-0 py-0"
          aria-expanded={expanded}
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? t("showLess") : t("showMore")}
        </Button>
      )}
    </li>
  );
}
