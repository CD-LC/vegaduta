"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { ArrowLeft, BookOpenCheck, GraduationCap, RotateCcw } from "lucide-react";

import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState, Section } from "../../components/ax/page-kit";
import { usePageReady } from "../../components/RouteTransition";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Skeleton } from "../../components/ui/skeleton";
import { api } from "../../lib/api";
import type {
  TutorGeneratePlanRequest,
  TutorLesson,
  TutorPlan,
  TutorPlanSummary,
  TutorStatus,
} from "../../lib/api/tutorTypes";
import { useTranslations } from "../../lib/i18n";
import LessonView from "./LessonView";
import PlanView, { ProgressBar, languageLabel } from "./PlanView";
import ReviewSession from "./ReviewSession";
import TutorStart from "./TutorStart";
import { LEVEL_LABEL_KEY, progressPercent, replaceLesson, reviewRequested } from "./tutorState";

/**
 * /learn/tutor - a personal tutor for any topic.
 *
 * Type a topic, get a course (modules -> lessons), study a lesson, answer its
 * quiz, and come back for the questions you missed. Everything is stored and
 * graded by core (/api/tutor/**), scoped to the signed-in member.
 *
 * The feature is behind a server flag. With it off core maps no /api/tutor
 * route at all, so the status probe answers 404 and this page shows a plain
 * "not available here" state instead of controls that cannot work.
 *
 * `?review=1` (the link in the review-due notification) opens the review
 * session directly.
 */

type Availability =
  | { kind: "loading" }
  | { kind: "unavailable" }
  | { kind: "error"; message: string }
  | { kind: "ready"; status: TutorStatus };

type View =
  | { kind: "home" }
  | { kind: "plan"; planId: string }
  | { kind: "lesson"; planId: string; lessonId: string }
  | { kind: "review" };

export default function TutorPage() {
  const t = useTranslations("tutorPage");
  const [availability, setAvailability] = useState<Availability>({ kind: "loading" });
  const [plans, setPlans] = useState<TutorPlanSummary[]>([]);
  const [plansLoaded, setPlansLoaded] = useState(false);
  const [view, setView] = useState<View>({ kind: "home" });
  const [plan, setPlan] = useState<TutorPlan | null>(null);
  const [planError, setPlanError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [probe, setProbe] = useState(0);

  const refreshPlans = useCallback(() => {
    return api
      .listTutorPlans()
      .then((rows: TutorPlanSummary[]) => setPlans(rows || []))
      .catch(() => {
        /* toJson already surfaced the server's message */
      })
      .finally(() => setPlansLoaded(true));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setAvailability({ kind: "loading" });
    api
      .getTutorStatus()
      .then((status: TutorStatus) => {
        if (cancelled) return;
        if (!status || status.enabled === false) {
          setAvailability({ kind: "unavailable" });
          return;
        }
        setAvailability({ kind: "ready", status });
        // The notification link lands here with ?review=1.
        if (typeof window !== "undefined" && reviewRequested(window.location.search)) {
          setView({ kind: "review" });
        }
        void refreshPlans();
      })
      .catch((err: any) => {
        if (cancelled) return;
        // 404 = the feature is switched off on this deployment (no route is
        // mapped), which is a state, not a failure.
        if (err?.status === 404) setAvailability({ kind: "unavailable" });
        else setAvailability({ kind: "error", message: err?.message || "" });
      });
    return () => {
      cancelled = true;
    };
  }, [probe, refreshPlans]);

  const settled =
    availability.kind === "unavailable" ||
    availability.kind === "error" ||
    (availability.kind === "ready" && plansLoaded);
  usePageReady(settled);

  const goHome = useCallback(() => {
    setView({ kind: "home" });
    setPlan(null);
    setPlanError(null);
    void refreshPlans();
  }, [refreshPlans]);

  const openPlan = useCallback((planId: string) => {
    setView({ kind: "plan", planId });
    setPlan((current) => (current && current.id === planId ? current : null));
    setPlanError(null);
    api
      .getTutorPlan(planId)
      .then((loaded: TutorPlan) => setPlan(loaded))
      .catch((err: any) => setPlanError(err?.message || ""));
  }, []);

  const generate = useCallback(
    async (request: TutorGeneratePlanRequest) => {
      setGenerating(true);
      setGenerateError(null);
      try {
        const created: TutorPlan = await api.generateTutorPlan(request);
        setPlan(created);
        setView({ kind: "plan", planId: created.id });
        void refreshPlans();
      } catch (err: any) {
        setGenerateError(err?.message || t("genericError"));
      } finally {
        setGenerating(false);
      }
    },
    [refreshPlans, t]
  );

  const deletePlan = useCallback(async () => {
    if (!plan || deleting) return;
    setDeleting(true);
    try {
      await api.deleteTutorPlan(plan.id);
      goHome();
    } catch {
      /* surfaced by toJson */
    } finally {
      setDeleting(false);
    }
  }, [plan, deleting, goHome]);

  const onLessonChanged = useCallback((lesson: TutorLesson) => {
    setPlan((current) => (current ? replaceLesson(current, lesson) : current));
  }, []);

  const totalDue = plans.reduce((sum, row) => sum + (row.dueReviews || 0), 0);
  const limits = availability.kind === "ready" ? availability.status.limits : null;
  const atPlanCap = limits != null && limits.maxPlansPerUser > 0 && plans.length >= limits.maxPlansPerUser;

  let body: ReactNode;
  if (availability.kind === "loading") {
    body = (
      <div className="space-y-4" aria-busy="true">
        <Skeleton className="h-[220px] rounded-xl" />
        <Skeleton className="h-[120px] rounded-xl" />
      </div>
    );
  } else if (availability.kind === "unavailable") {
    body = (
      <EmptyState
        icon={GraduationCap}
        title={t("unavailableTitle")}
        description={t("unavailableBody")}
        action={
          <Button asChild variant="outline">
            <Link href="/help">
              <ArrowLeft aria-hidden="true" />
              {t("backToHelp")}
            </Link>
          </Button>
        }
      />
    );
  } else if (availability.kind === "error") {
    body = (
      <div>
        <Alert variant="destructive">
          <AlertDescription>{availability.message || t("genericError")}</AlertDescription>
        </Alert>
        <div className="mt-4">
          <Button type="button" variant="outline" size="sm" onClick={() => setProbe((n) => n + 1)}>
            {t("retry")}
          </Button>
        </div>
      </div>
    );
  } else if (view.kind === "review") {
    body = <ReviewSession onExit={goHome} />;
  } else if (view.kind === "plan" || view.kind === "lesson") {
    if (planError != null) {
      body = (
        <div>
          <Alert variant="destructive">
            <AlertDescription>{planError || t("genericError")}</AlertDescription>
          </Alert>
          <div className="mt-4">
            <Button type="button" variant="outline" size="sm" onClick={goHome}>
              {t("backToCourses")}
            </Button>
          </div>
        </div>
      );
    } else if (!plan || plan.id !== view.planId) {
      body = (
        <div className="space-y-4" aria-busy="true">
          <Skeleton className="h-[140px] rounded-xl" />
          <Skeleton className="h-[260px] rounded-xl" />
        </div>
      );
    } else if (view.kind === "lesson") {
      body = (
        <LessonView
          key={view.lessonId}
          plan={plan}
          lessonId={view.lessonId}
          onBack={() => setView({ kind: "plan", planId: plan.id })}
          onLessonChanged={onLessonChanged}
          onPlanChanged={setPlan}
          onOpenLesson={(lessonId) => setView({ kind: "lesson", planId: plan.id, lessonId })}
        />
      );
    } else {
      body = (
        <PlanView
          plan={plan}
          deleting={deleting}
          onBack={goHome}
          onOpenLesson={(lessonId) => setView({ kind: "lesson", planId: plan.id, lessonId })}
          onReview={() => setView({ kind: "review" })}
          onDelete={() => void deletePlan()}
        />
      );
    }
  } else {
    body = (
      <div className="space-y-10">
        {totalDue > 0 && (
          <Card className="flex-row flex-wrap items-center justify-between gap-4 p-5" data-testid="tutor-due-banner">
            <div className="min-w-0">
              <h2 className="text-sm font-semibold text-foreground">{t("dueBannerTitle", { count: totalDue })}</h2>
              <p className="mt-1 text-sm text-muted-foreground">{t("dueBannerBody")}</p>
            </div>
            <Button type="button" className="shrink-0" onClick={() => setView({ kind: "review" })}>
              <RotateCcw aria-hidden="true" />
              {t("startReview")}
            </Button>
          </Card>
        )}

        <Section title={t("startHeading")} description={t("startIntro")}>
          {availability.status.generationAvailable === false ? (
            <Alert>
              <AlertDescription>{t("generationUnavailable")}</AlertDescription>
            </Alert>
          ) : atPlanCap ? (
            <Alert>
              <AlertDescription>{t("planCapReached", { max: limits?.maxPlansPerUser ?? 0 })}</AlertDescription>
            </Alert>
          ) : (
            <TutorStart
              maxTopicChars={limits?.maxTopicChars || undefined}
              busy={generating}
              error={generateError}
              onGenerate={(request) => void generate(request)}
            />
          )}
        </Section>

        <Section title={t("coursesHeading")}>
          {!plansLoaded ? (
            <Skeleton className="h-[120px] rounded-xl" />
          ) : plans.length === 0 ? (
            <EmptyState icon={BookOpenCheck} title={t("noCoursesTitle")} description={t("noCoursesBody")} />
          ) : (
            <div className="grid gap-4 md:grid-cols-2">
              {plans.map((row) => {
                const percent = progressPercent(row.completedLessons, row.totalLessons);
                return (
                  <Card key={row.id} className="gap-0 p-5" data-testid={`tutor-course-${row.id}`}>
                    <div className="flex items-start justify-between gap-3">
                      <h3 className="min-w-0 text-sm font-semibold text-foreground [overflow-wrap:anywhere]">
                        {row.topic}
                      </h3>
                      {row.dueReviews > 0 && (
                        <Badge variant="accent" className="shrink-0">
                          {t("reviewsDue", { count: row.dueReviews })}
                        </Badge>
                      )}
                    </div>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Badge variant="outline">{t(LEVEL_LABEL_KEY[row.level] || "levelBeginner")}</Badge>
                      <Badge variant="outline">{languageLabel(row.language)}</Badge>
                      {row.status === "COMPLETED" && <Badge variant="outline">{t("courseCompleted")}</Badge>}
                    </div>
                    <div className="mt-4 space-y-1.5">
                      <ProgressBar value={percent} label={t("progressLabel")} />
                      <p className="text-xs text-muted-foreground">
                        {t("lessonsDone", { done: row.completedLessons, total: row.totalLessons })}
                      </p>
                    </div>
                    <div className="mt-4">
                      <Button type="button" variant="outline" size="sm" onClick={() => openPlan(row.id)}>
                        {t("openCourse")}
                      </Button>
                    </div>
                  </Card>
                );
              })}
            </div>
          )}
        </Section>
      </div>
    );
  }

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} icon={GraduationCap} />
      {body}
    </AppShell>
  );
}
