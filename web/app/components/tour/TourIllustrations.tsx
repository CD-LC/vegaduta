"use client";

import type { ReactElement } from "react";
import type { TourIllustrationName } from "./illustrations/shared";
import { AgentsIllustration, WorkflowsIllustration, KnowledgeIllustration } from "./illustrations/build";
import { ChannelsIllustration, ToolsIllustration, PlaygroundIllustration } from "./illustrations/connect";
import { MonitorIllustration, HelpIllustration, EdgeIllustration } from "./illustrations/ops";
import { SharedWithMeIllustration, PlatformIllustration } from "./illustrations/audience";
import { RecipeIllustration, WelcomeIllustration, CompleteIllustration } from "./illustrations/onboarding";
import type { IllustrationProps } from "./illustrations/shared";

export type { TourIllustrationName };

/**
 * The product tour's illustration set - fourteen hand-drawn inline SVGs (the
 * prose below still says thirteen in places; `edge` was added 2026-09-19): one per
 * tour step (`recipe` for the opening step, then the seven nav destinations and
 * `help`), plus `welcome` for the pre-tour hero and `complete` for the end card.
 *
 * TWO OF THE THIRTEEN ARE AUDIENCE-CONDITIONAL. The tour is no longer one fixed
 * walk: TourProvider picks steps by audience (platform-admin / tenant-admin /
 * member), entitlement and reachability, so `shared-with-me` is drawn only for
 * members - who receive agents rather than author them - and `platform` only for
 * a platform-admin, who operates the platform and is not a new tenant. Neither
 * is reachable from the tenant-admin walk, which is why nine of these can be the
 * whole tour for one user and a different subset the whole tour for another.
 *
 * WHY INLINE SVG AND NOT IMAGE FILES. Every colour in here has to flip with
 * the console's theme, and the console has no `.dark` wrapper: it swaps the
 * whole `--surface-*` / `--text-*` / `--accent-*` ladder on
 * `:root[data-mode="dark"]` (globals.css). A PNG or a static .svg asset cannot
 * see those variables, so it would either be a light-mode-only graphic or need
 * two files and a mode-aware <picture>. Inline SVG inherits the cascade, so one
 * drawing is correct in both modes - and correct again when a tenant repaints
 * `--accent-500` through the theme picker, which is exactly why nothing below
 * is a hex literal.
 *
 * WHY THESE DRAWINGS ARE MECHANISMS, NOT SHAPES. Each step's card carries two
 * bullet points; the picture's job is to show the *shape of the thing* before
 * the reader has any vocabulary for it - a persona card with a model chip
 * attached, documents feeding an index an agent reads from, one agent fanning
 * out to three channels. A generic gradient blob would have been cheaper and
 * would have taught nothing.
 *
 * THE FOUR-BEAT STRIP IS THE SPINE. `recipe` (step 0) and `complete` (the end
 * card) draw the same four beats in the same order - agent, knowledge, sandbox,
 * channel - because the tour *walks the nav rail* (agents, workflows,
 * knowledge, channels, tools, playground, monitor) while what a first-time
 * reader actually needs is the *build order*, which is a different sequence.
 * The completion copy states that order in one sentence and nothing else in the
 * tour does, so the picture states it twice: once before the walk, as a plan,
 * and once after it, as a finished loop. The repetition is the point. Every
 * beat is drawn in the same vocabulary the matching step later uses at full
 * size, so those parts are recognised rather than met.
 *
 * DELIBERATE NON-FEATURES.
 *  - No <linearGradient>, <filter> or <clipPath>. Gradient/filter ids are
 *    document-global, and B3 cross-fades steps inside <AnimatePresence>, so two
 *    illustrations are mounted at once during every transition; duplicate or
 *    even distinct ids in that window are a class of bug (the outgoing SVG
 *    recolouring mid-fade) that is simply avoided by not having any. Flat fills
 *    and `color-mix()` reach the same look. It also keeps this off the
 *    app-wide "no blur() filters" performance line the ambient layer holds.
 *  - No text inside any SVG. Text here would bypass the 30-locale i18n
 *    pipeline and ship English into every other locale. Where a drawing needs
 *    to suggest words it uses bars, the way a wireframe does.
 *  - No trademarked logos. The `channels` illustration draws a generic speech
 *    bubble, a generic web widget and a generic envelope. Using the real
 *    WhatsApp/Slack/etc. marks (which we do own path data for, in
 *    BrandIcons.tsx) would be wrong here on two counts: those marks belong to
 *    the Channels screen where a tile means that specific vendor, and a tour
 *    slide is marketing surface where third-party marks carry trademark
 *    obligations we have no reason to take on.
 *
 * B3 renders these at card width; see TourIllustration's docblock for the
 * sizing contract.
 */

/* ------------------------------------------------------------------ *
 * Colour vocabulary
 *
 * All thirteen drawings share these nine values, and every one of them resolves
 * through a custom property that is redefined under `:root[data-mode="dark"]`,
 * so a shape drawn once is legible on both a white `--surface-card` and a
 * near-black one. Two rules make that work without a second dark-mode pass:
 *
 *  - Tints are `color-mix(… , transparent)` over whatever the card is, never a
 *    baked light grey. A 12% mix of `--muted-foreground` is a faint darker
 *    wash on white and a faint lighter wash on the dark card - the same
 *    *relationship* in both modes, which is what "reads correctly" means here.
 *  - `--card` is used ONLY as a knockout (punching a hole through a line, or
 *    lifting a chip off the panel behind it), and only through `KNOCKOUT`,
 *    never at full opacity. It is never used as a "light fill" for a shape that
 *    needs to be seen, because on a dark card it is dark and the shape would
 *    vanish.
 *
 * WHY KNOCKOUT IS 86% AND NOT OPAQUE - AND WHY THE GROUND IS PLAIN `--card`.
 * These drawings sit on plain `--card` today. TourOverlay's illustration band
 * paints no background of its own; it shows the tour card shell's own `bg-card`
 * through (see IllustrationBand's docblock in TourOverlay.tsx), and KNOCKOUT
 * composited over that ground is exactly `--card` - card over card is card at
 * any alpha.
 *
 * It was not always so, and the history is the reason not to undo it. The band
 * used to paint a `linear-gradient(160deg, --primary 16% ...)` behind the whole
 * 240x120 box while this file filled its knockouts with a flat `var(--card)`,
 * so every knockout punched a hard, untinted patch straight through that tint -
 * worst in the upper left, where the 160deg ramp was strongest. Round 2 deleted
 * the gradient rather than the knockouts, because the knockouts are the
 * load-bearing half: they are what makes the document stack read as depth
 * instead of three stacked outlines, and what lifts the model chip off the
 * instruction bars behind it. A tint is decoration; those are the drawing.
 *
 * 86% survives as the mix anyway, because it costs nothing on the flat ground
 * and means this file needs no agreement with the caller about what is behind
 * it: composited over a tint it keeps 14% of that tint, so the shape would read
 * as lifted rather than as a hole cut in the background, and 14% is little
 * enough that what it covers still stays covered. That is a cushion, NOT a
 * licence to paint a tint back in - at 86% the patches would be softer but
 * still there. Anyone who wants a tinted ground again has to change the
 * knockouts in the same edit, and the place for that tint is INSIDE the
 * illustration, behind the knockouts, mixed `in oklab` like everything else
 * here.
 *
 * WHAT LEFT THE PALETTE. `--destructive` (as ALERT/ALERT_HALO) used to be here
 * for `monitor`'s flagged run. `monitor` no longer draws a threshold or an
 * alert - the step it illustrates is about the Playground, produced files and
 * the audit log, and a red dot promised alerting that step never mentions - so
 * the token went with the drawing. Nothing else in the set has any business
 * being red.
 *
 * `--primary` is the tenant-customisable accent (ThemePicker swaps it to
 * emerald/rose/amber/sky/slate). That is intended: the tour should wear the
 * tenant's accent. `--brand-violet` - the fixed landing-page violet - is
 * deliberately NOT used, because nothing in these drawings is the brand mark.
 * The one true brand mark, in `welcome`, is the real /logo-mark.png artwork.
 * ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ *
 * Registry
 * ------------------------------------------------------------------ */

const ILLUSTRATIONS: Record<TourIllustrationName, (props: IllustrationProps) => ReactElement> = {
  recipe: RecipeIllustration,
  welcome: WelcomeIllustration,
  agents: AgentsIllustration,
  workflows: WorkflowsIllustration,
  knowledge: KnowledgeIllustration,
  channels: ChannelsIllustration,
  tools: ToolsIllustration,
  playground: PlaygroundIllustration,
  monitor: MonitorIllustration,
  "shared-with-me": SharedWithMeIllustration,
  help: HelpIllustration,
  edge: EdgeIllustration,
  platform: PlatformIllustration,
  complete: CompleteIllustration,
};

/**
 * Renders one tour illustration by name.
 *
 * SIZING CONTRACT FOR THE CALLER. Every illustration is a 240x120 viewBox (2:1)
 * that fills its container's width and takes its height from the aspect ratio -
 * the root <svg> carries `w-full h-auto block` and no intrinsic width/height
 * attributes. So the caller controls size with a wrapper, e.g.
 * `<div className="w-full"><TourIllustration name={step.illustration} /></div>`,
 * and gets a box exactly half as tall as it is wide. At the tour card's ~320px
 * that is 320x160. Anything much below ~200px wide starts to lose the smaller
 * details (socket holes, grid cells, `shared-with-me`'s row-scale agent eyes and
 * `monitor`'s file-type badges, which are the densest things in the set and set
 * the real floor); anything above ~420px is
 * fine but the strokes begin to look thin, so a `max-w-` cap is worth having.
 *
 * The band this is mounted in IS `--card`: TourOverlay's IllustrationBand gives
 * the box no background of its own, so the tour card's `bg-card` is the ground,
 * and `KNOCKOUT` (86% `--card`) composites over it to exactly `--card`. The
 * band used to carry a `--primary` tint; round 2 removed it because the opaque
 * knockouts here painted untinted patches over it. Knockouts stay a mix rather
 * than a flat `var(--card)` so a caller may still change the ground without
 * telling this file - but a caller that wants a tint back has to fix the
 * knockouts in the same change. See the Colour vocabulary block.
 *
 * The drawings are decorative (`aria-hidden`), so the caller does not need to
 * label them - and should not, or screen readers will hear the step twice.
 *
 * An unrecognised name falls back to `welcome` instead of throwing: a tour step
 * with a typo'd illustration should degrade to a wrong-but-present picture, not
 * take the whole overlay down with it via an error boundary.
 */
export function TourIllustration({
  name,
  className,
}: {
  name: TourIllustrationName;
  className?: string;
}): ReactElement {
  const Chosen = ILLUSTRATIONS[name] ?? ILLUSTRATIONS.welcome;
  return <Chosen className={className} />;
}
