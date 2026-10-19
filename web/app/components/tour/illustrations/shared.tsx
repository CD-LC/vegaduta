import type { ReactElement, ReactNode } from "react";

export const LINE = "var(--border)";
export const KNOCKOUT = "color-mix(in oklab, var(--card) 86%, transparent)";
export const ACCENT = "var(--primary)";
export const ACCENT_MID = "color-mix(in oklab, var(--primary) 55%, transparent)";
export const ACCENT_SOFT = "color-mix(in oklab, var(--primary) 32%, transparent)";
export const ACCENT_FAINT = "color-mix(in oklab, var(--primary) 12%, transparent)";
export const FILL = "color-mix(in oklab, var(--muted-foreground) 12%, transparent)";
export const FILL_STRONG = "color-mix(in oklab, var(--muted-foreground) 30%, transparent)";
export const QUIET = "color-mix(in oklab, var(--muted-foreground) 55%, transparent)";

export type TourIllustrationName =
  | "recipe"
  | "welcome"
  | "agents"
  | "workflows"
  | "knowledge"
  | "channels"
  | "tools"
  | "playground"
  | "monitor"
  // Member-only: the receiving side of sharing, drawn for a user who is given
  // agents rather than one who authors them.
  | "shared-with-me"
  | "help"
  // The on-device step (2026-09-19): a model downloaded INTO the reader's own
  // device. Dropped from the walk when NEXT_PUBLIC_EDGE_AGENTS is off.
  | "edge"
  // Platform-admin-only: the operator's own situation, which is that the
  // controls are in another application.
  | "platform"
  | "complete";

export type IllustrationProps = { className?: string };

/* ------------------------------------------------------------------ *
 * Motion
 *
 * A tour card is ~320px wide and the reader is being asked to read two bullet
 * points, so motion here is ambient at most: exactly one animated element per
 * illustration, never two. (An earlier revision of this note claimed six of the
 * ten had none at all; that was never true of the code - every drawing has
 * always carried its single `.vd-flow` or `.vd-pulse`. The budget is the real
 * rule, so the budget is what is written down.)
 *
 * The animation lives in a <style> element inside the SVG rather than in
 * globals.css for two reasons: this file owns its own presentation (B1 does not
 * edit globals.css), and `.hud-tour-*` in globals.css is UNLAYERED, so anything
 * that ended up next to it would win over Tailwind utilities in ways that are
 * hard to reason about. An inline SVG's <style> is still document-scoped
 * though - it is a real stylesheet in the document, not shadow DOM - so every
 * selector is namespaced under the illustration's own
 * `.vd-tour-ill-<name>` root class and every @keyframes name carries the same
 * suffix. All thirteen of these can be in the document at once (two are, during
 * every cross-fade) with no chance of collision.
 *
 * Reduced motion is handled the way the rest of the app handles it: a
 * per-block `@media (prefers-reduced-motion: reduce)` opt-out that kills the
 * animation and pins the property at its resting value. There is no global
 * reset in this codebase to rely on. Doing it in CSS rather than in JS also
 * sidesteps the hydration trap documented in lib/useReducedMotionSafe.ts -
 * CSS media queries need no first-render guess, so there is nothing for the
 * server and the client to disagree about.
 * ------------------------------------------------------------------ */

/**
 * Marching-ants flow along a path. Applied to a <g>, not to the paths inside
 * it: `stroke-dasharray` and `stroke-dashoffset` are *inherited* SVG
 * presentation properties, so animating the group animates every child in
 * lockstep while still being one animated element for the browser to composite.
 */
export function dashFlowCss(ns: string, dash: string, travel: number, duration: string): string {
  const anim = `vd-tour-flow-${ns}`;
  return [
    `.vd-tour-ill-${ns} .vd-flow{stroke-dasharray:${dash};animation:${anim} ${duration} linear infinite}`,
    `@keyframes ${anim}{from{stroke-dashoffset:${travel}}to{stroke-dashoffset:0}}`,
    `@media (prefers-reduced-motion:reduce){.vd-tour-ill-${ns} .vd-flow{animation:none;stroke-dasharray:none;stroke-dashoffset:0}}`,
  ].join("");
}

/**
 * Slow opacity breathe. Never fades below `min` so the element is legible at
 * every frame - this is emphasis, not a blink, and it must not read as a
 * loading state.
 */
export function pulseCss(ns: string, min: number, duration: string): string {
  const anim = `vd-tour-pulse-${ns}`;
  return [
    `.vd-tour-ill-${ns} .vd-pulse{animation:${anim} ${duration} ease-in-out infinite}`,
    `@keyframes ${anim}{0%,100%{opacity:1}50%{opacity:${min}}}`,
    `@media (prefers-reduced-motion:reduce){.vd-tour-ill-${ns} .vd-pulse{animation:none;opacity:1}}`,
  ].join("");
}

/* ------------------------------------------------------------------ *
 * Shared chrome
 * ------------------------------------------------------------------ */

/**
 * The common <svg> wrapper. Every illustration is drawn on the same 240x120
 * canvas so B3 can reserve one box and never see the card height jump between
 * steps.
 *
 * `role="img"` + `aria-hidden` is intentional and is the accessible-name story
 * for the whole set: the step title, body and two bullets already say
 * everything the picture says, so announcing the picture too would be a
 * duplicate reading. Making them decorative also avoids inventing twelve more
 * translatable alt strings for a 30-locale pipeline.
 *
 * `color` is pinned to `--muted-foreground` on the root rather than inherited,
 * so `currentColor` means one predictable thing (the quiet structural tier) no
 * matter which part of the tour card B3 mounts the illustration in.
 */
export function Canvas({
  name,
  css,
  className,
  children,
}: {
  name: TourIllustrationName;
  css?: string;
  className?: string;
  children: ReactNode;
}): ReactElement {
  return (
    <svg
      // Concatenated, not a Tailwind class: `vd-tour-ill-*` is authored by the
      // <style> below, so Tailwind v4's content scan has nothing to find and
      // nothing to tree-shake. The three utility classes next to it ARE
      // Tailwind and are written as whole literals for exactly that reason.
      className={"vd-tour-ill vd-tour-ill-" + name + " block h-auto w-full" + (className ? " " + className : "")}
      viewBox="0 0 240 120"
      preserveAspectRatio="xMidYMid meet"
      role="img"
      aria-hidden="true"
      focusable="false"
      style={{ color: "var(--muted-foreground)" }}
      xmlns="http://www.w3.org/2000/svg"
    >
      {css ? <style>{css}</style> : null}
      {children}
    </svg>
  );
}

/** Solid arrowhead pointing right, with its tip at (x, y). */
export function ArrowRight({ x, y, fill }: { x: number; y: number; fill: string }): ReactElement {
  return <path d={`M${x} ${y}l-6.5 -3.8v7.6z`} fill={fill} />;
}

/**
 * The agent glyph, reused across `knowledge`, `channels` and `tools` so the
 * same character recurs and the reader can follow it from one step to the
 * next - it is the subject of half the tour. A rounded head with an antenna is
 * a deliberately unremarkable "assistant" shorthand; it is NOT the brand mark,
 * which is only ever the real artwork (see `welcome`).
 */
export function AgentGlyph({ cx, cy }: { cx: number; cy: number }): ReactElement {
  return (
    <g>
      <rect
        x={cx - 16}
        y={cy - 13}
        width={32}
        height={28}
        rx={9}
        fill={ACCENT_FAINT}
        stroke={ACCENT}
        strokeWidth={1.6}
      />
      <path d={`M${cx} ${cy - 13}V${cy - 20}`} stroke={ACCENT} strokeWidth={1.6} strokeLinecap="round" />
      <circle cx={cx} cy={cy - 22.4} r={2.4} fill={ACCENT} />
      <circle cx={cx - 6} cy={cy - 2} r={2.6} fill={ACCENT} />
      <circle cx={cx + 6} cy={cy - 2} r={2.6} fill={ACCENT} />
      <path d={`M${cx - 5} ${cy + 7}H${cx + 5}`} stroke={ACCENT} strokeWidth={1.6} strokeLinecap="round" />
    </g>
  );
}

/**
 * The agent glyph again, at list-row scale - a 15x13 head with the same antenna
 * and the same two eyes, drawn from its top-left corner instead of its centre
 * because it is positioned inside rows, not composed around.
 *
 * It exists so `shared-with-me` can say "these rows are agents" with the part
 * the reader has already met at full size two steps earlier, rather than a
 * fresh icon they would have to decode. `AgentGlyph` itself is ~32x42 with the
 * antenna and does not fit in a 24px row.
 *
 * `quiet` drops it to the structural tier for a row that is drawn as "and more
 * below" rather than as a specific grant.
 *
 * The eyes are r=1.2 - the smallest marks in the whole set alongside `monitor`'s
 * file badges, and part of what sets the ~200px legibility floor in
 * TourIllustration's sizing contract. Below that they close up into one blob.
 */
export function MiniAgent({ x, y, quiet = false }: { x: number; y: number; quiet?: boolean }): ReactElement {
  const line = quiet ? LINE : ACCENT;
  const face = quiet ? FILL : ACCENT_FAINT;
  const mark = quiet ? FILL_STRONG : ACCENT;
  const eye = quiet ? QUIET : ACCENT;
  const cx = x + 7.5;

  return (
    <g>
      <rect x={x} y={y} width={15} height={13} rx={4.5} fill={face} stroke={line} strokeWidth={1.3} />
      <path d={`M${cx} ${y}V${y - 3.4}`} stroke={line} strokeWidth={1.3} strokeLinecap="round" />
      <circle cx={cx} cy={y - 4.6} r={1.3} fill={mark} />
      <circle cx={cx - 2.8} cy={y + 6.4} r={1.2} fill={eye} />
      <circle cx={cx + 2.8} cy={y + 6.4} r={1.2} fill={eye} />
    </g>
  );
}

