"use client";

import type { ComponentProps, CSSProperties } from "react";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Card (new-york), token-driven. Uses --card/--border so
// it reads as a glass surface on the dark landing canvas.
//
// 2026-07-29 design-system contract additions
// (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md) - all opt-in, default
// appearance is unchanged so existing call sites don't move:
//   - `elevation` maps to the shared .elev-sunken/.elev-base/.elev-raised
//     depth rungs, replacing the default border/bg pair when set.
//   - `interactive` wires the shared .card-interactive hover treatment
//     (lift, border tint, --cat-tinted top hairline on hover/focus).
//   - `cat` passes a --cat-* token through as the `--cat` custom property
//     that .card-interactive's hairline and any nested .cat-chip/.cat-tint
//     read (see contract §2/§4). No visible effect on its own unless
//     `interactive` is set or a child reads --cat.
export type CardElevation = "sunken" | "base" | "raised";

const ELEVATION_CLASS: Record<CardElevation, string> = {
  sunken: "elev-sunken",
  base: "elev-base",
  raised: "elev-raised",
};

export interface CardProps extends ComponentProps<"div"> {
  /** Opt-in elevation rung. When set, replaces the default
   *  `border-border bg-card shadow-sm` styling with the matching
   *  `.elev-*` utility. Default: undefined - unchanged appearance. */
  elevation?: CardElevation;
  /** Opt-in hover/focus interaction treatment (`.card-interactive`).
   *  Default: false - unchanged appearance. */
  interactive?: boolean;
  /** Category accent passthrough, e.g. `cat="var(--cat-data)"`. */
  cat?: string;
}

function Card({ className, elevation, interactive = false, cat, style, ...props }: CardProps) {
  return (
    <div
      data-slot="card"
      className={cn(
        "flex flex-col gap-6 rounded-xl py-6 text-card-foreground",
        // Aurora depth layer (2026-08-12): the default resting shadow is the
        // layered --shadow-card token (ambient wash + contact line; inset
        // top-highlight in dark) instead of the flat Tailwind shadow-sm -
        // one-line, app-wide dimensionality for every existing Card. .glossy
        // adds the per-mode curvature gradient (top light-catch in dark,
        // bottom shade in light) - background-image only, so any bg-* or
        // shadow override a call site sets still applies untouched.
        elevation ? ELEVATION_CLASS[elevation] : "glossy border border-border bg-card shadow-[var(--shadow-card)]",
        interactive && "card-interactive",
        className
      )}
      style={cat ? ({ ["--cat" as string]: cat, ...style } as CSSProperties) : style}
      {...props}
    />
  );
}

function CardHeader({ className, ...props }: ComponentProps<"div">) {
  return <div data-slot="card-header" className={cn("flex flex-col gap-1.5 px-6", className)} {...props} />;
}

function CardTitle({ className, ...props }: ComponentProps<"div">) {
  return <div data-slot="card-title" className={cn("font-semibold leading-none tracking-tight", className)} {...props} />;
}

function CardDescription({ className, ...props }: ComponentProps<"div">) {
  return <div data-slot="card-description" className={cn("text-sm text-muted-foreground", className)} {...props} />;
}

function CardContent({ className, ...props }: ComponentProps<"div">) {
  return <div data-slot="card-content" className={cn("px-6", className)} {...props} />;
}

function CardFooter({ className, ...props }: ComponentProps<"div">) {
  return <div data-slot="card-footer" className={cn("flex items-center px-6", className)} {...props} />;
}

export { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter };
