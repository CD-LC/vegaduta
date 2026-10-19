"use client";

import { useReducer } from "react";
import { CheckCircle2, Loader2, RotateCcw, XCircle } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { api } from "../../lib/api";
import type { TutorQuizAnswerResult, TutorQuizQuestion } from "../../lib/api/tutorTypes";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { initQuizState, quizReducer, quizScore } from "./tutorState";

/**
 * A single-choice list shared by the lesson quiz and the review session.
 * Before the verdict it is a plain radio group; after it, the learner's pick
 * and the right answer are marked with an icon and a word, never colour alone.
 */
export function ChoiceList({
  name,
  legend,
  options,
  selected,
  onSelect,
  disabled,
  verdict,
}: {
  name: string;
  legend: string;
  options: string[];
  selected: number | null;
  onSelect: (optionIndex: number) => void;
  disabled: boolean;
  verdict: { selectedIndex: number; correctIndex: number } | null;
}) {
  const t = useTranslations("tutorPage");
  return (
    <fieldset className="space-y-2" disabled={disabled}>
      <legend className="sr-only">{legend}</legend>
      {options.map((option, optionIndex) => {
        const isCorrect = verdict != null && optionIndex === verdict.correctIndex;
        const isWrongPick = verdict != null && optionIndex === verdict.selectedIndex && !isCorrect;
        const checked = verdict ? optionIndex === verdict.selectedIndex : optionIndex === selected;
        return (
          <label
            key={optionIndex}
            className={cn(
              "flex cursor-pointer items-start gap-3 rounded-lg border border-border bg-card px-3 py-2.5 text-sm text-foreground transition-colors",
              !verdict && checked && "border-primary bg-primary/10",
              !verdict && !disabled && "hover:bg-accent",
              isCorrect && "border-primary bg-primary/10",
              isWrongPick && "border-destructive/50 bg-destructive/10",
              (disabled || verdict) && "cursor-default"
            )}
          >
            <input
              type="radio"
              name={name}
              className="mt-0.5 accent-primary"
              checked={checked}
              disabled={disabled || verdict != null}
              onChange={() => onSelect(optionIndex)}
            />
            <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{option}</span>
            {isCorrect && (
              <span className="flex shrink-0 items-center gap-1 text-xs font-medium text-primary">
                <CheckCircle2 className="size-4" aria-hidden="true" />
                {t("rightAnswer")}
              </span>
            )}
            {isWrongPick && (
              <span className="flex shrink-0 items-center gap-1 text-xs font-medium text-destructive">
                <XCircle className="size-4" aria-hidden="true" />
                {t("yourAnswer")}
              </span>
            )}
          </label>
        );
      })}
    </fieldset>
  );
}

/**
 * The lesson quiz: one question at a time, submitted to the server, which
 * grades it and returns the right answer and why. Nothing here knows the
 * answer before the server says so. A wrong answer is flagged "added to
 * review" exactly when the server reports it created a review card.
 */
export default function TutorQuiz({
  planId,
  lessonId,
  questions,
  onAnswered,
}: {
  planId: string;
  lessonId: string;
  questions: TutorQuizQuestion[];
  onAnswered: (result: TutorQuizAnswerResult) => void;
}) {
  const t = useTranslations("tutorPage");
  const [state, dispatch] = useReducer(quizReducer, questions, initQuizState);

  const total = state.order.length;
  if (total === 0) return null;

  const score = quizScore(state);

  if (state.phase === "done") {
    return (
      <Card className="gap-0 p-5" data-testid="tutor-quiz-done">
        <h3 className="text-sm font-semibold text-foreground">{t("quizDoneTitle")}</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("quizScore", { correct: score.correct, total: score.total })}
        </p>
        {score.correct < score.answered && (
          <p className="mt-1 text-sm text-muted-foreground">{t("quizMistakesQueued")}</p>
        )}
        <ul className="mt-4 list-none space-y-2 p-0">
          {questions.map((question, i) => {
            const verdict = state.verdicts[question.id];
            if (!verdict) return null;
            return (
              <li key={question.id} className="flex items-start gap-2 text-sm text-foreground">
                {verdict.correct ? (
                  <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
                ) : (
                  <XCircle className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden="true" />
                )}
                <span className="min-w-0 [overflow-wrap:anywhere]">
                  <span className="sr-only">{verdict.correct ? t("correct") : t("incorrect")}: </span>
                  {i + 1}. {question.question}
                </span>
              </li>
            );
          })}
        </ul>
        <div className="mt-4">
          <Button type="button" variant="outline" size="sm" onClick={() => dispatch({ type: "restart" })}>
            <RotateCcw aria-hidden="true" />
            {t("quizRetake")}
          </Button>
        </div>
      </Card>
    );
  }

  const question = questions.find((q) => q.id === state.order[state.index]);
  if (!question) return null;
  const verdict = state.phase === "feedback" ? state.verdicts[question.id] : null;
  const submitting = state.phase === "submitting";
  const isLast = state.index + 1 >= total;

  async function submit() {
    if (state.phase !== "answering" || state.selected == null || !question) return;
    const selectedIndex = state.selected;
    dispatch({ type: "submit" });
    try {
      const result: TutorQuizAnswerResult = await api.answerTutorQuiz(planId, lessonId, question.id, selectedIndex);
      dispatch({ type: "graded", result });
      onAnswered(result);
    } catch (err: any) {
      dispatch({ type: "failed", error: err?.message || t("genericError") });
    }
  }

  return (
    <Card className="gap-0 p-5" data-testid="tutor-quiz">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-semibold text-foreground">{t("quizTitle")}</h3>
        <span className="text-xs text-muted-foreground">
          {t("quizProgress", { current: state.index + 1, total })}
        </span>
      </div>

      <p className="mt-3 text-sm font-medium leading-relaxed text-foreground [overflow-wrap:anywhere]">
        {question.question}
      </p>

      <div className="mt-3">
        <ChoiceList
          name={`quiz-${lessonId}-${question.id}`}
          legend={question.question}
          options={question.options}
          selected={state.selected}
          onSelect={(optionIndex) => dispatch({ type: "select", optionIndex })}
          disabled={submitting}
          verdict={verdict}
        />
      </div>

      {state.error && (
        <Alert variant="destructive" className="mt-3">
          <AlertDescription>{state.error}</AlertDescription>
        </Alert>
      )}

      {verdict && (
        <div className="mt-3" aria-live="polite">
          <Alert variant={verdict.correct ? "success" : "warning"}>
            <AlertTitle className="flex flex-wrap items-center gap-2">
              {verdict.correct ? t("correct") : t("incorrect")}
              {verdict.addedToReview && <Badge variant="outline">{t("addedToReview")}</Badge>}
            </AlertTitle>
            {verdict.explanation && (
              <AlertDescription className="[overflow-wrap:anywhere]">{verdict.explanation}</AlertDescription>
            )}
          </Alert>
        </div>
      )}

      <div className="mt-4 flex items-center gap-3">
        {verdict ? (
          <Button type="button" onClick={() => dispatch({ type: "next" })} data-testid="tutor-quiz-next">
            {isLast ? t("quizSeeResults") : t("quizNext")}
          </Button>
        ) : (
          <Button
            type="button"
            onClick={() => void submit()}
            disabled={state.selected == null || submitting}
            data-testid="tutor-quiz-submit"
          >
            {submitting && <Loader2 className="animate-spin" aria-hidden="true" />}
            {submitting ? t("checking") : t("checkAnswer")}
          </Button>
        )}
      </div>
    </Card>
  );
}
