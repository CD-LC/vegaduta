"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, CheckCircle2, Loader2 } from "lucide-react";

import RichText from "../../components/RichText";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Skeleton } from "../../components/ui/skeleton";
import { api } from "../../lib/api";
import type { TutorLesson, TutorPlan, TutorQuizAnswerResult } from "../../lib/api/tutorTypes";
import { useTranslations } from "../../lib/i18n";
import TutorChat, { LessonListenButton } from "./TutorChat";
import TutorQuiz from "./TutorQuiz";
import { applyQuizAnswer, findLesson, nextLessonId } from "./tutorState";

// One request per lesson at a time. The effect below can run twice for the
// same lesson (React re-runs effects on remount), and two concurrent writes of
// one plan make the server refuse the second with a 409.
const writesInFlight = new Map<string, Promise<TutorLesson>>();

function writeLesson(planId: string, lessonId: string): Promise<TutorLesson> {
  const key = `${planId}/${lessonId}`;
  const running = writesInFlight.get(key);
  if (running !== undefined) return running;
  const started: Promise<TutorLesson> = api.generateTutorLesson(planId, lessonId);
  writesInFlight.set(key, started);
  const clear = () => {
    writesInFlight.delete(key);
  };
  started.then(clear, clear);
  return started;
}

/**
 * One lesson: the explanation, the key points, a worked example, then the
 * quiz. A lesson is written the first time it is opened (one model call, a
 * few seconds) and cached by the server, so reopening it is instant.
 */
export default function LessonView({
  plan,
  lessonId,
  onBack,
  onLessonChanged,
  onPlanChanged,
  onOpenLesson,
}: {
  plan: TutorPlan;
  lessonId: string;
  onBack: () => void;
  /** A lesson was generated or one of its questions graded. */
  onLessonChanged: (lesson: TutorLesson) => void;
  /** The server returned the whole plan (after "mark complete"). */
  onPlanChanged: (plan: TutorPlan) => void;
  onOpenLesson: (lessonId: string) => void;
}) {
  const t = useTranslations("tutorPage");
  const entry = findLesson(plan, lessonId);
  const lesson = entry?.lesson || null;
  const needsWriting = lesson != null && !lesson.generated;

  const [writeError, setWriteError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [completing, setCompleting] = useState(false);
  const [completeError, setCompleteError] = useState<string | null>(null);
  // The latest callback, without making it a dependency of the effect below.
  const onLessonChangedRef = useRef(onLessonChanged);
  useEffect(() => {
    onLessonChangedRef.current = onLessonChanged;
  });

  useEffect(() => {
    if (!needsWriting) return undefined;
    let cancelled = false;
    setWriteError(null);
    writeLesson(plan.id, lessonId)
      .then((written: TutorLesson) => {
        if (!cancelled) onLessonChangedRef.current(written);
      })
      .catch((err: any) => {
        if (!cancelled) setWriteError(err?.message || "");
      });
    return () => {
      cancelled = true;
    };
  }, [needsWriting, plan.id, lessonId, attempt]);

  const back = (
    <Button type="button" variant="ghost" size="sm" onClick={onBack} className="mb-4 px-0 hover:bg-transparent">
      <ArrowLeft aria-hidden="true" />
      {t("backToCourse")}
    </Button>
  );

  if (!lesson) {
    return (
      <div data-testid="tutor-lesson">
        {back}
        <Alert variant="destructive">
          <AlertDescription>{t("lessonMissing")}</AlertDescription>
        </Alert>
      </div>
    );
  }

  const header = (
    <div className="mb-6">
      <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground [overflow-wrap:anywhere]">
        {entry?.moduleTitle}
      </p>
      <div className="mt-1 flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold tracking-tight text-foreground [overflow-wrap:anywhere]">
          {lesson.title}
        </h2>
        {lesson.status === "COMPLETED" && (
          <Badge variant="accent">
            <CheckCircle2 aria-hidden="true" />
            {t("lessonCompleted")}
          </Badge>
        )}
      </div>
      {lesson.objective && (
        <p className="mt-1 max-w-[68ch] text-sm text-muted-foreground [overflow-wrap:anywhere]">{lesson.objective}</p>
      )}
    </div>
  );

  if (needsWriting) {
    return (
      <div data-testid="tutor-lesson">
        {back}
        {header}
        {writeError != null ? (
          <>
            <Alert variant="destructive">
              <AlertDescription>{writeError || t("lessonWriteFailed")}</AlertDescription>
            </Alert>
            <div className="mt-4">
              <Button type="button" variant="outline" size="sm" onClick={() => setAttempt((n) => n + 1)}>
                {t("retry")}
              </Button>
            </div>
          </>
        ) : (
          <div aria-busy="true">
            <p className="mb-3 flex items-center gap-2 text-sm text-muted-foreground" role="status">
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
              {t("lessonWriting")}
            </p>
            <Skeleton className="h-[260px] rounded-xl" />
          </div>
        )}
      </div>
    );
  }

  // A lesson can be marked written yet carry no explanation (a hand-made
  // plan with only a quiz). Nothing more is coming, so show what there is -
  // the quiz, the tutor, the complete control - instead of waiting forever.
  const content = lesson.content || null;
  const followingId = nextLessonId(plan, lesson.id);

  function handleAnswered(result: TutorQuizAnswerResult) {
    if (lesson) onLessonChanged(applyQuizAnswer(lesson, result));
  }

  async function complete() {
    if (completing || !lesson) return;
    setCompleting(true);
    setCompleteError(null);
    try {
      const updated: TutorPlan = await api.completeTutorLesson(plan.id, lesson.id);
      onPlanChanged(updated);
      if (followingId) onOpenLesson(followingId);
      else onBack();
    } catch (err: any) {
      setCompleteError(err?.message || t("genericError"));
    } finally {
      setCompleting(false);
    }
  }

  return (
    <div data-testid="tutor-lesson">
      {back}
      {header}

      <div className="space-y-6">
        {content?.explanation && (
          <Card className="gap-0 p-5">
            <div className="mb-3 flex justify-end">
              <LessonListenButton text={content.explanation} />
            </div>
            <RichText text={content.explanation} />
          </Card>
        )}

        {content?.keyPoints && content.keyPoints.length > 0 && (
          <Card className="gap-0 p-5">
            <h3 className="text-sm font-semibold text-foreground">{t("keyPoints")}</h3>
            <ul className="mt-2 ml-5 list-disc space-y-1 text-sm leading-relaxed text-foreground">
              {content.keyPoints.map((point, i) => (
                <li key={i} className="[overflow-wrap:anywhere]">
                  {point}
                </li>
              ))}
            </ul>
          </Card>
        )}

        {content?.workedExample && (
          <Card className="gap-0 p-5">
            <h3 className="mb-2 text-sm font-semibold text-foreground">{t("workedExample")}</h3>
            <RichText text={content.workedExample} />
          </Card>
        )}

        {lesson.quiz && lesson.quiz.length > 0 && (
          <TutorQuiz
            key={lesson.id}
            planId={plan.id}
            lessonId={lesson.id}
            questions={lesson.quiz}
            onAnswered={handleAnswered}
          />
        )}

        <TutorChat key={`chat-${lesson.id}`} plan={plan} lesson={lesson} moduleTitle={entry?.moduleTitle} />

        {completeError && (
          <Alert variant="destructive">
            <AlertDescription>{completeError}</AlertDescription>
          </Alert>
        )}

        <div className="flex flex-wrap items-center gap-3">
          {lesson.status === "COMPLETED" ? (
            followingId ? (
              <Button type="button" onClick={() => onOpenLesson(followingId)} data-testid="tutor-next-lesson">
                {t("nextLesson")}
                <ArrowRight aria-hidden="true" />
              </Button>
            ) : (
              <Button type="button" variant="outline" onClick={onBack}>
                {t("backToCourse")}
              </Button>
            )
          ) : (
            <Button type="button" onClick={() => void complete()} disabled={completing} data-testid="tutor-complete">
              {completing ? <Loader2 className="animate-spin" aria-hidden="true" /> : <CheckCircle2 aria-hidden="true" />}
              {followingId ? t("completeAndNext") : t("completeLesson")}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
