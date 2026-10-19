// One place for the studio's colour language, so every selection, stage and
// call-to-action means the same thing everywhere. Tailwind palette utilities
// (with dark variants) rather than new tokens: the studio is a page-level
// surface and these read correctly on both themes without touching the global
// design system.
//
//   violet  - identity and primary actions (the brand colour)
//   emerald - things that DO (hands, running, go)
//   sky     - things it KNOWS (documents, reading)
//   amber   - how carefully it thinks (thinking, autonomy, approvals)
//   rose    - only ever destructive
//
// A selected option is unmistakable on purpose: tinted fill, a coloured ring,
// and (in components) a check - not the faint outline the default kit gives.

export type Tone = "violet" | "emerald" | "sky" | "amber" | "rose";

type ToneClasses = {
  /** A selected card / option. */
  selected: string;
  /** An unselected but interactive card. */
  idle: string;
  /** Solid dot / number badge. */
  solid: string;
  /** Soft icon chip. */
  soft: string;
  /** Text colour for emphasis. */
  text: string;
  /** A call-to-action button in this tone. */
  cta: string;
};

export const TONES: Record<Tone, ToneClasses> = {
  violet: {
    selected: "border-violet-500 bg-violet-500/10 ring-1 ring-violet-500/30 shadow-sm shadow-violet-500/10",
    idle: "border-border/60 hover:border-violet-400/60 hover:bg-violet-500/5",
    solid: "bg-violet-600 text-white",
    soft: "bg-violet-500/15 text-violet-700 dark:text-violet-300",
    text: "text-violet-700 dark:text-violet-300",
    cta: "bg-gradient-to-r from-violet-600 to-indigo-600 text-white shadow-md shadow-violet-500/25 hover:from-violet-500 hover:to-indigo-500 hover:shadow-lg hover:shadow-violet-500/30",
  },
  emerald: {
    selected: "border-emerald-500 bg-emerald-500/10 ring-1 ring-emerald-500/30 shadow-sm shadow-emerald-500/10",
    idle: "border-border/60 hover:border-emerald-400/60 hover:bg-emerald-500/5",
    solid: "bg-emerald-600 text-white",
    soft: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300",
    text: "text-emerald-700 dark:text-emerald-300",
    cta: "bg-gradient-to-r from-emerald-600 to-teal-600 text-white shadow-md shadow-emerald-500/25 hover:from-emerald-500 hover:to-teal-500 hover:shadow-lg hover:shadow-emerald-500/30",
  },
  sky: {
    selected: "border-sky-500 bg-sky-500/10 ring-1 ring-sky-500/30 shadow-sm shadow-sky-500/10",
    idle: "border-border/60 hover:border-sky-400/60 hover:bg-sky-500/5",
    solid: "bg-sky-600 text-white",
    soft: "bg-sky-500/15 text-sky-700 dark:text-sky-300",
    text: "text-sky-700 dark:text-sky-300",
    cta: "bg-gradient-to-r from-sky-600 to-cyan-600 text-white shadow-md shadow-sky-500/25 hover:from-sky-500 hover:to-cyan-500 hover:shadow-lg hover:shadow-sky-500/30",
  },
  amber: {
    selected: "border-amber-500 bg-amber-500/10 ring-1 ring-amber-500/30 shadow-sm shadow-amber-500/10",
    idle: "border-border/60 hover:border-amber-400/60 hover:bg-amber-500/5",
    solid: "bg-amber-600 text-white",
    soft: "bg-amber-500/15 text-amber-700 dark:text-amber-300",
    text: "text-amber-700 dark:text-amber-300",
    cta: "bg-gradient-to-r from-amber-500 to-orange-500 text-white shadow-md shadow-amber-500/25 hover:from-amber-400 hover:to-orange-400 hover:shadow-lg hover:shadow-amber-500/30",
  },
  rose: {
    selected: "border-rose-500 bg-rose-500/10 ring-1 ring-rose-500/30",
    idle: "border-border/60 hover:border-rose-400/60",
    solid: "bg-rose-600 text-white",
    soft: "bg-rose-500/15 text-rose-700 dark:text-rose-300",
    text: "text-rose-700 dark:text-rose-300",
    cta: "bg-gradient-to-r from-rose-600 to-red-600 text-white shadow-md shadow-rose-500/25",
  },
};

/** The four stages, each with its own colour so where you are is obvious at a glance. */
export const STAGE_TONE: Record<"design" | "run" | "chain" | "deploy", Tone> = {
  design: "violet",
  run: "emerald",
  chain: "amber",
  deploy: "sky",
};

/** Selected-or-idle class for a card-like option. */
export function pick(tone: Tone, selected: boolean): string {
  return selected ? TONES[tone].selected : TONES[tone].idle;
}

/** A pulsing ring for the control the tour is asking the visitor to use. */
export const TOUR_PULSE = "studio-tour-pulse";
