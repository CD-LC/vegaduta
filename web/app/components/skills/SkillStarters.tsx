"use client";

// "Things to try": one-click starter prompts for the built-in skills. Each card
// opens the Playground with the prompt pre-filled (the composer is filled, not
// sent - the person reads it and presses Send). A card only shows when its skill
// is installed AND switched on in this workspace, so a starter never promises a
// skill the agent will not load. Copy is inline English on purpose: see the
// web-i18n rules on en.json fragility (same precedent as SkillRegistryBrowser).

import Link from "next/link";
import {
  ArrowRight,
  BookOpen,
  Compass,
  Lightbulb,
  Megaphone,
  Mic,
  PenLine,
  Rocket,
  Scale,
  type LucideIcon,
} from "lucide-react";
import type { SkillSummary } from "../../lib/api";
import { Card, CardContent } from "../ui/card";
import { useTranslations } from "../../lib/i18n";

type Starter = { skill: string; title: string; blurb: string; prompt: string; icon: LucideIcon };

export const SKILL_STARTERS: Starter[] = [
  {
    skill: "story-engine",
    title: "Play a story",
    blurb: "An interactive tale where your choices change the world.",
    prompt: "Let's play an interactive story. Start a short mystery set in a monsoon-soaked night market, and give me choices at the end of every scene.",
    icon: Compass,
  },
  {
    skill: "decision-lab",
    title: "Settle a tough choice",
    blurb: "Weighted criteria, a pre-mortem and a clear recommendation.",
    prompt: "Help me decide between two options. I'll tell you what they are; ask me what matters most and weigh them for me.",
    icon: Scale,
  },
  {
    skill: "idea-storm",
    title: "Brainstorm 20 ideas",
    blurb: "Wild-then-sharp ideation using proven creative lenses.",
    prompt: "Brainstorm 20 ideas for the name and tagline of a small business that I'll describe. Use a few different creative lenses, then shortlist five.",
    icon: Lightbulb,
  },
  {
    skill: "pitch-coach",
    title: "Sharpen a pitch",
    blurb: "A seven-beat story spine, then a mock Q&A with a sceptic.",
    prompt: "Coach me on a pitch. Ask me who the audience is and what I want from them, build the story spine, then grill me with the five hardest questions.",
    icon: Megaphone,
  },
  {
    skill: "rehearsal-room",
    title: "Rehearse a hard talk",
    blurb: "Practise a negotiation or feedback chat with a realistic counterpart.",
    prompt: "I want to rehearse a salary negotiation. Play my manager at a realistic difficulty, then give me a debrief with lines I could say instead.",
    icon: Mic,
  },
  {
    skill: "brand-voice",
    title: "Capture a writing voice",
    blurb: "Paste samples, get a voice guide, then write in that voice.",
    prompt: "I'll paste a few samples of my writing. Work out my voice, write a one-page style guide, then rewrite a paragraph I give you in that voice.",
    icon: PenLine,
  },
  {
    skill: "launch-planner",
    title: "Plan a launch",
    blurb: "A dated timeline with owners, risks and a launch-day checklist.",
    prompt: "Plan a launch for me. Ask for my goal and deadline, then build a week-by-week plan with owners, risks and a launch-day checklist.",
    icon: Rocket,
  },
  {
    skill: "quiz-maker",
    title: "Quiz me",
    blurb: "Revise any topic one question at a time, then get flashcards.",
    prompt: "Quiz me on a topic I choose, one question at a time, and finish with the two themes I should review and a flashcard set.",
    icon: BookOpen,
  },
];

export function startersFor(skills: SkillSummary[]): Starter[] {
  const on = new Set(skills.filter((s) => s.enabled !== false).map((s) => s.name));
  return SKILL_STARTERS.filter((s) => on.has(s.skill));
}

/** The text the Playground is opened with. It names the skill outright: left implicit, a
 * smaller model with a narrow persona ("You are the Comparison Agent...") answers from the
 * persona and never calls load_skill, even though the skill is offered on the turn. */
export function starterPrompt(starter: Pick<Starter, "skill" | "prompt">): string {
  return `${starter.prompt} Use the ${starter.skill} skill.`;
}

export function playgroundHref(prompt: string): string {
  return `/playground?prompt=${encodeURIComponent(prompt.slice(0, 600))}`;
}

export default function SkillStarters({ skills }: { skills: SkillSummary[] }) {
    const tHC = useTranslations("hcComponentsSkillsSkillStarters");
  const starters = startersFor(skills);
  if (starters.length === 0) return null;
  return (
    <section aria-labelledby="skill-starters-heading" className="mb-8 space-y-3">
      <div>
        <h2 id="skill-starters-heading" className="text-lg font-semibold text-foreground">
          {tHC("s2")}
        </h2>
        <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
          {tHC("s1")}
        </p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {starters.map(({ skill, title, blurb, prompt, icon: Icon }) => (
          <Link key={skill} href={playgroundHref(starterPrompt({ skill, prompt }))} className="group block h-full rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <Card className="h-full transition-shadow group-hover:shadow-md">
              <CardContent className="flex h-full flex-col gap-2 p-4">
                <span className="flex items-center gap-2 text-sm font-semibold text-foreground">
                  <Icon size={15} className="text-primary" aria-hidden="true" /> {title}
                </span>
                <span className="text-xs leading-relaxed text-muted-foreground">{blurb}</span>
                <span className="mt-auto inline-flex items-center gap-1 pt-1 font-mono text-[11px] text-muted-foreground">
                  {skill} <ArrowRight size={11} className="transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
                </span>
              </CardContent>
            </Card>
          </Link>
        ))}
      </div>
    </section>
  );
}
