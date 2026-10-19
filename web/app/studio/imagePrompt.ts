// Pure helpers for the Studio quick-create card: no React, no i18n.

export const STYLE_IDS = ["photo", "illustration", "render3d", "watercolor", "logo", "cinematic"] as const;
export type StyleId = (typeof STYLE_IDS)[number];

export const SHAPE_IDS = ["square", "wide", "tall"] as const;
export type ShapeId = (typeof SHAPE_IDS)[number];

/** Descriptive phrase the agent receives for each choice (model-facing, so English). */
export const STYLE_PHRASE: Record<StyleId, string> = {
  photo: "realistic photograph",
  illustration: "flat vector illustration",
  render3d: "polished 3D render",
  watercolor: "soft watercolor painting",
  logo: "minimal logo mark on a plain background",
  cinematic: "cinematic still with dramatic lighting",
};

export const SHAPE_PHRASE: Record<ShapeId, string> = {
  square: "square 1:1 framing",
  wide: "wide 16:9 landscape framing",
  tall: "tall 9:16 portrait framing",
};

/** The playground reads at most 600 chars of ?prompt=, so stay under it. */
export const MAX_PROMPT = 600;
export const MAX_IDEA = 400;

export function buildImagePrompt(idea: string, style: StyleId | null, shape: ShapeId | null): string {
  const base = idea.trim().replace(/\s+/g, " ").slice(0, MAX_IDEA);
  if (!base) return "";
  const parts = [style && STYLE_PHRASE[style], shape && SHAPE_PHRASE[shape]].filter(Boolean);
  const tail = parts.length ? `, ${parts.join(", ")}` : "";
  return `Generate an image of ${base}${tail}.`.slice(0, MAX_PROMPT);
}

export function playgroundHref(prompt: string): string {
  return `/playground?prompt=${encodeURIComponent(prompt.slice(0, MAX_PROMPT))}`;
}

/** A reuse prompt from a gallery caption: keep it as the idea, drop any leading "Generate an image of". */
export function reusePrompt(caption: string): string {
  const idea = caption.trim().replace(/^generate (an )?image( of)?:?\s*/i, "");
  return buildImagePrompt(idea, null, null);
}

/** Deterministic pick so tests (and SSR) never depend on Math.random. */
export function pickIdea(ideas: readonly string[], seed: number): string {
  if (ideas.length === 0) return "";
  return ideas[Math.abs(Math.trunc(seed)) % ideas.length];
}
