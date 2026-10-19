"use client";

import { useEffect, useReducer, useState } from "react";
import { ArrowLeft, CalendarClock, CheckCircle2, Loader2 } from "lucide-react";

import { EmptyState } from "../../components/ax/page-kit";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Skeleton } from "../../components/ui/skeleton";
import { api } from "../../lib/api";
import type { TutorDueReviews, TutorReviewAnswerResult } from "../../lib/api/tutorTypes";
import { useLocale, useTranslations } from "../../lib/i18n";
import { ChoiceList } from "./TutorQuiz";
import { initReviewState, reviewCardKey, reviewReducer } from "./tutorState";

function formatDate(iso: string, locale: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(date);
  } catch {
    return date.toDateString();
  }
}

/**
 * Loads the due cards, then hands them to the session. Split in two so the
 * reducer is created once, from the loaded cards, and never re-seeded.
 */
export default function ReviewSession({ onExit }: { onExit: () => void }) {
  const t = useTranslations("tutorPage");
  const [due, setDue] = useState<TutorDueReviews | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [round, setRound] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setDue(null);
    setError(null);
    api
      .getTutorDueReviews()
      .then((result: TutorDueReviews) => {
        if (!cancelled) setDue(result);
      })
      .catch((err: any) => {
        if (!cancelled) setError(err?.message || t("genericError"));
      });
    return () => {
      cancelled = true;
    };
    // `t` is stable per locale; a new round refetches.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [round]);

  const back = (
    <Button type="button" variant="ghost" size="sm" onClick={onExit} className="mb-4 px-0 hover:bg-transparent">
      <ArrowLeft aria-hidden="true" />
      {t("backToCourses")}
    </Button>
  );

  if (error) {
    return (
      <div data-testid="tutor-review">
        {back}
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
        <div className="mt-4">
          <Button type="button" variant="outline" size="sm" onClick={() => setRound((n) => n + 1)}>
            {t("retry")}
          </Button>
        </div>
      </div>
    );
  }

  if (!due) {
    return (
      <div data-testid="tutor-review" aria-busy="true">
        {back}
        <Skeleton className="h-[220px] rounded-xl" />
      </div>
    );
  }

  return (
    <div data-testid="tutor-review">
      {back}
      {/* Keyed on the round so "Review more" starts a fresh reducer. */}
      <ReviewCards key={round} due={due} onExit={onExit} onMore={() => setRound((n) => n + 1)} />
    </div>
  );
}

function ReviewCards({ due, onExit, onMore }: { due: TutorDueReviews; onExit: () => void; onMore: () => void }) {
  const t = useTranslations("tutorPage");
  const locale = useLocale();
  const [state, dispatch] = useReducer(reviewReducer, undefined, () => initReviewState(due.cards, due.totalDue));

  if (state.cards.length === 0) {
    return (
      <EmptyState
        icon={CheckCircle2}
        title={t("reviewEmptyTitle")}
        description={t("reviewEmptyBody")}
        action={
          <Button type="button" variant="outline" onClick={onExit}>
            {t("backToCourses")}
          </Button>
        }
      />
    );
  }

  if (state.phase === "done") {
    return (
      <Card className="gap-0 p-5" data-testid="tutor-review-done">
        <h3 className="text-sm font-semibold text-foreground">{t("reviewDoneTitle")}</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("reviewDoneScore", { correct: state.correct, total: state.answered })}
        </p>
        <p className="mt-1 text-sm text-muted-foreground">
          {state.remainingDue > 0 ? t("reviewMoreDue", { count: state.remainingDue }) : t("reviewAllCaughtUp")}
        </p>
        <div className="mt-4 flex flex-wrap gap-3">
          {state.remainingDue > 0 && (
            <Button type="button" onClick={onMore}>
              {t("reviewMore")}
            </Button>
          )}
          <Button type="button" variant="outline" onClick={onExit}>
            {t("backToCourses")}
          </Button>
        </div>
      </Card>
    );
  }

  const card = state.cards[state.index];
  if (!card) return null;
  const result = state.phase === "feedback" ? state.result : null;
  const submitting = state.phase === "submitting";
  const isLast = state.index + 1 >= state.cards.length;

  async function submit() {
    if (state.phase !== "answering" || state.selected == null) return;
    const selectedIndex = state.selected;
    dispatch({ type: "submit" });
    try {
      const graded: TutorReviewAnswerResult = await api.answerTutorReview(card.planId, card.cardId, selectedIndex);
      dispatch({ type: "graded", result: graded });
    } catch (err: any) {
      dispatch({ type: "failed", error: err?.message || t("genericError") });
    }
  }

  return (
    <Card className="gap-0 p-5" data-testid="tutor-review-card">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Badge variant="outline" className="max-w-full">
          <span className="truncate">{card.planTopic}</span>
        </Badge>
        <span className="text-xs text-muted-foreground">
          {t("reviewProgress", { current: state.index + 1, total: state.cards.length })}
        </span>
      </div>

      <p className="mt-3 text-sm font-medium leading-relaxed text-foreground [overflow-wrap:anywhere]">
        {card.question}
      </p>

      <div className="mt-3">
        <ChoiceList
          name={`review-${reviewCardKey(card)}`}
          legend={card.question}
          options={card.options}
          selected={state.selected}
          onSelect={(optionIndex) => dispatch({ type: "select", optionIndex })}
          disabled={submitting}
          verdict={result ? { selectedIndex: result.selectedIndex, correctIndex: result.correctIndex } : null}
        />
      </div>

      {state.error && (
        <Alert variant="destructive" className="mt-3">
          <AlertDescription>{state.error}</AlertDescription>
        </Alert>
      )}

      {result && (
        <div className="mt-3" aria-live="polite">
          <Alert variant={result.correct ? "success" : "warning"}>
            <AlertTitle>{result.correct ? t("correct") : t("incorrect")}</AlertTitle>
            <AlertDescription className="space-y-1.5 [overflow-wrap:anywhere]">
              {result.explanation && <p>{result.explanation}</p>}
              <p className="flex items-center gap-1.5">
                <CalendarClock className="size-4 shrink-0" aria-hidden="true" />
                {t("reviewNextDue", { date: formatDate(result.nextDueAt, locale), days: result.intervalDays })}
              </p>
            </AlertDescription>
          </Alert>
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-3">
        {result ? (
          <Button type="button" onClick={() => dispatch({ type: "next" })} data-testid="tutor-review-next">
            {isLast ? t("reviewFinish") : t("reviewNext")}
          </Button>
        ) : (
          <>
            <Button
              type="button"
              onClick={() => void submit()}
              disabled={state.selected == null || submitting}
              data-testid="tutor-review-submit"
            >
              {submitting && <Loader2 className="animate-spin" aria-hidden="true" />}
              {submitting ? t("checking") : t("checkAnswer")}
            </Button>
            {state.error && (
              <Button type="button" variant="ghost" onClick={() => dispatch({ type: "skip" })}>
                {t("reviewSkip")}
              </Button>
            )}
          </>
        )}
      </div>
    </Card>
  );
}
