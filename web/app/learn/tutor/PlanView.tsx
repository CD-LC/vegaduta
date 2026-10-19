"use client";

import { ArrowLeft, CheckCircle2, Circle, CircleDot, PlayCircle, RotateCcw, Trash2 } from "lucide-react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../../components/ui/alert-dialog";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../../components/ui/accordion";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import type { TutorLessonStatus, TutorPlan } from "../../lib/api/tutorTypes";
import { SUPPORTED_LOCALES, useTranslations } from "../../lib/i18n";
import { LEVEL_LABEL_KEY, firstOpenLessonId, progressPercent } from "./tutorState";

const STATUS_LABEL_KEY: Record<TutorLessonStatus, string> = {
  NOT_STARTED: "lessonNotStarted",
  IN_PROGRESS: "lessonInProgress",
  COMPLETED: "lessonCompleted",
};

function StatusIcon({ status }: { status: TutorLessonStatus }) {
  if (status === "COMPLETED") return <CheckCircle2 className="size-4 shrink-0 text-primary" aria-hidden="true" />;
  if (status === "IN_PROGRESS") return <CircleDot className="size-4 shrink-0 text-primary" aria-hidden="true" />;
  return <Circle className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />;
}

/** The language's own name when the picker knows it, else the raw tag. */
export function languageLabel(tag: string): string {
  const base = (tag || "").split(/[-_]/)[0];
  const known = SUPPORTED_LOCALES.find((l) => l.code === tag) || SUPPORTED_LOCALES.find((l) => l.code === base);
  return known ? known.label : tag;
}

export function ProgressBar({ value, label }: { value: number; label: string }) {
  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={value}
      aria-label={label}
      className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
    >
      <div className="h-full rounded-full bg-primary transition-[width]" style={{ width: `${value}%` }} />
    </div>
  );
}

/** One course: its modules, each lesson's status, and where to continue. */
export default function PlanView({
  plan,
  deleting,
  onBack,
  onOpenLesson,
  onReview,
  onDelete,
}: {
  plan: TutorPlan;
  deleting: boolean;
  onBack: () => void;
  onOpenLesson: (lessonId: string) => void;
  onReview: () => void;
  onDelete: () => void;
}) {
  const t = useTranslations("tutorPage");
  const percent = progressPercent(plan.completedLessons, plan.totalLessons);
  const continueId = firstOpenLessonId(plan);
  const moduleIds = plan.modules.map((module) => module.id);

  return (
    <div data-testid="tutor-plan">
      <Button type="button" variant="ghost" size="sm" onClick={onBack} className="mb-4 px-0 hover:bg-transparent">
        <ArrowLeft aria-hidden="true" />
        {t("backToCourses")}
      </Button>

      <Card className="mb-6 gap-0 p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold tracking-tight text-foreground [overflow-wrap:anywhere]">
              {plan.topic}
            </h2>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Badge variant="outline">{t(LEVEL_LABEL_KEY[plan.level] || "levelBeginner")}</Badge>
              <Badge variant="outline">{languageLabel(plan.language)}</Badge>
              {plan.status === "COMPLETED" && <Badge variant="accent">{t("courseCompleted")}</Badge>}
            </div>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            {plan.dueReviews > 0 && (
              <Button type="button" variant="outline" size="sm" onClick={onReview}>
                <RotateCcw aria-hidden="true" />
                {t("reviewsDue", { count: plan.dueReviews })}
              </Button>
            )}
            {continueId && (
              <Button type="button" size="sm" onClick={() => onOpenLesson(continueId)} data-testid="tutor-continue">
                <PlayCircle aria-hidden="true" />
                {plan.completedLessons > 0 ? t("continueCourse") : t("startCourse")}
              </Button>
            )}
          </div>
        </div>

        {plan.goal && <p className="mt-3 text-sm text-muted-foreground [overflow-wrap:anywhere]">{plan.goal}</p>}

        <div className="mt-4 space-y-1.5">
          <ProgressBar value={percent} label={t("progressLabel")} />
          <p className="text-xs text-muted-foreground">
            {t("lessonsDone", { done: plan.completedLessons, total: plan.totalLessons })}
          </p>
        </div>
      </Card>

      <Card className="gap-0 px-5 py-1">
        <Accordion type="multiple" defaultValue={moduleIds}>
          {plan.modules.map((module, moduleIndex) => (
            <AccordionItem key={module.id} value={module.id}>
              <AccordionTrigger className="py-4 text-sm">
                <span className="min-w-0 [overflow-wrap:anywhere]">
                  {t("moduleHeading", { number: moduleIndex + 1, title: module.title })}
                </span>
              </AccordionTrigger>
              <AccordionContent>
                <ul className="list-none space-y-1 p-0">
                  {module.lessons.map((lesson) => (
                    <li key={lesson.id}>
                      <Button
                        type="button"
                        variant="ghost"
                        onClick={() => onOpenLesson(lesson.id)}
                        className="h-auto w-full items-start justify-start gap-3 whitespace-normal px-2 py-2 text-left font-normal"
                        data-testid={`tutor-lesson-row-${lesson.id}`}
                      >
                        <span className="mt-0.5">
                          <StatusIcon status={lesson.status} />
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block font-medium [overflow-wrap:anywhere]">{lesson.title}</span>
                          {lesson.objective && (
                            <span className="mt-0.5 block text-xs text-muted-foreground [overflow-wrap:anywhere]">
                              {lesson.objective}
                            </span>
                          )}
                        </span>
                        <span className="shrink-0 text-xs text-muted-foreground">
                          {t(STATUS_LABEL_KEY[lesson.status] || "lessonNotStarted")}
                        </span>
                      </Button>
                    </li>
                  ))}
                </ul>
              </AccordionContent>
            </AccordionItem>
          ))}
        </Accordion>
      </Card>

      <div className="mt-6">
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button type="button" variant="ghost" size="sm" disabled={deleting} className="text-muted-foreground">
              <Trash2 aria-hidden="true" />
              {t("deleteCourse")}
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{t("deleteCourseTitle")}</AlertDialogTitle>
              <AlertDialogDescription>{t("deleteCourseBody")}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
              <AlertDialogAction onClick={onDelete}>{t("deleteCourseConfirm")}</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </div>
  );
}
