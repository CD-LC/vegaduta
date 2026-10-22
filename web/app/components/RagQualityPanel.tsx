"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import {
  EVAL_PRESETS,
  buildEvalRequest,
  formatChars,
  formatCi,
  formatMrr,
  formatMs,
  formatPct,
  parseEvalDataset,
  presetHintKey,
  presetLabelKey,
  verdictAgainstBaseline,
  type DatasetErrorCode,
  type RagEvalResponse,
  type RagSettings,
} from "../lib/ragQuality";
import { Alert, AlertDescription, AlertTitle } from "./ui/alert";
import { Button } from "./ui/button";
import { Card } from "./ui/card";
import { Switch } from "./ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "./ui/table";
import { Textarea } from "./ui/textarea";
import BrandText from "./BrandText";
import { isPersonalEdition } from "../lib/edition";

const K_CHOICES = [3, 5, 10];

const ERROR_KEYS: Record<DatasetErrorCode, string> = {
  invalidJson: "errInvalidJson",
  notAnArray: "errNotAnArray",
  emptyQuestion: "errEmptyQuestion",
  tooLong: "errTooLong",
  missingExpected: "errMissingExpected",
  tooMany: "errTooMany",
};

const VERDICT_KEYS = {
  baseline: "verdictBaseline",
  better: "verdictBetter",
  worse: "verdictWorse",
  noise: "verdictNoise",
} as const;

// Two things for one collection: the parent-child opt-in (which only affects documents ingested from
// now on - said plainly below) and an eval that measures retrieval configurations on the owner's own
// questions, so a change is checked instead of assumed.
export default function RagQualityPanel({ collectionId }: { collectionId: string }) {
  const t = useTranslations("ragQuality");

  const [open, setOpen] = useState(false);
  const [settings, setSettings] = useState<RagSettings | null>(null);
  const [settingsLoading, setSettingsLoading] = useState(true);
  const [settingsUnavailable, setSettingsUnavailable] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const [datasetText, setDatasetText] = useState("");
  const [presets, setPresets] = useState<string[]>(["baseline", "fusion"]);
  const [k, setK] = useState(5);
  const [running, setRunning] = useState(false);
  const [evalResult, setEvalResult] = useState<RagEvalResponse | null>(null);
  const [evalError, setEvalError] = useState<string | null>(null);
  const [upgradeNeeded, setUpgradeNeeded] = useState(false);

  // A different collection starts from a clean slate (results of another collection's run must never be
  // shown under this one). Collapsing and re-expanding the same collection keeps what was there.
  useEffect(() => {
    setSettings(null);
    setEvalResult(null);
    setEvalError(null);
    setUpgradeNeeded(false);
    setSaveError(null);
  }, [collectionId]);

  // Settings load lazily, the first time the panel is expanded for a collection.
  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    setSettingsLoading(true);
    setSettingsUnavailable(false);
    void api
      .getRagSettings(collectionId)
      .then((res) => {
        if (!cancelled) setSettings(res);
      })
      .catch(() => {
        if (!cancelled) {
          setSettings(null);
          setSettingsUnavailable(true);
        }
      })
      .finally(() => {
        if (!cancelled) setSettingsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [collectionId, open]);

  const parsed = useMemo(() => parseEvalDataset(datasetText), [datasetText]);

  async function toggleParentChild(next: boolean) {
    setSaving(true);
    setSaveError(null);
    try {
      setSettings(await api.updateRagSettings(collectionId, { parentChildEnabled: next }));
    } catch (err: any) {
      setSaveError(err?.message || t("parentChildSaveError"));
    } finally {
      setSaving(false);
    }
  }

  function togglePreset(preset: string) {
    if (preset === "baseline") return; // the baseline is always measured - it is what the others are read against
    setPresets((current) => (current.includes(preset) ? current.filter((p) => p !== preset) : [...current, preset]));
  }

  async function runEval() {
    if (parsed.questions.length === 0 || running) return;
    setRunning(true);
    setEvalError(null);
    setUpgradeNeeded(false);
    try {
      setEvalResult(await api.runRagEval(collectionId, buildEvalRequest(parsed.questions, presets, k)));
    } catch (err: any) {
      setEvalResult(null);
      if (err?.status === 403) setUpgradeNeeded(true);
      else setEvalError(err?.message || t("evalError"));
    } finally {
      setRunning(false);
    }
  }

  const baseline = evalResult?.results.find((r) => r.preset === "baseline");
  const parentChildBlocked = !settings || !settings.schemaReady || (!settings.parentChildAvailable && !settings.parentChildEnabled);

  return (
    <Card className="gap-0 p-5" data-testid="rag-quality-panel">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <h2 className="text-lg font-semibold tracking-tight text-foreground"><BrandText text={t("title")} /></h2>
          <p className="text-sm text-muted-foreground"><BrandText text={t("subtitle")} /></p>
        </div>
        <Button type="button" size="sm" variant="outline" aria-expanded={open} aria-controls="rag-quality-body" onClick={() => setOpen((v) => !v)}>
          {open ? t("hide") : t("show")}
        </Button>
      </div>

      {open && (
      <div id="rag-quality-body">
      <section className="mt-5 space-y-3" aria-labelledby="rag-parent-child-heading">
        <h3 id="rag-parent-child-heading" className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("parentChildTitle")}
        </h3>
        {settingsLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 size={14} className="animate-spin" aria-hidden="true" /> {t("loading")}
          </div>
        ) : settingsUnavailable || !settings ? (
          <p className="text-sm text-muted-foreground">{t("settingsUnavailable")}</p>
        ) : (
          <>
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-1">
                <label htmlFor="rag-parent-child-switch" className="text-sm font-medium text-foreground">
                  {t("parentChildSwitchLabel")}
                </label>
                <p className="text-sm text-muted-foreground">
                  {t("parentChildDescription", { child: settings.childTokens, parent: settings.parentTokens })}
                </p>
              </div>
              <Switch
                id="rag-parent-child-switch"
                aria-label={t("parentChildSwitchLabel")}
                checked={settings.parentChildEnabled}
                disabled={saving || parentChildBlocked}
                onCheckedChange={(next) => void toggleParentChild(next)}
              />
            </div>
            <p className="text-xs text-muted-foreground">{t("parentChildAppliesNote")}</p>
            {!settings.schemaReady && <p className="text-xs text-[var(--state-warning)]">{t("parentChildNoSchema")}</p>}
            {settings.schemaReady && !settings.parentChildAvailable && (
              <p className="text-xs text-muted-foreground">{t("parentChildNotAvailable")}</p>
            )}
            {settings.parentChunkCount > 0 && (
              <p className="text-xs text-muted-foreground">{t("parentChildCount", { count: settings.parentChunkCount })}</p>
            )}
            <p className="text-xs text-muted-foreground">
              {t("deploymentLine", {
                fusion: settings.fusionEnabled ? t("stateOn") : t("stateOff"),
                rewrite:
                  settings.queryRewriteMode === "off"
                    ? t("stateOff")
                    : settings.queryRewriteMode === "multi"
                      ? t("rewriteModeMulti")
                      : settings.queryRewriteMode === "hyde"
                        ? t("rewriteModeHyde")
                        : t("stateOn"),
              })}
            </p>
            {saveError && (
              <p role="alert" className="text-xs text-destructive">
                {saveError}
              </p>
            )}
          </>
        )}
      </section>

      <section className="mt-6 space-y-4 border-t border-border pt-5" aria-labelledby="rag-eval-heading">
        <div className="space-y-1">
          <h3 id="rag-eval-heading" className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("evalTitle")}
          </h3>
          <p className="text-sm text-muted-foreground">{t("evalIntro")}</p>
        </div>

        <div className="space-y-2">
          <label htmlFor="rag-eval-dataset" className="text-sm font-medium text-foreground">
            {t("datasetLabel")}
          </label>
          <Textarea
            id="rag-eval-dataset"
            value={datasetText}
            onChange={(e) => setDatasetText(e.target.value)}
            placeholder={t("datasetPlaceholder")}
            rows={5}
            className="font-mono text-xs"
          />
          <p className="text-xs text-muted-foreground">{t("datasetFormatHelp")}</p>
          {datasetText.trim() !== "" && (
            <p className="text-xs text-muted-foreground" aria-live="polite">
              {t("parsedSummary", { count: parsed.questions.length })}
            </p>
          )}
          {parsed.errors.length > 0 && (
            <ul className="space-y-0.5 text-xs text-[var(--state-warning)]">
              {parsed.errors.slice(0, 5).map((e, i) => (
                <li key={`${e.line}-${e.code}-${i}`}>
                  {e.line > 0 ? t("errorLine", { line: e.line, message: t(ERROR_KEYS[e.code]) }) : t(ERROR_KEYS[e.code])}
                </li>
              ))}
              {parsed.errors.length > 5 && <li>{t("moreErrors", { count: parsed.errors.length - 5 })}</li>}
            </ul>
          )}
        </div>

        <div className="space-y-2">
          <div className="text-sm font-medium text-foreground">{t("presetsLabel")}</div>
          <div className="flex flex-wrap gap-2">
            {EVAL_PRESETS.map((preset) => {
              const on = presets.includes(preset) || preset === "baseline";
              return (
                <Button
                  key={preset}
                  type="button"
                  size="sm"
                  variant={on ? "default" : "outline"}
                  aria-pressed={on}
                  disabled={preset === "baseline"}
                  onClick={() => togglePreset(preset)}
                >
                  {t(presetLabelKey(preset))}
                </Button>
              );
            })}
          </div>
          <ul className="space-y-0.5 text-xs text-muted-foreground">
            {EVAL_PRESETS.map((preset) => (
              <li key={preset}>
                <span className="font-medium text-foreground">{t(presetLabelKey(preset))}</span> - {t(presetHintKey(preset))}
              </li>
            ))}
          </ul>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2" role="group" aria-label={t("kLabel")}>
            <span className="text-sm text-muted-foreground">{t("kLabel")}</span>
            {K_CHOICES.map((choice) => (
              <Button
                key={choice}
                type="button"
                size="sm"
                variant={k === choice ? "default" : "outline"}
                aria-pressed={k === choice}
                onClick={() => setK(choice)}
              >
                {choice}
              </Button>
            ))}
          </div>
          <Button type="button" onClick={() => void runEval()} disabled={running || parsed.questions.length === 0}>
            {running ? (
              <>
                <Loader2 size={14} className="animate-spin" aria-hidden="true" /> {t("running")}
              </>
            ) : (
              t("runButton")
            )}
          </Button>
        </div>

        {upgradeNeeded && (
          <Alert variant="warning">
            <AlertTitle>{t("upgradeTitle")}</AlertTitle>
            <AlertDescription>
              {t("upgradeBody")}{" "}
              {!isPersonalEdition() && (
                <Link href="/admin/billing" className="font-semibold text-[var(--upgrade)] underline-offset-4 hover:underline">
                  {t("upgradeLink")}
                </Link>
              )}
            </AlertDescription>
          </Alert>
        )}
        {evalError && (
          <p role="alert" className="text-sm text-destructive">
            {evalError}
          </p>
        )}

        {evalResult && (
          <div className="space-y-3" data-testid="rag-eval-results">
            <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t("resultsTitle")}</div>
            <p className="text-xs text-muted-foreground">
              {t("resultsMeta", {
                questions: evalResult.questions,
                k: evalResult.k,
                children: evalResult.childChunksInCollection,
                parents: evalResult.parentChunksInCollection,
              })}
            </p>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("colConfig")}</TableHead>
                  <TableHead>{t("colHitRate")}</TableHead>
                  <TableHead>{t("colRecall")}</TableHead>
                  <TableHead>{t("colMrr")}</TableHead>
                  <TableHead>{t("colContext")}</TableHead>
                  <TableHead>{t("colLatency")}</TableHead>
                  <TableHead>{t("colVerdict")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {evalResult.results.map((r) => {
                  const verdict = verdictAgainstBaseline(r, baseline);
                  return (
                    <TableRow key={r.name}>
                      <TableCell className="font-medium text-foreground">
                        {t(presetLabelKey(r.preset))}
                        {r.failedQuestions > 0 && (
                          <div className="text-xs font-normal text-[var(--state-warning)]">{t("failedQuestions", { count: r.failedQuestions })}</div>
                        )}
                      </TableCell>
                      <TableCell className="tabular-nums">
                        {formatPct(r.hitRate)} <span className="text-xs text-muted-foreground">({formatCi(r.hitRateCi)})</span>
                      </TableCell>
                      <TableCell className="tabular-nums">{formatPct(r.recallAtK)}</TableCell>
                      <TableCell className="tabular-nums">{formatMrr(r.mrr)}</TableCell>
                      <TableCell className="tabular-nums">{formatChars(r.avgReturnedChars)}</TableCell>
                      <TableCell className="tabular-nums">{formatMs(r.avgLatencyMs)}</TableCell>
                      <TableCell
                        className={cn(
                          "text-xs",
                          verdict === "better" && "text-[var(--state-success)]",
                          verdict === "worse" && "text-[var(--state-error)]",
                          (verdict === "noise" || verdict === "baseline") && "text-muted-foreground"
                        )}
                      >
                        {t(VERDICT_KEYS[verdict])}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
            <p className="text-xs text-muted-foreground">{t("ciHint")}</p>
            {evalResult.truncated && <p className="text-xs text-[var(--state-warning)]">{t("truncatedNote")}</p>}
            {evalResult.notes.length > 0 && (
              <ul className="list-disc space-y-0.5 pl-5 text-xs text-muted-foreground">
                {evalResult.notes.map((note, i) => (
                  <li key={i}>{note}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </section>
      </div>
      )}
    </Card>
  );
}
