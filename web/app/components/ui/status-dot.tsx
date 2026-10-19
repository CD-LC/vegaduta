"use client";

import type { CSSProperties } from "react";
import { cn } from "../../lib/utils";

// Shared connection/health indicator dot - consolidates five previously
// independent implementations that had drifted apart (design-system audit):
//   - dashboard/SystemStatusPanel.tsx's local StatusDot (.mc-status-dot,
//     --hud-ok/--danger-text/--hud-text-dim)
//   - dashboard/ConnectedPlatformsPanel.tsx's local StatusDot (same classes)
//   - ApprovalsPanel.tsx's inline `style={{ background: dotColor(status) }}`
//     (--ax-amber/--ax-green/--ax-red/--ax-dim)
//   - GoogleIntegrationPanel.tsx's `.ax-mnt-status-dot` + parent modifier
//     classes (--ax-green/--ax-red)
//   - ChannelsPanel.tsx's local StatusDot using raw, un-tokenized Tailwind
//     palette classes (bg-emerald-500/bg-amber-500/bg-rose-500)
// This is a de-duplication refactor, not a redesign - each call site passes
// `colors`/`size`/`glow`/`pulse`/`style` to reproduce its previous exact look
// where that look was already token-driven and deliberate (the HUD dashboard
// panels), while ChannelsPanel's raw Tailwind colors are replaced outright
// with the app's real --ax-* design tokens (the actual bug this consolidation
// was meant to fix there).
export type StatusDotStatus = "up" | "down" | "pending" | "unknown";

// Default palette: the app's own --ax-green/--ax-amber/--ax-red/--ax-dim
// tokens (globals.css) - already the canonical "status" colors used by
// ApprovalsPanel's dotColor()/GoogleIntegrationPanel's .ax-mnt-status-* rules
// before this refactor.
const DEFAULT_COLOR: Record<StatusDotStatus, string> = {
  up: "var(--ax-green)",
  down: "var(--ax-red)",
  pending: "var(--ax-amber)",
  unknown: "var(--ax-dim)",
};

// 2026-07-29 design-system contract (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md
// §3/§4) adds a second, opt-in rendering system: `.state-dot`/`.state-dot-live`,
// which derives its colour from a single `--state` custom property and gets an
// expanding ring for live/running states instead of a bare opacity pulse. This
// is layered on top via the new `system`/`stateColor`/`live` props below -
// every existing prop (`colors`/`size`/`glow`/`pulse`/`style`) keeps working
// exactly as before for call sites that don't opt in.
export type StatusDotSystem = "legacy" | "state";

// Default status -> --state-* hue mapping for the new system. Loosely mirrors
// DEFAULT_COLOR's semantics (up/down/pending/unknown) onto the state hues
// (success/error/queued/idle) rather than reusing the --ax-* palette.
const DEFAULT_STATE_TOKEN: Record<StatusDotStatus, string> = {
  up: "var(--state-success)",
  down: "var(--state-error)",
  pending: "var(--state-queued)",
  unknown: "var(--state-idle)",
};

export interface StatusDotProps {
  /** Connection/health state this dot represents. */
  status: StatusDotStatus;
  /** Diameter in px. Defaults to 7 - the size most existing call sites used. */
  size?: number;
  /** Glow halo around the dot. `true` uses a sensible default blur radius; a
   *  number sets the blur radius in px directly; `false` disables the glow.
   *  Only applies to the default `system="legacy"` rendering. */
  glow?: boolean | number;
  /** Soft opacity pulse (Tailwind's `animate-pulse`) for "live"/pending dots.
   *  Only applies to the default `system="legacy"` rendering. */
  pulse?: boolean;
  /** Per-status color override so a call site anchored to a different (but
   *  equivalent) existing token family - e.g. the dashboard HUD panels'
   *  --hud-ok/--danger-text/--hud-text-dim - can keep its exact prior color
   *  while still going through this one shared component, instead of every
   *  dot silently repainting to the AXON palette. Only applies to
   *  `system="legacy"`. */
  colors?: Partial<Record<StatusDotStatus, string>>;
  className?: string;
  /** Extra inline style, merged last (e.g. a call site's own bespoke
   *  keyframe animation) - lets a site preserve behavior this component's
   *  own `pulse` prop doesn't cover. */
  style?: CSSProperties;
  /** Opt-in: render via the shared `.state-dot`/`.state-dot-live` system
   *  instead of this component's original inline-style rendering. Default
   *  `"legacy"` - existing call sites are completely unaffected. */
  system?: StatusDotSystem;
  /** `system="state"` only: the `--state` custom-property value, e.g.
   *  `"var(--state-running)"`. Defaults to a status->state-hue mapping
   *  (up=success, down=error, pending=queued, unknown=idle) when omitted. */
  stateColor?: string;
  /** `system="state"` only: adds the expanding "live" ring
   *  (`.state-dot-live`) used for running/in-progress states. */
  live?: boolean;
}

export function StatusDot({
  status,
  size = 7,
  glow = true,
  pulse = false,
  colors,
  className,
  style,
  system = "legacy",
  stateColor,
  live = false,
}: StatusDotProps) {
  if (system === "state") {
    const stateVar = stateColor ?? DEFAULT_STATE_TOKEN[status] ?? DEFAULT_STATE_TOKEN.unknown;
    return (
      <span
        aria-hidden="true"
        className={cn("state-dot", live && "state-dot-live", className)}
        style={{
          width: size,
          height: size,
          ["--state" as string]: stateVar,
          ...style,
        }}
      />
    );
  }

  const color = colors?.[status] ?? DEFAULT_COLOR[status] ?? DEFAULT_COLOR.unknown;
  const blurPx = glow === false ? 0 : glow === true ? 6 : glow;

  return (
    <span
      aria-hidden="true"
      className={cn("inline-block shrink-0 rounded-full", pulse && "animate-pulse", className)}
      style={{
        width: size,
        height: size,
        background: color,
        boxShadow: blurPx ? `0 0 ${blurPx}px color-mix(in srgb, ${color} 70%, transparent)` : undefined,
        ...style,
      }}
    />
  );
}
