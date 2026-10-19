"use client";

import { useState } from "react";
import Link from "next/link";
import { Loader2, Mic, Plus, Search, Square, X } from "lucide-react";
import { api } from "../../lib/api";
import type { ResearchDepth, ResearchStatus } from "../../lib/api/types";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { useTranslations } from "../../lib/i18n";
import {
  DEPTH_OPTIONS,
  MAX_QUESTION_CHARS,
  MAX_SEED_URLS,
  allowanceUsedUp,
  cleanSeedUrls,
  roundMinutes,
} from "./researchRun";
import { RS } from "./researchStrings";
import { appendTranscript, useDictation } from "./useDictation";

/**
 * The question box. Shown only when the status probe says research is ready;
 * the limits line under it is read from that same probe, so what the user is
 * told ("up to N searches") is what the server will actually enforce.
 *
 * A refused start (daily allowance, plan ceiling, a switch flipped since the
 * page loaded) arrives with a ready-to-show sentence and is rendered inline -
 * the API client keeps those statuses out of the global error toast.
 */
export default function ResearchComposer({
  status,
  onStarted,
  onRefused,
  initialQuestion = "",
}: {
  status: ResearchStatus;
  onStarted: (job: any) => void;
  /** Called after a refused start so the page can re-read status (e.g. the allowance just ran out). */
  onRefused?: () => void;
  initialQuestion?: string;
}) {
  const t = useTranslations("researchPage");
  const [question, setQuestion] = useState(initialQuestion);
  const [depth, setDepth] = useState<ResearchDepth>("auto");
  const [seedUrls, setSeedUrls] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; upgrade: boolean } | null>(null);

  const dictation = useDictation((spoken) => setQuestion((q) => appendTranscript(q, spoken)));
  const limits = status.limits;
  const usedUp = allowanceUsedUp(status);
  const trimmed = question.trim();
  const tooLong = trimmed.length > MAX_QUESTION_CHARS;
  const seeds = cleanSeedUrls(seedUrls);
  const activeDepth = DEPTH_OPTIONS.find((d) => d.value === depth) ?? DEPTH_OPTIONS[0];
  const canSubmit = !busy && !usedUp && trimmed.length > 0 && !tooLong && seeds.invalid.length === 0;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const job = await api.startResearchRun({
        question: trimmed,
        depth,
        ...(seeds.urls.length ? { seedUrls: seeds.urls } : {}),
      });
      onStarted(job);
    } catch (err: any) {
      setError({ message: err?.message || t("startFailed"), upgrade: err?.status === 403 });
      onRefused?.();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="gap-4 p-5" data-testid="research-composer">
      <div className="space-y-2">
        <label htmlFor="research-question" className="text-sm font-medium text-foreground">
          {t("questionLabel")}
        </label>
        <Textarea
          id="research-question"
          rows={4}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
              e.preventDefault();
              void submit();
            }
          }}
          aria-describedby="research-question-hint"
          placeholder={t("questionPlaceholder")}
          disabled={busy}
          aria-invalid={tooLong}
        />
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <p id="research-question-hint" className="text-xs text-muted-foreground">
            {RS.submitHint}
          </p>
          {trimmed.length > MAX_QUESTION_CHARS * 0.9 && !tooLong && (
            <p className="text-xs text-muted-foreground">{RS.charsLeft(MAX_QUESTION_CHARS - trimmed.length)}</p>
          )}
          {dictation.supported && (
            <Button
              type="button"
              size="sm"
              variant={dictation.listening ? "default" : "outline"}
              className="ml-auto"
              aria-pressed={dictation.listening}
              disabled={busy}
              onClick={dictation.listening ? dictation.stop : dictation.start}
            >
              {dictation.listening ? (
                <Square className="size-4" aria-hidden="true" />
              ) : (
                <Mic className="size-4" aria-hidden="true" />
              )}
              {dictation.listening ? RS.dictateStop : RS.dictate}
            </Button>
          )}
        </div>
        <p className="sr-only" role="status" aria-live="polite">
          {dictation.listening ? RS.dictating : ""}
        </p>
        {dictation.failed && <p className="text-xs text-destructive">{RS.dictateError}</p>}
        {trimmed.length === 0 && (
          <div className="space-y-1.5" data-testid="research-examples">
            <p className="text-xs text-muted-foreground">{RS.examplesLabel}</p>
            <ul className="flex flex-wrap gap-2">
              {RS.examples.map((ex) => (
                <li key={ex} className="max-w-full">
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => setQuestion(ex)}
                    className="max-w-full rounded-full border border-border px-3 py-1.5 text-left text-xs text-foreground hover:bg-muted focus-visible:outline focus-visible:outline-2 focus-visible:outline-ring [overflow-wrap:anywhere]"
                  >
                    {ex}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
        {tooLong && <p className="text-xs text-destructive">{t("questionTooLong", { max: MAX_QUESTION_CHARS })}</p>}
      </div>

      <div className="space-y-2">
        <p id="research-depth-label" className="text-sm font-medium text-foreground">
          {t("depthLabel")}
        </p>
        <div role="group" aria-labelledby="research-depth-label" className="flex flex-wrap gap-2">
          {DEPTH_OPTIONS.map((d) => (
            <Button
              key={d.value}
              type="button"
              size="sm"
              variant={depth === d.value ? "default" : "outline"}
              aria-pressed={depth === d.value}
              disabled={busy}
              onClick={() => setDepth(d.value)}
            >
              {t(d.labelKey)}
            </Button>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">{t(activeDepth.hintKey)}</p>
      </div>

      <div className="space-y-2">
        <p className="text-sm font-medium text-foreground">{t("seedLabel")}</p>
        <p className="text-xs text-muted-foreground">{t("seedHint", { max: MAX_SEED_URLS })}</p>
        {seedUrls.map((url, i) => {
          const value = url.trim();
          const bad = value !== "" && seeds.invalid.includes(value);
          return (
            <div key={i} className="flex items-center gap-2">
              <Input
                type="url"
                inputMode="url"
                value={url}
                aria-label={t("seedInputLabel", { n: i + 1 })}
                aria-invalid={bad}
                placeholder={t("seedPlaceholder")}
                disabled={busy}
                onChange={(e) => setSeedUrls((prev) => prev.map((u, j) => (j === i ? e.target.value : u)))}
              />
              <Button
                type="button"
                size="sm"
                variant="ghost"
                aria-label={t("seedRemove", { n: i + 1 })}
                disabled={busy}
                onClick={() => setSeedUrls((prev) => prev.filter((_, j) => j !== i))}
              >
                <X className="size-4" aria-hidden="true" />
              </Button>
            </div>
          );
        })}
        {seeds.invalid.length > 0 && <p className="text-xs text-destructive">{t("seedInvalid")}</p>}
        {seedUrls.length < MAX_SEED_URLS && (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() => setSeedUrls((prev) => [...prev, ""])}
          >
            <Plus className="size-4" aria-hidden="true" /> {t("seedAdd")}
          </Button>
        )}
      </div>

      {error && (
        <div
          role="alert"
          className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
        >
          {error.message}
          {error.upgrade && (
            <>
              {" "}
              <Link href="/admin/billing" className="font-medium underline underline-offset-2">
                {t("seePlans")}
              </Link>
            </>
          )}
        </div>
      )}

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-0.5 text-xs text-muted-foreground" data-testid="research-limits">
          <p>
            {t("limitsLine", {
              searches: limits.maxSearchesPerRun,
              reads: limits.maxFetchesPerRun,
              minutes: roundMinutes(limits.totalDeadlineSeconds),
            })}
          </p>
          {status.remainingToday != null && (
            <p className={usedUp ? "text-destructive" : undefined}>
              {usedUp
                ? t("allowanceUsed", { max: limits.maxRunsPerUserPerDay })
                : t("runsLeft", { count: status.remainingToday, max: limits.maxRunsPerUserPerDay })}
            </p>
          )}
        </div>
        <Button type="button" onClick={submit} disabled={!canSubmit}>
          {busy ? (
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          ) : (
            <Search className="size-4" aria-hidden="true" />
          )}
          {busy ? t("starting") : t("start")}
        </Button>
      </div>
    </Card>
  );
}
