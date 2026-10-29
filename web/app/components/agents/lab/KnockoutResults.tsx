"use client";

// Agent Lab - Prompt Knockout results. Everything shown here is derived from
// the run's cells and the context the run was started with (never from the
// form, which may have changed since): one row per tested section with its
// measured contribution, the noise floor it was judged against, and the
// per-question numbers and answers behind it.
import { useEffect, useId, useMemo, useRef, useState, type ReactElement } from "react";
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, HelpCircle, Hourglass, Minus } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Badge } from "../../ui/badge";
import { Button } from "../../ui/button";
import DeltaBar, { formatSigned } from "./DeltaBar";
import type { LabCellResult, LabRun } from "./labApi";
import { clampText } from "./labTurns";
import { attributeSections, baselineNoise, type SectionAttribution } from "./knockoutMath";
import { baselineKey, readKnockoutScores, sectionKey, type KnockoutContext } from "./knockoutPlan";
import { promptWithout, type PromptSection } from "./promptSections";

const QUESTION_PREVIEW_CHARS = 160;
const COPIED_MS = 1500;

/**
 * `live`: the run is still going. The changes measured so far are shown, but
 * no section is labelled and no noise floor is quoted until it finishes - a
 * label read off the first few cells would be issued from almost no baseline
 * data and could flip as more arrives.
 */
export default function KnockoutResults(props: {
  context: KnockoutContext;
  run: LabRun;
  partial: boolean;
  live?: boolean;
}): ReactElement {
  const { context, run, partial } = props;
  const live = props.live === true;
  const t = useTranslations("agentLab");
  const { result, itemMeans } = useMemo(() => {
    const scores = readKnockoutScores(context, run);
    return {
      result: attributeSections(scores, context.testedSectionIds),
      itemMeans: baselineNoise(scores.baseline).itemMeans,
    };
  }, [context, run]);
  const cells = useMemo(() => new Map(run.cells.map((cell) => [cell.key, cell])), [run.cells]);

  // One scale for every row, wide enough for the largest change or floor.
  let domain = 0.1;
  for (const section of result.sections) {
    domain = Math.max(domain, Math.abs(section.delta ?? 0), section.floor ?? 0);
  }
  domain *= 1.1;

  return (
    <section className="space-y-3">
      <div className="space-y-1">
        <h3 className="text-sm font-semibold text-foreground">{t("koResultsTitle")}</h3>
        {partial && <p className="text-xs font-medium text-foreground">{t("partialResults")}</p>}
        <p className="text-xs text-muted-foreground">
          {result.nItems === 1
            ? t("koMeasuredOnOne", { repeats: result.repeats })
            : t("koMeasuredOn", { items: result.nItems, repeats: result.repeats })}
          {result.baselineMean !== null && <> {t("koBaselineScore", { score: result.baselineMean.toFixed(2) })}.</>}
        </p>
        {live ? (
          <p className="text-xs text-muted-foreground">{t("koLiveNote")}</p>
        ) : result.noiseFloor !== null ? (
          <p className="text-xs text-muted-foreground">
            <span className="font-medium text-foreground">
              {t("koNoiseFloor", { value: result.noiseFloor.toFixed(2) })}.
            </span>{" "}
            {t("koNoiseFloorHelp")}
          </p>
        ) : (
          <p className="text-xs text-muted-foreground">{t("koNoBaseline")}</p>
        )}
      </div>

      <ul className="space-y-2">
        {result.sections.map((attribution) => {
          const section = context.sections.find((candidate) => candidate.id === attribution.sectionId);
          if (!section) return null;
          return (
            <SectionRow
              key={attribution.sectionId}
              section={section}
              attribution={attribution}
              context={context}
              cells={cells}
              itemMeans={itemMeans}
              domain={domain}
              live={live}
            />
          );
        })}
      </ul>

      <div className="space-y-1 text-xs text-muted-foreground">
        <p>{t("koDeltaHelp")}</p>
        <p>{t("koCaveat")}</p>
      </div>
    </section>
  );
}

function scoreOf(cell: LabCellResult | undefined): number | null {
  if (!cell || cell.scoreStatus !== "SCORED") return null;
  return typeof cell.score === "number" && Number.isFinite(cell.score) ? cell.score : null;
}

function SectionRow(props: {
  section: PromptSection;
  attribution: SectionAttribution;
  context: KnockoutContext;
  cells: Map<string, LabCellResult>;
  itemMeans: (number | null)[];
  domain: number;
  live: boolean;
}): ReactElement {
  const { section, attribution, context, cells, itemMeans, domain, live } = props;
  const t = useTranslations("agentLab");
  const tableId = useId();
  const [open, setOpen] = useState(false);
  const [answersOpen, setAnswersOpen] = useState<ReadonlySet<number>>(new Set());
  const [copied, setCopied] = useState(false);
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (copiedTimer.current != null) clearTimeout(copiedTimer.current);
    },
    []
  );

  const title = section.title || t("koSectionFallbackTitle", { n: section.index + 1 });
  const magnitude = Math.abs(attribution.delta ?? 0).toFixed(2);
  // `delta` is "score lost when the section is removed". Sentences and the
  // per-question table state the change the way it reads: the score without
  // the section minus the baseline score.
  const change = attribution.delta === null ? null : formatSigned(-attribution.delta);

  let labelText: string;
  let detail: string;
  let state: string;
  let tone: "positive" | "negative" | "neutral" = "neutral";
  let icon: ReactElement;
  if (live) {
    labelText = t("koLabelPending");
    detail = change !== null ? t("koDetailPending", { change }) : t("koDetailPendingNone");
    state = "var(--state-idle)";
    icon = <Hourglass aria-hidden="true" />;
  } else if (attribution.label === "load-bearing") {
    labelText = t("koLabelLoadBearing");
    detail = t("koDetailLoadBearing", { delta: magnitude });
    state = "var(--state-success)";
    tone = "positive";
    icon = <CheckCircle2 aria-hidden="true" />;
  } else if (attribution.label === "harmful") {
    labelText = t("koLabelHarmful");
    detail = t("koDetailHarmful", { delta: magnitude });
    state = "var(--state-error)";
    tone = "negative";
    icon = <AlertTriangle aria-hidden="true" />;
  } else if (attribution.label === "no-effect") {
    labelText = t("koLabelNoEffect");
    detail = t("koDetailNoEffect", { delta: magnitude });
    state = "var(--state-idle)";
    icon = <Minus aria-hidden="true" />;
  } else {
    labelText = t("koLabelNotMeasured");
    // A change WAS measured; what is missing is a noise floor to judge it by.
    detail = change !== null ? t("koDetailNoFloor", { change }) : t("koDetailNotMeasured");
    state = "var(--state-idle)";
    icon = <HelpCircle aria-hidden="true" />;
  }

  const copyWithout = async () => {
    try {
      await navigator.clipboard.writeText(promptWithout(context.sections, [section.id]));
      setCopied(true);
      if (copiedTimer.current != null) clearTimeout(copiedTimer.current);
      copiedTimer.current = setTimeout(() => setCopied(false), COPIED_MS);
    } catch {
      // Clipboard blocked (permissions, insecure origin): nothing was copied,
      // so the button simply does not flip to "Copied".
    }
  };

  const toggleAnswers = (item: number) =>
    setAnswersOpen((prev) => {
      const next = new Set(prev);
      if (next.has(item)) next.delete(item);
      else next.add(item);
      return next;
    });

  return (
    <li className="space-y-2 rounded-lg border border-border bg-card p-3">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="min-w-0 truncate text-sm font-medium text-foreground">{title}</h4>
        <Badge variant="state" state={state}>
          {icon}
          {labelText}
        </Badge>
      </div>
      <p className="text-xs text-muted-foreground">
        {detail}
        {attribution.nItems > 0 && (
          <> {t("koItemsAgree", { agree: attribution.agreeCount, total: attribution.nItems })}</>
        )}
      </p>
      <DeltaBar delta={attribution.delta} floor={live ? null : attribution.floor} domain={domain} tone={tone} />
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" onClick={() => void copyWithout()}>
          <span aria-live="polite">{copied ? t("copied") : t("koCopyWithout")}</span>
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          aria-expanded={open}
          aria-controls={open ? tableId : undefined}
          onClick={() => setOpen((value) => !value)}
        >
          {open ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
          {t("koPerItemTitle")}
        </Button>
      </div>
      {open && (
        <div id={tableId} className="overflow-x-auto">
          <table className="w-full min-w-[28rem] border-collapse text-start text-xs">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th scope="col" className="py-1.5 pe-3 font-medium">
                  {t("koColQuestion")}
                </th>
                <th scope="col" className="px-3 py-1.5 text-end font-medium">
                  {t("koColBaseline")}
                </th>
                <th scope="col" className="px-3 py-1.5 text-end font-medium">
                  {t("koColWithout")}
                </th>
                <th scope="col" className="py-1.5 ps-3 text-end font-medium">
                  {t("koColChange")}
                </th>
              </tr>
            </thead>
            {context.items.map((item, i) => {
              const without = cells.get(sectionKey(section.id, i));
              const withoutScore = scoreOf(without);
              const lost = attribution.perItem[i] ?? null;
              const showAnswers = answersOpen.has(i);
              return (
                <tbody key={i} className="border-b border-border/60 align-top last:border-b-0">
                  <tr>
                    <th scope="row" className="py-1.5 pe-3 font-normal text-foreground">
                      <span className="block">{clampText(item.message, QUESTION_PREVIEW_CHARS)}</span>
                      <Button
                        type="button"
                        variant="link"
                        size="sm"
                        className="h-auto border-0 bg-transparent px-0 py-0.5 shadow-none"
                        aria-expanded={showAnswers}
                        onClick={() => toggleAnswers(i)}
                      >
                        {showAnswers ? t("koHideAnswers") : t("koShowAnswers")}
                      </Button>
                    </th>
                    <td className="px-3 py-1.5 text-end tabular-nums">
                      {itemMeans[i] !== null ? (itemMeans[i] as number).toFixed(2) : t("koNoScore")}
                    </td>
                    <td className="px-3 py-1.5 text-end tabular-nums">
                      {withoutScore !== null ? withoutScore.toFixed(2) : t("koNoScore")}
                    </td>
                    {/* Without minus baseline, so the sign matches the two columns beside it. */}
                    <td className="py-1.5 ps-3 text-end tabular-nums">{lost !== null ? formatSigned(-lost) : "–"}</td>
                  </tr>
                  {showAnswers && (
                    <tr>
                      <td colSpan={4} className="pb-3">
                        <div className="grid gap-3 sm:grid-cols-2">
                          <div className="space-y-2">
                            <p className="font-medium text-foreground">{t("koColBaseline")}</p>
                            {Array.from({ length: context.repeats }, (_, r) => (
                              <CellAnswer key={r} cell={cells.get(baselineKey(r, i))} />
                            ))}
                          </div>
                          <div className="space-y-2">
                            <p className="font-medium text-foreground">{t("koColWithout")}</p>
                            <CellAnswer cell={without} />
                          </div>
                        </div>
                      </td>
                    </tr>
                  )}
                </tbody>
              );
            })}
          </table>
        </div>
      )}
    </li>
  );
}

/** One answer with its score and the judge's note; a failed call says why. */
function CellAnswer(props: { cell: LabCellResult | undefined }): ReactElement {
  const { cell } = props;
  const t = useTranslations("agentLab");
  if (cell?.status === "ERROR") {
    return (
      <p className="rounded-md border border-border bg-muted/40 p-2 text-destructive">
        {t("koCellFailed", { reason: cell.errorMessage ?? "" })}
      </p>
    );
  }
  const score = scoreOf(cell);
  return (
    <div className="space-y-1 rounded-md border border-border bg-muted/40 p-2">
      <p className="font-medium tabular-nums text-foreground">{score !== null ? score.toFixed(2) : t("koNoScore")}</p>
      {cell?.answer && <p className="whitespace-pre-wrap break-words text-foreground">{cell.answer}</p>}
      {cell?.scoreFeedback && (
        <p className="text-muted-foreground">
          {t("koJudgeNote")}: {cell.scoreFeedback}
        </p>
      )}
    </div>
  );
}
