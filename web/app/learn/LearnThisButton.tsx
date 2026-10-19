"use client";

import { GraduationCap, ArrowUpRight } from "lucide-react";

import { cn } from "../lib/utils";
import { Button } from "../components/ui/button";
import { academyLessonUrl } from "./curriculum";
import { hasAcademy } from "../lib/academyUrl";
import { CONSOLE_SCREEN_LESSONS, primaryLessonForConsolePath } from "./consoleLessonMap";

/**
 * The contextual "Learn this" affordance — the whole point of the Learn
 * surface: someone is looking at a screen they do not understand and can get
 * the lesson for *that screen* without leaving the product.
 *
 * Drop it into any console screen's PageHeader:
 *
 *   <PageHeader title="Workflows" action={<LearnThisButton path="/workflows" />} />
 *
 * Renders NOTHING when the screen has no mapped lesson (see
 * consoleLessonMap.ts — unmapped is the honest default, and a disabled or
 * dead-ended button would be worse than no button). So it is safe to add
 * unconditionally to a screen before its lesson exists.
 *
 * `path` is required rather than read from usePathname() on purpose: the
 * mapping keys are canonical route shapes, and several console screens are
 * dynamic (/workflows/[id]/runs/[runId]) or redirect shims (/devices). Passing
 * the literal route keeps each call site explicit about which mapping entry it
 * expects, and makes this component trivially testable. A screen that wants
 * live matching can pass usePathname() itself — the prefix matcher in
 * lessonsForConsolePath handles dynamic segments.
 */
export default function LearnThisButton({
  path,
  variant = "outline",
  size = "sm",
  className,
  label = "Learn this",
}: {
  path: string;
  variant?: "outline" | "ghost" | "secondary";
  size?: "sm" | "default";
  className?: string;
  /** Override for screens where "Learn this" reads oddly next to the title. */
  label?: string;
}) {
  const primary = primaryLessonForConsolePath(path);
  if (!primary || !hasAcademy()) return null;

  return (
    <Button asChild variant={variant} size={size} className={className}>
      {/* External anchor, not next/link: the Academy is its own deployable on
          its own origin, so a client-side transition would 404 inside the
          console. target=_blank keeps whatever the learner had open here (a
          half-built canvas, an unsent prompt) exactly where it was. */}
      <a
        href={primary.href}
        target="_blank"
        rel="noopener noreferrer"
        title={`Opens the VegaDūta Academy lesson "${primary.link.title}" in a new tab`}
      >
        <GraduationCap aria-hidden="true" />
        {label}
        <ArrowUpRight aria-hidden="true" />
      </a>
    </Button>
  );
}

/** One lesson link — level chip, title, external-link affordance. */
export function LearnLessonLink({
  level,
  title,
  lessonId,
  fromPath,
}: {
  level: number;
  title: string;
  lessonId: string;
  /** Console route this link is being offered from — becomes `console_from`. */
  fromPath: string;
}) {
  if (!hasAcademy()) return null;
  return (
    <a
      href={academyLessonUrl(lessonId, fromPath)}
      target="_blank"
      rel="noopener noreferrer"
      className="group inline-flex items-start gap-2 text-sm leading-relaxed text-muted-foreground transition-colors hover:text-foreground"
    >
      <span className="mt-px shrink-0 rounded-full border border-border px-1.5 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
        L{level}
      </span>
      <span className="underline-offset-4 group-hover:underline">{title}</span>
      <ArrowUpRight
        className="mt-0.5 size-3.5 shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
        aria-hidden="true"
      />
    </a>
  );
}

/**
 * Every lesson mapped to a screen, as a stack of links — what the /learn index
 * renders per row, and what a screen can use inline when one button is not
 * enough. Lives here rather than in the page module so any console screen can
 * reuse it without importing a route's page.
 */
export function LearnThisLessonLinks({ path }: { path: string }) {
  const screen = CONSOLE_SCREEN_LESSONS.find((s) => s.route === path);
  if (!screen) return null;

  return (
    <ul className="list-none space-y-1.5 p-0">
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
  );
}

/**
 * The quiet sibling of LearnThisButton: a bare link for places a button would
 * be too loud — a Section's actions row, a dialog header, a line of helper
 * text. Takes an explicit `lessonId` rather than a path because the caller is
 * a sub-section of a screen (a lab tab, a quality section, an envoy rail)
 * that knows its own lesson better than the route-level map does.
 *
 * Same rules as the button: external anchor to the Academy's own origin,
 * target=_blank so nothing in the console is lost, console_from attribution
 * stamped by academyLessonUrl. The lesson id must be one that exists in the
 * Academy's seed — keep it listed in a CONSOLE_SCREEN_LESSONS entry so the
 * seed check in pageGuides.test.ts covers it (a dead link is worse than none).
 */
export function LearnLink({
  lessonId,
  fromPath,
  label = "Learn",
  className,
}: {
  /** Academy content-unit id, e.g. "l22-4". */
  lessonId: string;
  /** Console route this link is being offered from — becomes `console_from`. */
  fromPath?: string;
  label?: string;
  className?: string;
}) {
  return (
    <a
      href={academyLessonUrl(lessonId, fromPath)}
      target="_blank"
      rel="noopener noreferrer"
      title={`Opens the VegaDūta Academy lesson that teaches this section in a new tab`}
      className={cn(
        "inline-flex shrink-0 items-center gap-1 text-xs font-semibold text-primary hover:underline",
        className
      )}
    >
      <GraduationCap className="size-3.5" aria-hidden="true" />
      {label}
      <ArrowUpRight className="size-3" aria-hidden="true" />
    </a>
  );
}
