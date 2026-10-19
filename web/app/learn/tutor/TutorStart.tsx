"use client";

import { useMemo, useState } from "react";
import { Loader2, Sparkles } from "lucide-react";

import Select from "../../components/Select";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { SUPPORTED_LOCALES, useLocale, useTranslations } from "../../lib/i18n";
import type { TutorGeneratePlanRequest, TutorLevel } from "../../lib/api/tutorTypes";
import {
  DEFAULT_MAX_TOPIC_CHARS,
  LEVEL_LABEL_KEY,
  TUTOR_LEVELS,
  defaultTutorLanguage,
  topicProblem,
} from "./tutorState";

// Suggestion chips: en.json keys, so they read in the visitor's own language.
const SUGGESTION_KEYS = ["chipPhotosynthesis", "chipSpanish", "chipPython", "chipFinance", "chipGuitar", "chipHistory"];

/**
 * The start form: any topic, a level, and the language to be taught in.
 * Validation mirrors the server rule so an obviously short or long topic
 * never costs a request; every other refusal comes back from the server in
 * plain language and is shown under the form by the page.
 */
export default function TutorStart({
  maxTopicChars = DEFAULT_MAX_TOPIC_CHARS,
  busy,
  error,
  onGenerate,
}: {
  maxTopicChars?: number;
  busy: boolean;
  error: string | null;
  onGenerate: (request: TutorGeneratePlanRequest) => void;
}) {
  const t = useTranslations("tutorPage");
  const locale = useLocale();
  const languageCodes = useMemo(() => SUPPORTED_LOCALES.map((l) => l.code), []);
  const [topic, setTopic] = useState("");
  const [level, setLevel] = useState<TutorLevel>("beginner");
  const [language, setLanguage] = useState(() => defaultTutorLanguage(locale, languageCodes));
  const [touched, setTouched] = useState(false);

  const problem = topicProblem(topic, maxTopicChars);
  const languageOptions = useMemo(() => SUPPORTED_LOCALES.map((l) => ({ value: l.code, label: l.label })), []);
  const levelOptions = TUTOR_LEVELS.map((value) => ({ value, label: t(LEVEL_LABEL_KEY[value]) }));

  function submit() {
    setTouched(true);
    if (problem || busy) return;
    onGenerate({ topic: topic.trim(), level, language });
  }

  return (
    <Card className="gap-0 p-5" data-testid="tutor-start">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
        className="space-y-4"
      >
        <div className="space-y-1.5">
          <Label htmlFor="tutor-topic">{t("topicLabel")}</Label>
          <Textarea
            id="tutor-topic"
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
            onBlur={() => setTouched(true)}
            placeholder={t("topicPlaceholder")}
            rows={3}
            maxLength={maxTopicChars}
            disabled={busy}
            aria-invalid={touched && problem ? true : undefined}
            aria-describedby="tutor-topic-hint"
          />
          <p id="tutor-topic-hint" className="text-xs text-muted-foreground">
            {touched && problem
              ? t(problem, { max: maxTopicChars })
              : t("topicHint", { count: topic.trim().length, max: maxTopicChars })}
          </p>
        </div>

        <div role="group" className="flex flex-wrap gap-2" aria-label={t("suggestionsLabel")}>
          {SUGGESTION_KEYS.map((key) => {
            const text = t(key);
            return (
              <Button
                key={key}
                type="button"
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={() => setTopic(text)}
              >
                {text}
              </Button>
            );
          })}
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="tutor-level">{t("levelLabel")}</Label>
            <Select
              id="tutor-level"
              value={level}
              onChange={(e: any) => setLevel(e.target.value as TutorLevel)}
              options={levelOptions}
              disabled={busy}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="tutor-language">{t("languageLabel")}</Label>
            <Select
              id="tutor-language"
              value={language}
              onChange={(e: any) => setLanguage(String(e.target.value))}
              options={languageOptions}
              searchable
              disabled={busy}
            />
            <p className="text-xs text-muted-foreground">{t("languageHint")}</p>
          </div>
        </div>

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={busy} data-testid="tutor-generate">
            {busy ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Sparkles aria-hidden="true" />}
            {busy ? t("generating") : t("generate")}
          </Button>
          {busy && (
            <span className="text-xs text-muted-foreground" role="status">
              {t("generatingHint")}
            </span>
          )}
        </div>
      </form>
    </Card>
  );
}
