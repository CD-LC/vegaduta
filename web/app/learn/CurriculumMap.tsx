"use client";

import { ArrowUpRight, CheckCircle2, Compass, DraftingCompass } from "lucide-react";

import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../components/ui/accordion";
import { CURRICULUM, academyJourneyUrl, type CurriculumLevel } from "./curriculum";
import { CONSOLE_SCREEN_LESSONS } from "./consoleLessonMap";
import { completedInLevel, type LearnProgressSnapshot } from "./learnProgress";

/**
 * The browsable map of the curriculum: one card per Academy level, what that
 * level makes you able to do, and — for the levels that are still on the
 * agenda rather than in the Academy's seed — the lesson list, collapsed.
 *
 * Live levels deliberately do NOT list their lessons here: those 135 titles are
 * authored in and owned by vegaduta-webapp/src/seed.ts, and a second copy in
 * this repo would drift the first time anyone renames one. A live level links
 * into the Academy's Journey Map instead, which is the real, progress-aware
 * list. See curriculum.ts's provenance header.
 *
 * `progress` is optional and usually absent — see learnProgress.ts for why
 * there is no endpoint yet. When it is absent nothing about this component
 * degrades: the cards simply carry no completion counts.
 */
export default function CurriculumMap({ progress }: { progress?: LearnProgressSnapshot | null }) {
  const live = CURRICULUM.filter((l) => l.status === "live");
  const inDesign = CURRICULUM.filter((l) => l.status === "in-design");

  return (
    <div className="space-y-10">
      <section id="curriculum-live" className="scroll-mt-6 space-y-4">
        <div className="space-y-1">
          <h2 className="text-base font-semibold tracking-tight text-foreground">
            Teachable today — levels 0 to 10
          </h2>
          <p className="max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
            Eleven levels, free, in order. Each one ends where the next begins: concepts, then this console, then the
            integrations, then operating an agent people depend on, then the engine underneath it.
          </p>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          {live.map((level) => (
            <LevelCard key={level.level} level={level} progress={progress} />
          ))}
        </div>
      </section>

      <section id="curriculum-in-design" className="scroll-mt-6 space-y-4">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <DraftingCompass className="size-4 text-primary" aria-hidden="true" />
            <h2 className="text-base font-semibold tracking-tight text-foreground">
              In design — levels 11 to 15
            </h2>
          </div>
          <p className="max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
            A five-part advanced track, specified but not yet published in the Academy. These lessons have no pages to
            open yet, so the titles below are the whole of what exists — listed so you can see what is coming and say
            what you want first.
          </p>
        </div>
        <div className="space-y-4">
          {inDesign.map((level) => (
            <InDesignLevelCard key={level.level} level={level} />
          ))}
        </div>
      </section>
    </div>
  );
}

/** Console screens taught by a given level, derived from the one mapping
 * constant so this can never disagree with the "Learn this" buttons. */
function screensForLevel(level: number): string[] {
  const labels = new Set<string>();
  for (const screen of CONSOLE_SCREEN_LESSONS) {
    if (screen.lessons.some((lesson) => lesson.level === level)) labels.add(screen.label);
  }
  return Array.from(labels);
}

function LevelCard({
  level,
  progress,
}: {
  level: CurriculumLevel;
  progress?: LearnProgressSnapshot | null;
}) {
  const screens = screensForLevel(level.level);
  const done = progress ? completedInLevel(progress, level.level) : null;

  return (
    <Card className="card-interactive h-full gap-0 p-5">
      <div className="flex items-start justify-between gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 font-mono text-xs font-semibold text-primary">
          L{level.level}
        </span>
        <div className="flex shrink-0 items-center gap-2">
          {done !== null && done > 0 && (
            <span className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground">
              <CheckCircle2 className="size-3.5 text-primary" aria-hidden="true" />
              {done}/{level.lessonCount}
            </span>
          )}
          <Badge variant="secondary">{level.lessonCount} lessons</Badge>
        </div>
      </div>

      <h3 className="mt-3 text-base font-semibold tracking-tight text-foreground">{level.title}</h3>
      <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{level.outcome}</p>

      {screens.length > 0 && (
        <p className="mt-3 text-xs leading-relaxed text-muted-foreground">
          <span className="font-medium text-foreground">Screens it covers: </span>
          {screens.join(" · ")}
        </p>
      )}

      <div className="mt-4">
        <Button asChild variant="ghost" size="sm" className="px-0 hover:bg-transparent">
          {/* The Journey Map is the Academy's real, progress-aware lesson list
              for every level — the honest destination for "show me the
              lessons", since this console does not hold their titles. */}
          <a href={academyJourneyUrl("/learn")} target="_blank" rel="noopener noreferrer">
            <Compass aria-hidden="true" />
            Open in the Academy
            <ArrowUpRight aria-hidden="true" />
          </a>
        </Button>
      </div>
    </Card>
  );
}

function InDesignLevelCard({ level }: { level: CurriculumLevel }) {
  const lessons = level.lessons ?? [];

  return (
    <Card className="gap-0 p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted font-mono text-xs font-semibold text-muted-foreground">
            L{level.level}
          </span>
          <div className="min-w-0">
            <h3 className="text-base font-semibold tracking-tight text-foreground">{level.title}</h3>
            <p className="mt-1.5 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">{level.outcome}</p>
          </div>
        </div>
        <Badge variant="outline" className="shrink-0">
          {level.lessonCount} lessons planned
        </Badge>
      </div>

      {lessons.length > 0 && (
        <Accordion type="single" collapsible className="mt-3">
          <AccordionItem value={`level-${level.level}`} className="border-b-0">
            <AccordionTrigger className="text-sm">See the lesson list</AccordionTrigger>
            <AccordionContent>
              {/* Plain ordered list, no links: none of these has a content unit
                  in the Academy, so every href would be a 404. */}
              <ol className="list-none space-y-2 p-0">
                {lessons.map((title, i) => (
                  <li key={title} className="flex items-start gap-3 text-sm leading-relaxed text-muted-foreground">
                    <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-muted font-mono text-xs font-semibold text-muted-foreground">
                      {i + 1}
                    </span>
                    <span>{title}</span>
                  </li>
                ))}
              </ol>
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      )}
    </Card>
  );
}
