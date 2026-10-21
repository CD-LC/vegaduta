"use client";

// Agent Lab - Prompt Knockout. Splits the agent's system prompt into
// sections, runs the unchanged prompt several times plus one variant per
// ticked section (that section removed) over the same questions, and shows
// which removals moved the judge score by more than run-to-run noise.
//
// The run itself is a lab run (useLabRun): raw completions against the
// prompt variants, not agent chat turns - AgentLab states that scope above
// this panel. Nothing here changes the agent; the only way out is "Copy
// prompt without this section" in the results.
import { useEffect, useId, useMemo, useState } from "react";
import { useTranslations } from "../../../lib/i18n";
import { api } from "../../../lib/api";
import { Button } from "../../ui/button";
import { Select } from "../../ui/select";
import { Textarea } from "../../ui/textarea";
import { LAB_DEFAULT_MAX_CALLS, LAB_MAX_MESSAGE_CHARS, LAB_MAX_PROMPT_CHARS, plannedCalls } from "./labApi";
import { LabEstimate, LabRunButton, LabRunStatus } from "./LabRunControls";
import { promptOf } from "./labTurns";
import KnockoutResults from "./KnockoutResults";
import { MIN_LABEL_DF, MIN_LABEL_ITEMS, canEstimateFloor } from "./knockoutMath";
import { buildKnockoutRequest, readKnockoutScores, type KnockoutContext, type KnockoutItem } from "./knockoutPlan";
import { MAX_PROMPT_SECTIONS, splitPromptSections } from "./promptSections";
import { useLabRun } from "./useLabRun";
import type { KnockoutPanelProps } from "./types";

const MAX_ITEMS = 10;
const DEFAULT_DATASET_ITEMS = 5;
const MIN_REPEATS = 2;
const MAX_REPEATS = 5;
const DEFAULT_REPEATS = 3;
const DEFAULT_RUN_TTL_MINUTES = 60;

const FIELD_CLASS =
  "flex h-9 min-w-0 rounded-md border border-border bg-secondary/40 px-3 py-1.5 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50";

interface DatasetSummary { id: string; name: string; caseCount: number }

/** The list body when it is one; anything else (the preview's `{}`) is "none". */
function datasetsOf(body: unknown): DatasetSummary[] {
  if (!Array.isArray(body)) return [];
  const out: DatasetSummary[] = [];
  for (const entry of body as unknown[]) {
    const row = entry as { id?: unknown; name?: unknown; caseCount?: unknown } | null;
    if (!row || row.id == null) continue;
    out.push({
      id: String(row.id),
      name: typeof row.name === "string" && row.name ? row.name : String(row.id),
      caseCount: typeof row.caseCount === "number" ? row.caseCount : 0,
    });
  }
  return out;
}

/** A dataset's usable cases, or null when the body has no `cases` array. */
function casesOf(body: unknown): KnockoutItem[] | null {
  const cases = (body as { cases?: unknown } | null)?.cases;
  if (!Array.isArray(cases)) return null;
  const out: KnockoutItem[] = [];
  for (const entry of cases as unknown[]) {
    const row = entry as { userMessage?: unknown; expectedOutput?: unknown } | null;
    const message = typeof row?.userMessage === "string" ? row.userMessage.trim() : "";
    if (!message) continue;
    const expected = typeof row?.expectedOutput === "string" && row.expectedOutput.trim() ? row.expectedOutput : null;
    out.push({ message, expected });
  }
  return out;
}

/** sessionStorage hands the context back as parsed JSON of unknown age. */
function isKnockoutContext(value: unknown): value is KnockoutContext {
  const ctx = value as Partial<KnockoutContext> | null;
  return (
    !!ctx &&
    Array.isArray(ctx.sections) &&
    Array.isArray(ctx.testedSectionIds) &&
    Array.isArray(ctx.items) &&
    typeof ctx.repeats === "number"
  );
}

function clampInt(raw: string, min: number, max: number, fallback: number): number {
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

export default function KnockoutPanel({ agent, capabilities }: KnockoutPanelProps) {
  const t = useTranslations("agentLab");
  const ids = useId();
  const agentId = agent.id;
  const lab = useLabRun<KnockoutContext>(agentId, "KNOCKOUT");

  const prompt = promptOf(agent);
  const split = useMemo(() => splitPromptSections(prompt, MAX_PROMPT_SECTIONS), [prompt]);
  const [unticked, setUnticked] = useState<ReadonlySet<string>>(new Set());
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());

  const [source, setSource] = useState<"dataset" | "probes">("dataset");
  // null = the list is still loading; [] = none (or the read failed).
  const [datasets, setDatasets] = useState<DatasetSummary[] | null>(null);
  const [datasetId, setDatasetId] = useState<string | null>(null);
  // Keyed by dataset id so a slow answer for the previous choice is ignored;
  // `items: null` = that dataset could not be read.
  const [loadedCases, setLoadedCases] = useState<{ datasetId: string; items: KnockoutItem[] | null } | null>(null);
  const [datasetCount, setDatasetCount] = useState<number | null>(null);
  const [probeText, setProbeText] = useState("");
  const [repeats, setRepeats] = useState(DEFAULT_REPEATS);

  // Both reads THROW on a FREE plan (403) and resolve to `{}` in the preview;
  // either way there is simply no dataset to offer.
  useEffect(() => {
    let stale = false;
    void (async () => {
      let list: DatasetSummary[] = [];
      try {
        list = datasetsOf(await api.listEvalDatasets(agentId));
      } catch {
        list = [];
      }
      if (stale) return;
      setDatasets(list);
      setDatasetId((current) => current ?? list[0]?.id ?? null);
    })();
    return () => {
      stale = true;
    };
  }, [agentId]);

  useEffect(() => {
    if (!datasetId) return undefined;
    let stale = false;
    void (async () => {
      let items: KnockoutItem[] | null = null;
      try {
        items = casesOf(await api.getEvalDataset(agentId, datasetId));
      } catch {
        items = null;
      }
      if (!stale) setLoadedCases({ datasetId, items });
    })();
    return () => {
      stale = true;
    };
  }, [agentId, datasetId]);

  const datasetsLoading = datasets === null;
  const casesLoading = datasetId != null && loadedCases?.datasetId !== datasetId;
  const datasetCases = !casesLoading && loadedCases ? loadedCases.items : null;
  const noDatasets = !datasetsLoading && (datasets.length === 0 || (!casesLoading && datasetCases === null));
  const totalCases = datasetCases?.length ?? 0;
  const maxCount = Math.min(MAX_ITEMS, totalCases);
  const usedCount = Math.min(maxCount, Math.max(1, datasetCount ?? DEFAULT_DATASET_ITEMS));

  const probeLines = useMemo(
    () => probeText.split(/\r?\n/).map((line) => line.trim()).filter(Boolean),
    [probeText]
  );

  const ctx = useMemo((): KnockoutContext => {
    const items: KnockoutItem[] =
      source === "dataset"
        ? (datasetCases ?? []).slice(0, usedCount)
        : probeLines.slice(0, MAX_ITEMS).map((message): KnockoutItem => ({ message, expected: null }));
    return {
      sections: split.sections,
      testedSectionIds: split.sections.filter((section) => !unticked.has(section.id)).map((section) => section.id),
      items,
      repeats,
      prompt,
    };
  }, [source, datasetCases, usedCount, probeLines, split.sections, unticked, repeats, prompt]);
  const request = useMemo(() => buildKnockoutRequest(ctx), [ctx]);
  const calls = plannedCalls(request);
  const cap = capabilities?.maxCallsPerRun ?? LAB_DEFAULT_MAX_CALLS;

  // The first limit that applies, stated before start so it is never a 400.
  const tooLong = ctx.items.findIndex((item) => item.message.length > LAB_MAX_MESSAGE_CHARS);
  let blockedReason: string | undefined;
  if (prompt.length > LAB_MAX_PROMPT_CHARS) blockedReason = t("koPromptTooLong", { max: LAB_MAX_PROMPT_CHARS });
  else if (ctx.testedSectionIds.length === 0) blockedReason = t("koSectionsNone");
  else if (ctx.items.length === 0) blockedReason = t("koItemsNone");
  else if (tooLong >= 0) blockedReason = t("koItemTooLong", { n: tooLong + 1, max: LAB_MAX_MESSAGE_CHARS });
  // A run this small could never label a section (knockoutMath refuses a
  // noise floor from so little baseline), so it is not allowed to spend.
  else if (!canEstimateFloor(ctx.items.length, ctx.repeats)) blockedReason = t("koTooFewToLabel", { items: MIN_LABEL_ITEMS, df: MIN_LABEL_DF });
  // "lost" counts as live: the run may well still be going server-side, and
  // a second start would only be refused (one live run per user).
  const live = lab.phase === "starting" || lab.phase === "polling" || lab.phase === "lost";

  const toggle = (set: ReadonlySet<string>, id: string): ReadonlySet<string> => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  };

  // Results read the context the run was STARTED with, not the form.
  const run = lab.run;
  const runContext = isKnockoutContext(lab.context) ? lab.context : null;
  const hasScoredBaseline = useMemo(
    () =>
      run != null &&
      runContext != null &&
      readKnockoutScores(runContext, run).baseline.some((row) => row.some((score) => score !== null)),
    [run, runContext]
  );
  const partial =
    run != null && (run.status === "FAILED" || run.status === "CANCELLED" || (lab.phase === "expired" && run.status !== "SUCCEEDED"));
  // Still being measured: results show the changes so far, without labels.
  const measuring = live && run != null && (run.status === "QUEUED" || run.status === "RUNNING");

  return (
    <div className="space-y-5">
      <p className="text-sm text-foreground">{t("koIntro")}</p>

      {prompt.trim() === "" ? (
        <p className="text-sm text-muted-foreground">{t("koNoPrompt")}</p>
      ) : (
        <>
          <fieldset className="min-w-0 space-y-2">
            <legend className="text-sm font-semibold text-foreground">{t("koSectionsTitle")}</legend>
            <p className="text-xs text-muted-foreground">{t("koSectionsHint")}</p>
            {split.merged && (
              <p className="text-xs text-muted-foreground">{t("koSectionsMerged", { max: MAX_PROMPT_SECTIONS })}</p>
            )}
            <ul className="space-y-1.5">
              {split.sections.map((section) => {
                const title = section.title || t("koSectionFallbackTitle", { n: section.index + 1 });
                const isOpen = expanded.has(section.id);
                const checkboxId = `${ids}-${section.id}`;
                const previewId = `${ids}-${section.id}-text`;
                return (
                  <li key={section.id} className="rounded-lg border border-border bg-card px-3 py-2">
                    <div className="flex items-start gap-2">
                      <input
                        id={checkboxId}
                        type="checkbox"
                        className="mt-1"
                        checked={!unticked.has(section.id)}
                        onChange={() => setUnticked((prev) => toggle(prev, section.id))}
                      />
                      <label htmlFor={checkboxId} className="min-w-0 flex-1 cursor-pointer">
                        <span className="block truncate text-sm font-medium text-foreground">{title}</span>
                        <span className="block text-xs text-muted-foreground">
                          {t("koSectionChars", { count: section.text.length })}
                        </span>
                      </label>
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        aria-expanded={isOpen}
                        aria-controls={previewId}
                        aria-label={`${isOpen ? t("showLess") : t("showMore")}: ${title}`}
                        onClick={() => setExpanded((prev) => toggle(prev, section.id))}
                      >
                        {isOpen ? t("showLess") : t("showMore")}
                      </Button>
                    </div>
                    <p
                      id={previewId}
                      className={
                        "mt-1.5 whitespace-pre-wrap break-words text-xs text-muted-foreground " +
                        (isOpen ? "max-h-64 overflow-y-auto" : "line-clamp-2")
                      }
                    >
                      {section.text.trim()}
                    </p>
                  </li>
                );
              })}
            </ul>
          </fieldset>

          <fieldset className="min-w-0 space-y-2">
            <legend className="text-sm font-semibold text-foreground">{t("koItemsTitle")}</legend>
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-foreground">
                <input
                  type="radio"
                  name={`${ids}-source`}
                  checked={source === "dataset"}
                  onChange={() => setSource("dataset")}
                />
                {t("koSourceDataset")}
              </label>
              <label className="flex cursor-pointer items-center gap-2 text-sm text-foreground">
                <input
                  type="radio"
                  name={`${ids}-source`}
                  checked={source === "probes"}
                  onChange={() => setSource("probes")}
                />
                {t("koSourceProbes")}
              </label>
            </div>

            {source === "dataset" ? (
              datasetsLoading ? (
                <p role="status" className="text-xs text-muted-foreground">{t("koDatasetLoading")}</p>
              ) : noDatasets ? (
                <p className="text-xs text-muted-foreground">
                  {t("koDatasetNone")}{" "}
                  {/* Datasets are built on /agents/quality — l16-3 teaches them. */}
                </p>
              ) : (
                <div className="space-y-2">
                  <div className="flex flex-wrap items-end gap-3">
                    <div className="min-w-0 space-y-1.5">
                      <label htmlFor={`${ids}-dataset`} className="block text-xs font-medium text-foreground">
                        {t("koDatasetLabel")}
                      </label>
                      <Select
                        id={`${ids}-dataset`}
                        className="max-w-xs"
                        value={datasetId ?? ""}
                        onValueChange={(v) => {
                          setDatasetId(v);
                          setDatasetCount(null);
                        }}
                        options={datasets.map((dataset) => ({ value: dataset.id, label: dataset.name }))}
                      />
                    </div>
                    <div className="space-y-1.5">
                      <label htmlFor={`${ids}-count`} className="block text-xs font-medium text-foreground">
                        {t("koDatasetCount")}
                      </label>
                      <input
                        id={`${ids}-count`}
                        type="number"
                        inputMode="numeric"
                        className={FIELD_CLASS + " w-20"}
                        min={1}
                        max={Math.max(1, maxCount)}
                        value={Math.max(1, usedCount)}
                        disabled={casesLoading || maxCount === 0}
                        onChange={(e) => setDatasetCount(clampInt(e.target.value, 1, Math.max(1, maxCount), usedCount))}
                      />
                    </div>
                  </div>
                  {casesLoading ? (
                    <p role="status" className="text-xs text-muted-foreground">{t("koDatasetLoading")}</p>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      {t("koDatasetUsing", { used: usedCount, total: totalCases })} {t("koDatasetNote")}
                    </p>
                  )}
                </div>
              )
            ) : (
              <div className="space-y-1.5">
                <label htmlFor={`${ids}-probes`} className="block text-xs font-medium text-foreground">
                  {t("koProbesLabel")}
                </label>
                <Textarea
                  id={`${ids}-probes`}
                  rows={4}
                  value={probeText}
                  placeholder={t("koProbesPlaceholder")}
                  onChange={(e) => setProbeText(e.target.value)}
                />
                {probeLines.length > MAX_ITEMS && (
                  <p className="text-xs font-medium text-foreground">{t("koItemsTrimmed", { max: MAX_ITEMS })}</p>
                )}
                <p className="text-xs text-muted-foreground">{t("koProbesNote")}</p>
              </div>
            )}
          </fieldset>

          <div className="space-y-1.5">
            <label htmlFor={`${ids}-repeats`} className="block text-sm font-semibold text-foreground">
              {t("koRepeatsLabel")}
            </label>
            <input
              id={`${ids}-repeats`}
              type="number"
              inputMode="numeric"
              className={FIELD_CLASS + " w-20"}
              min={MIN_REPEATS}
              max={MAX_REPEATS}
              value={repeats}
              aria-describedby={`${ids}-repeats-hint`}
              onChange={(e) => setRepeats(clampInt(e.target.value, MIN_REPEATS, MAX_REPEATS, repeats))}
            />
            <p id={`${ids}-repeats-hint`} className="text-xs text-muted-foreground">{t("koRepeatsHint")}</p>
          </div>

          <div className="space-y-3">
            <LabEstimate
              calls={calls}
              cap={cap}
              breakdown={calls > 0 ? t("koBreakdown", { answers: request.cells.length, judges: request.cells.length }) : undefined}
            />
            <div className="space-y-1.5">
              <LabRunButton
                label={t("koRun")}
                calls={calls}
                capabilities={capabilities}
                disabled={blockedReason !== undefined || live}
                disabledReason={blockedReason}
                onRun={() => void lab.start(request, ctx)}
              />
              {blockedReason !== undefined && <p className="text-xs text-muted-foreground">{blockedReason}</p>}
            </div>
          </div>
        </>
      )}

      <LabRunStatus lab={lab} ttlMinutes={capabilities?.runTtlMinutes ?? DEFAULT_RUN_TTL_MINUTES} />

      {hasScoredBaseline && run != null && runContext != null && (
        <KnockoutResults context={runContext} run={run} partial={partial} live={measuring} />
      )}
    </div>
  );
}
