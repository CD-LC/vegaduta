"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  ArrowUpRight,
  BookOpenCheck,
  Compass,
  GraduationCap,
  Lightbulb,
  MonitorSmartphone,
  ShieldCheck,
} from "lucide-react";

import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { EmptyState, Section, StatCard } from "../components/ax/page-kit";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Skeleton } from "../components/ui/skeleton";
import { useAuth } from "../components/AuthProvider";
import { hasAcademy } from "../lib/academyUrl";
import { hiddenOnPersonalInstall } from "../lib/personalInstall";
import CurriculumMap from "./CurriculumMap";
import { LearnLessonLink } from "./LearnThisButton";
import TutorEntryCard from "./tutor/TutorEntryCard";
import { CONSOLE_SCREEN_LESSONS } from "./consoleLessonMap";
import {
  CURRICULUM,
  IN_DESIGN_LESSON_COUNT,
  LIVE_LESSON_COUNT,
  academyConceptsUrl,
  academyJourneyUrl,
} from "./curriculum";
import {
  completedInLevel,
  resolveLearnProgressLoader,
  type LearnProgressSnapshot,
} from "./learnProgress";

/**
 * /learn — the Academy, indexed inside the console.
 *
 * Built as a sibling of /help: same AppShell + PageHeader chrome, same shadcn
 * token layer, same jump-pill navigation, no new visual language. /help answers
 * "how does this product work"; /learn answers "teach me the concept behind the
 * screen I am staring at", and it does that by mapping console routes to real
 * Academy lessons (consoleLessonMap.ts) rather than by restating the course
 * here.
 *
 * Everything on this page renders from in-repo constants, so it works with the
 * backend down. The only network-dependent element is the progress strip, and
 * it has no endpoint yet — see learnProgress.ts.
 *
 * All Academy links resolve through lib/academyUrl.ts. No hostname is written
 * anywhere in this route.
 */

/** The states the progress strip can be in. `unwired` is today's reality. */
type ProgressState =
  | { kind: "unwired" }
  | { kind: "loading" }
  | { kind: "noLearner" }
  | { kind: "ready"; snapshot: LearnProgressSnapshot }
  | { kind: "error" };

const JUMP_SECTIONS = [
  { id: "screens", label: "Learn the screen you're on" },
  { id: "curriculum-live", label: "Levels 0-10" },
  { id: "curriculum-in-design", label: "Levels 11-15" },
];

export default function LearnPage() {
  const { isAdmin } = useAuth();
  const [progress, setProgress] = useState<ProgressState>({ kind: "unwired" });

  useEffect(() => {
    const loader = resolveLearnProgressLoader();
    if (!loader) {
      // No endpoint exists yet. Deliberately NOT an error state: nothing is
      // broken, there is simply nothing to show, so the strip renders nothing.
      setProgress({ kind: "unwired" });
      return undefined;
    }

    let cancelled = false;
    setProgress({ kind: "loading" });
    loader()
      .then((snapshot) => {
        if (cancelled) return;
        setProgress(snapshot ? { kind: "ready", snapshot } : { kind: "noLearner" });
      })
      .catch(() => {
        // The curriculum map below does not depend on this call, so a failure
        // costs the visitor one strip of numbers and nothing else.
        if (!cancelled) setProgress({ kind: "error" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // adminOnly rows drop for non-admins; on a personal install the screens that
  // install never runs (payments, branding, MCP, sharing, ...) drop too - a
  // lesson card for a hidden surface would be a link to nowhere useful.
  const mappedScreens = CONSOLE_SCREEN_LESSONS.filter(
    (screen) => (isAdmin || !screen.adminOnly) && !hiddenOnPersonalInstall(screen.route),
  );
  const snapshot = progress.kind === "ready" ? progress.snapshot : null;

  return (
    <AppShell eyebrow="Plans & Help">
      <PageHeader
        title="Learn"
        subtitle="The VegaDūta Academy, indexed inside the console. Find the lesson for the screen you are looking at, or browse the whole curriculum — every level, and what it makes you able to do."
        actions={
          hasAcademy() ? (
          <Button asChild>
            {/* The Academy is a separate deployable on its own origin, so these
                are real external anchors, never next/link transitions. */}
            <a href={academyJourneyUrl("/learn")} target="_blank" rel="noopener noreferrer">
              <Compass aria-hidden="true" />
              Open the Academy
              <ArrowUpRight aria-hidden="true" />
            </a>
          </Button>
          ) : undefined
        }
      />

      {/* Jump pills — same affordance as /help, kept above the fold because
          this page is long. */}
      <nav aria-label="On this page" className="mb-8 flex flex-wrap gap-2">
        {JUMP_SECTIONS.map((section) => (
          <Badge key={section.id} asChild variant="outline">
            <a href={`#${section.id}`}>{section.label}</a>
          </Badge>
        ))}
      </nav>

      <TutorEntryCard />

      <ProgressStrip state={progress} />

      {/* Shape-of-the-course figures, straight off the curriculum constant so
          they cannot drift from the cards below. */}
      <div className="mb-10 grid gap-4 sm:grid-cols-3">
        <StatCard
          label="Levels"
          value={CURRICULUM.length}
          sub={`${CURRICULUM.filter((l) => l.status === "live").length} teachable today, ${
            CURRICULUM.filter((l) => l.status === "in-design").length
          } in design`}
          icon={GraduationCap}
        />
        <StatCard
          label="Lessons in the Academy"
          value={LIVE_LESSON_COUNT}
          sub={`+${IN_DESIGN_LESSON_COUNT} specified for the advanced track`}
          icon={BookOpenCheck}
        />
        <StatCard
          label="Console screens with a lesson"
          value={mappedScreens.length}
          sub={
            snapshot
              ? `${snapshot.completedLessonIds.length} lessons completed on this account`
              : "Every one links straight to the lesson that teaches it"
          }
          icon={MonitorSmartphone}
        />
      </div>

      {/* -----------------------------------------------------------------
          Contextual entry points — the reason this route exists.
          ----------------------------------------------------------------- */}
      <section id="screens" className="mb-12 scroll-mt-6">
        <Section
          title="Learn the screen you're on"
          description="Each console screen below has at least one Academy lesson that teaches it. Lessons open in a new tab so you keep your place here."
        >
          {mappedScreens.length === 0 ? (
            <EmptyState
              icon={Lightbulb}
              title="No screen mappings yet"
              description="Nothing is mapped to an Academy lesson in this build. Add an entry to CONSOLE_SCREEN_LESSONS in app/learn/consoleLessonMap.ts to make one appear."
            />
          ) : (
            <div className="grid gap-4 md:grid-cols-2">
              {mappedScreens.map((screen) => (
                <Card key={screen.route} className="gap-0 p-5">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <h3 className="text-sm font-semibold text-foreground">{screen.label}</h3>
                      <code className="mt-0.5 block truncate font-mono text-xs text-muted-foreground">
                        {screen.route}
                      </code>
                    </div>
                    {screen.adminOnly && (
                      <Badge variant="outline" className="shrink-0">
                        <ShieldCheck aria-hidden="true" />
                        Admin
                      </Badge>
                    )}
                  </div>

                  <div className="mt-4 border-t border-border pt-4">
                    <ul className="list-none space-y-2 p-0">
                      {screen.lessons.map((lesson) => (
                        <li key={lesson.lessonId}>
                          <LearnLessonLink
                            level={lesson.level}
                            title={lesson.title}
                            lessonId={lesson.lessonId}
                            fromPath={screen.route}
                          />
                        </li>
                      ))}
                    </ul>
                  </div>

                  <div className="mt-4">
                    <Button asChild variant="ghost" size="sm" className="px-0 hover:bg-transparent">
                      <Link href={screen.route}>
                        Go to the screen
                        <ArrowUpRight aria-hidden="true" />
                      </Link>
                    </Button>
                  </div>
                </Card>
              ))}
            </div>
          )}
        </Section>

        <p className="mt-4 max-w-[68ch] text-xs leading-relaxed text-muted-foreground">
          Lessons live in the Academy, which keeps its own sign-in. If you have not signed in there yet, the link takes
          you to the Academy and you will land on its home page after signing in rather than directly on the lesson.
        </p>
      </section>

      {/* -----------------------------------------------------------------
          The curriculum map.
          ----------------------------------------------------------------- */}
      <div className="mb-12">
        <div className="mb-6 space-y-1">
          <h2 className="text-xl font-semibold tracking-tight text-foreground">The curriculum</h2>
          <p className="max-w-[68ch] leading-relaxed text-muted-foreground">
            Sixteen levels, from &ldquo;what is an agent&rdquo; to running an agent platform as a business. Levels 0-10
            are teachable in the Academy today; levels 11-15 are specified and not yet published.
          </p>
        </div>
        <CurriculumMap progress={snapshot} />
      </div>

      {/* Closing signposts — the two other Academy surfaces worth knowing
          about, plus where the product tour lives (it is on /help, not here:
          one home for "show me again", per the onboarding design). */}
      <Section title="Other ways in">
        <div className="grid gap-4 sm:grid-cols-3">
          <SignpostCard
            icon={Compass}
            title="Journey Map"
            body="The Academy's guided path: every level in order, which lessons are unlocked for you, and the XP each one is worth."
            href={academyJourneyUrl("/learn")}
            external
            cta="Open the Journey Map"
          />
          <SignpostCard
            icon={Lightbulb}
            title="Concept Library"
            body="Every concept across every level, browsable regardless of how far you have got — a reference rather than a path."
            href={academyConceptsUrl("/learn")}
            external
            cta="Browse concepts"
          />
          <SignpostCard
            icon={GraduationCap}
            title="Help & the product tour"
            body="Plain-language explanations of this product, the full docs reference, and the guided walkthrough you can replay."
            href="/help"
            external={false}
            cta="Open Help"
          />
        </div>
      </Section>
    </AppShell>
  );
}

/**
 * The learner's own numbers, when they can be known.
 *
 * Renders nothing at all in the `unwired` state — there is no endpoint for
 * Academy progress yet (learnProgress.ts documents the exact one to add), and a
 * strip of zeroes would be a lie about this account. The loading / error /
 * no-learner branches exist because they are the states the strip will be in
 * the moment that endpoint lands.
 */
function ProgressStrip({ state }: { state: ProgressState }) {
  if (state.kind === "unwired") return null;

  if (state.kind === "loading") {
    return (
      <div className="mb-10 grid gap-4 sm:grid-cols-3" aria-busy="true">
        <Skeleton className="h-[104px] rounded-xl" />
        <Skeleton className="h-[104px] rounded-xl" />
        <Skeleton className="h-[104px] rounded-xl" />
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <Card className="mb-10 gap-0 p-5">
        <h3 className="text-sm font-semibold text-foreground">Your Academy progress is unavailable right now</h3>
        <p className="mt-1 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
          The curriculum map below is unaffected — it does not need the network. Reload the page to try the progress
          lookup again.
        </p>
      </Card>
    );
  }

  if (state.kind === "noLearner") {
    return (
      <Card className="mb-10 flex-row items-center justify-between gap-4 p-5">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-foreground">You have not started the Academy yet</h3>
          <p className="mt-1 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
            It is free, and level 0 assumes nothing. Your progress will show up here once you finish your first lesson.
          </p>
        </div>
        <Button asChild className="shrink-0">
          <a href={academyJourneyUrl("/learn")} target="_blank" rel="noopener noreferrer">
            Start level 0
            <ArrowUpRight aria-hidden="true" />
          </a>
        </Button>
      </Card>
    );
  }

  const { snapshot } = state;
  // Which level the learner is furthest into — the highest level with at least
  // one completed lesson. Derived here rather than trusted from the payload so
  // the strip stays correct if the API only ever returns completed ids.
  const furthestLevel = CURRICULUM.filter(
    (level) => level.status === "live" && completedInLevel(snapshot, level.level) > 0
  ).at(-1);

  return (
    <div className="mb-10 grid gap-4 sm:grid-cols-3">
      <StatCard label="XP" value={snapshot.xpTotal} icon={GraduationCap} accent />
      <StatCard
        label="Streak"
        value={snapshot.streakDays}
        sub={snapshot.streakDays === 1 ? "day in a row" : "days in a row"}
        icon={BookOpenCheck}
      />
      <StatCard
        label="Lessons completed"
        value={snapshot.completedLessonIds.length}
        sub={
          furthestLevel
            ? `Furthest: L${furthestLevel.level} — ${furthestLevel.title}`
            : `of ${LIVE_LESSON_COUNT} in the Academy`
        }
        icon={Compass}
      />
    </div>
  );
}

/** One of the three closing signposts. */
function SignpostCard({
  icon: Icon,
  title,
  body,
  href,
  external,
  cta,
}: {
  icon: typeof Compass;
  title: string;
  body: string;
  href: string;
  external: boolean;
  cta: string;
}) {
  return (
    <Card className="h-full gap-0 p-5">
      <span className="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
        <Icon className="size-5" aria-hidden="true" />
      </span>
      <h3 className="mt-3 text-sm font-semibold text-foreground">{title}</h3>
      <p className="mt-1.5 flex-1 text-sm leading-relaxed text-muted-foreground">{body}</p>
      <div className="mt-4">
        <Button asChild variant="outline" size="sm">
          {external ? (
            <a href={href} target="_blank" rel="noopener noreferrer">
              {cta}
              <ArrowUpRight aria-hidden="true" />
            </a>
          ) : (
            <Link href={href}>
              {cta}
              <ArrowUpRight aria-hidden="true" />
            </Link>
          )}
        </Button>
      </div>
    </Card>
  );
}
