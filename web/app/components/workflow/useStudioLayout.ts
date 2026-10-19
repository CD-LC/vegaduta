"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties } from "react";

/**
 * Studio layout for the workflow designer (2026-09-29): the canvas fills the
 * whole designer area and the node palette (left) and inspector (right)
 * float over it as translucent panels that can be resized, collapsed to an
 * icon rail, or hidden together ("zen"). This hook owns that state, persists
 * it per browser, and turns it into:
 *
 * - CSS custom properties on the shell (`--wf-left`, `--wf-right`, ...) that
 *   every floating element positions itself against, so nothing else needs
 *   to know which panel is open;
 * - a `fitView` padding object, so "fit to screen" frames the graph in the
 *   space the panels actually leave free instead of underneath them.
 *
 * Keyboard: `[` toggles the palette, `]` the inspector, `\` both (zen).
 * Ignored while typing or with a modifier held, like the canvas's other
 * single-key shortcuts.
 */

export const PANEL_GAP = 12;
export const RAIL_WIDTH = 52;
/** Floating top bar (10px inset + ~52px bar) plus a gap. */
export const TOP_RESERVE = 74;
/** Canvas toolbar + describe bar at the bottom. */
export const BOTTOM_RESERVE = 128;

export const LEFT_LIMITS = { min: 200, max: 380, initial: 240 } as const;
/** Below this width the panels are off-canvas drawers (globals.css), not floating panels. */
export const DRAWERS_BELOW = 900;
/** Below this viewport width an open node library auto-collapses to its rail (not persisted). */
export const COMPACT_BELOW = 1200;
/** The inspector never takes more than this share of the viewport. */
const RIGHT_MAX_SHARE = 0.36;
export const RIGHT_LIMITS = { min: 300, max: 620, initial: 380 } as const;

const STORAGE_KEY = "wf-studio-layout-v1";

/** React Flow's per-side fitView padding wants unit-typed strings. */
type Px = `${number}px`;
export type StudioFitPadding = { top: Px; left: Px; right: Px; bottom: Px };
const px = (n: number): Px => `${n}px`;

type Persisted = {
  leftWidth: number;
  rightWidth: number;
  leftCollapsed: boolean;
};

const DEFAULTS: Persisted = {
  leftWidth: LEFT_LIMITS.initial,
  rightWidth: RIGHT_LIMITS.initial,
  leftCollapsed: false,
};

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, Math.round(value)));
}

function readPersisted(): Persisted {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULTS;
    const p = JSON.parse(raw) as Partial<Persisted>;
    return {
      leftWidth: clamp(Number(p.leftWidth) || DEFAULTS.leftWidth, LEFT_LIMITS.min, LEFT_LIMITS.max),
      rightWidth: clamp(Number(p.rightWidth) || DEFAULTS.rightWidth, RIGHT_LIMITS.min, RIGHT_LIMITS.max),
      leftCollapsed: p.leftCollapsed === true,
    };
  } catch {
    return DEFAULTS;
  }
}

function isTypingTarget(target: EventTarget | null) {
  const el = target as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable || !!el.closest("[role='dialog']");
}

export interface StudioLayoutOptions {
  /** The palette is not rendered at all (observe mode is read-only). */
  leftHidden: boolean;
  /** The inspector is not rendered at all (observing a finished run). */
  rightHidden: boolean;
  /** A node was just selected - the inspector should be visible to edit it. */
  selectedNodeId: string | null;
  /**
   * The inspector has something to show right now (a selected node, a live
   * or stoppable run, an undismissed run result). The right panel is shown
   * ONLY while this is true or the user opened it explicitly - it is never a
   * permanent column eating canvas width.
   */
  rightNeeded: boolean;
}

export function useStudioLayout({ leftHidden, rightHidden, selectedNodeId, rightNeeded }: StudioLayoutOptions) {
  const [state, setState] = useState<Persisted>(DEFAULTS);
  const [zen, setZen] = useState(false);
  // Explicit "show the inspector" from the toggle / `]` - session only.
  const [rightPinned, setRightPinned] = useState(false);
  const [hydrated, setHydrated] = useState(false);
  const [viewportWidth, setViewportWidth] = useState<number | null>(null);
  // Narrow screens auto-collapse the library; this is the user opening it anyway.
  const [compactOpen, setCompactOpen] = useState(false);
  const compact = viewportWidth !== null && viewportWidth < COMPACT_BELOW;

  // localStorage is read after mount so the server render and the first
  // client render agree (no hydration mismatch); the panels then settle.
  useEffect(() => {
    setState(readPersisted());
    setHydrated(true);
    const onResize = () => setViewportWidth(window.innerWidth);
    onResize();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch {
      // Private mode / blocked storage: layout still works, just not remembered.
    }
  }, [state, hydrated]);

  // Selecting a node leaves zen so the inspector (needed now) can show.
  useEffect(() => {
    if (selectedNodeId) setZen(false);
  }, [selectedNodeId]);

  const toggleLeft = useCallback(() => {
    setZen(false);
    if (compact) {
      setState((s) => (s.leftCollapsed ? { ...s, leftCollapsed: false } : s));
      setCompactOpen((v) => !v);
      return;
    }
    setState((s) => ({ ...s, leftCollapsed: !s.leftCollapsed }));
  }, [compact]);
  // Opening pins it; closing is the canvas's job too (deselect / dismiss the
  // run) - see onCloseRight in WorkflowCanvas - so here it only unpins.
  const toggleRight = useCallback(() => {
    setZen(false);
    setRightPinned((v) => !v);
  }, []);
  const expandLeft = useCallback(() => {
    setZen(false);
    if (compact) setCompactOpen(true);
    setState((s) => ({ ...s, leftCollapsed: false }));
  }, [compact]);
  const expandRight = useCallback(() => {
    setZen(false);
    setRightPinned(true);
  }, []);
  const unpinRight = useCallback(() => setRightPinned(false), []);
  // The canvas owns what "closing the inspector" means (deselect, dismiss the
  // run); it installs that here so the `]` shortcut does the same thing.
  const rightToggleOverride = useRef<(() => void) | null>(null);
  const toggleZen = useCallback(() => setZen((z) => !z), []);
  const setLeftWidth = useCallback(
    (w: number) => setState((s) => ({ ...s, leftWidth: clamp(w, LEFT_LIMITS.min, LEFT_LIMITS.max) })),
    [],
  );
  // The inspector's effective ceiling shrinks with the viewport (see
  // RIGHT_MAX_SHARE); dragging past it would store a width with no visible
  // effect, so clamp to what can actually be shown.
  const rightMax =
    viewportWidth === null ? RIGHT_LIMITS.max : Math.max(RIGHT_LIMITS.min, Math.min(RIGHT_LIMITS.max, Math.round(viewportWidth * RIGHT_MAX_SHARE)));
  const setRightWidth = useCallback(
    (w: number) => setState((s) => ({ ...s, rightWidth: clamp(w, RIGHT_LIMITS.min, rightMax) })),
    [rightMax],
  );

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey || isTypingTarget(e.target)) return;
      if (e.key === "[") {
        e.preventDefault();
        toggleLeft();
      } else if (e.key === "]") {
        e.preventDefault();
        (rightToggleOverride.current ?? toggleRight)();
      } else if (e.key === "\\") {
        e.preventDefault();
        toggleZen();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [toggleLeft, toggleRight, toggleZen]);

  // Auto-compaction on narrow screens is derived, never written back, so the
  // user's saved preference comes back as soon as the window is wide again.
  const leftMode: "hidden" | "rail" | "open" =
    leftHidden || zen ? "hidden" : state.leftCollapsed || (compact && !compactOpen) ? "rail" : "open";
  const rightMode: "hidden" | "rail" | "open" = rightHidden || zen || !(rightNeeded || rightPinned) ? "hidden" : "open";

  // Horizontal space each side occupies over the canvas, including the gap.
  const rightWidth =
    viewportWidth === null
      ? state.rightWidth
      : Math.max(RIGHT_LIMITS.min, Math.min(state.rightWidth, Math.round(viewportWidth * RIGHT_MAX_SHARE)));
  // Drawers overlay the canvas temporarily, so on small screens the panels
  // reserve no space at all.
  const drawers = viewportWidth !== null && viewportWidth < DRAWERS_BELOW;
  const leftSpan = drawers ? 0 : leftMode === "open" ? state.leftWidth + PANEL_GAP : leftMode === "rail" ? RAIL_WIDTH + PANEL_GAP : 0;
  const rightSpan = drawers || rightMode !== "open" ? 0 : rightWidth + PANEL_GAP;

  // Canvas width left between the panels (AppShell's 64px rail shows from
  // 1024px up). Drives `data-free` on the shell so floating chrome can shed
  // secondary controls instead of overflowing.
  const freeWidth =
    viewportWidth === null ? null : viewportWidth - (viewportWidth >= 1024 ? 64 : 0) - leftSpan - rightSpan - 2 * PANEL_GAP;
  const freeSize: "narrow" | "normal" = freeWidth !== null && freeWidth < 640 ? "narrow" : "normal";

  const cssVars = useMemo(
    () =>
      ({
        "--wf-left-w": `${state.leftWidth}px`,
        "--wf-right-w": `${rightWidth}px`,
        "--wf-left": `${leftSpan}px`,
        "--wf-right": `${rightSpan}px`,
        "--wf-gap": `${PANEL_GAP}px`,
        "--wf-rail": `${RAIL_WIDTH}px`,
        "--wf-top": `${TOP_RESERVE}px`,
      }) as CSSProperties,
    [state.leftWidth, rightWidth, leftSpan, rightSpan],
  );

  // fitView padding in px per side - React Flow 12.5+ accepts an object.
  const fitPadding = useMemo<StudioFitPadding>(
    () => ({
      top: px(TOP_RESERVE + 16),
      left: px(leftSpan + PANEL_GAP + 24),
      right: px(rightSpan + PANEL_GAP + 24),
      bottom: px(BOTTOM_RESERVE),
    }),
    [leftSpan, rightSpan],
  );

  return {
    leftMode,
    rightMode,
    zen,
    leftWidth: state.leftWidth,
    rightWidth,
    rightMax,
    freeSize,
    /** True once the saved layout and viewport size are known - fit the graph after this. */
    ready: hydrated && viewportWidth !== null,
    toggleLeft,
    toggleRight,
    unpinRight,
    rightPinned,
    rightToggleOverride,
    expandLeft,
    expandRight,
    toggleZen,
    setLeftWidth,
    setRightWidth,
    cssVars,
    fitPadding,
  };
}
