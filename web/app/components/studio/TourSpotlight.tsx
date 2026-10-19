"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { ArrowLeft, ArrowRight, Hand, X } from "lucide-react";
import { Button } from "../ui/button";
import { TOUR_PULSE, TONES } from "./studioTheme";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import "./studio.css";

// An interactive coach-mark tour over the REAL studio. It dims the page except
// for one control at a time, points at it, and - for steps marked as actions -
// waits for the visitor to actually do the thing (type, click, press Build)
// before moving on. It never blocks the page: the spotlight is a cut-out, not
// a wall, so the highlighted control (and everything else) stays clickable,
// and Skip / Esc are always there. A step whose control isn't on screen is
// skipped rather than left hanging.

export type TourStep = {
  id: string;
  /** Value of the target's data-tour attribute; null = a centred card with no spotlight. */
  target: string | null;
  title: string;
  body: ReactNode;
  placement?: "bottom" | "top" | "right" | "left";
  /** Runs when the step is entered - typically navigates to the right stage or tab. */
  onEnter?: () => void;
  /** Advance when the visitor clicks the highlighted control. */
  advanceOnClick?: boolean;
  /** Advance on its own once this turns true (polled). */
  advanceWhen?: () => boolean;
  /** Short imperative shown as "Try it" - marks the step as something to DO. */
  action?: string;
  /** Skip the step entirely when this is true. */
  skipIf?: () => boolean;
  /** Skip if the control never appears (e.g. a panel that only exists on wide screens). */
  optional?: boolean;
};

type Props = {
  open: boolean;
  steps: TourStep[];
  /** done = reached the end; false = skipped or dismissed. */
  onClose: (done: boolean) => void;
};

type Rect = { top: number; left: number; width: number; height: number };

const CARD_W = 340;
const PAD = 8;
const GAP = 14;

function findTarget(name: string | null): HTMLElement | null {
  if (!name) return null;
  const all = Array.from(document.querySelectorAll<HTMLElement>(`[data-tour="${name}"]`));
  // The same tour id can exist in a hidden layout (e.g. desktop-only aside); take the one that is actually visible.
  return all.find((el) => el.getClientRects().length > 0) ?? null;
}

function rectOf(el: HTMLElement): Rect {
  const r = el.getBoundingClientRect();
  return { top: r.top, left: r.left, width: r.width, height: r.height };
}

export default function TourSpotlight({ open, steps, onClose }: Props) {
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState<Rect | null>(null);
  const [cardH, setCardH] = useState(220);
  const cardRef = useRef<HTMLDivElement | null>(null);
  const missingSince = useRef<number | null>(null);
  const step = steps[index];

  // Restart from the top every time the tour is opened.
  useEffect(() => {
    if (open) setIndex(0);
  }, [open]);

  const go = useCallback(
    (dir: 1 | -1) => {
      let i = index + dir;
      while (i >= 0 && i < steps.length && steps[i].skipIf?.()) i += dir;
      if (i >= steps.length) {
        onClose(true);
        return;
      }
      setIndex(Math.max(0, i));
    },
    [index, steps, onClose]
  );

  // Entering a step: skip it if it does not apply, otherwise run its navigation.
  useEffect(() => {
    if (!open || !step) return;
    if (step.skipIf?.()) {
      go(1);
      return;
    }
    missingSince.current = null;
    step.onEnter?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, index]);

  // Track the target: it moves with scrolling, layout changes and tab switches.
  useEffect(() => {
    if (!open || !step) return undefined;
    let scrolled = false;
    const tick = () => {
      const el = findTarget(step.target);
      if (!step.target) {
        setRect(null);
        return;
      }
      if (!el) {
        setRect(null);
        if (missingSince.current == null) missingSince.current = Date.now();
        if (step.optional && Date.now() - missingSince.current > 900) go(1);
        return;
      }
      missingSince.current = null;
      if (!scrolled) {
        scrolled = true;
        // Instant, not smooth: the page is long, a smooth scroll across it is slow and the card would chase a moving target.
        el.scrollIntoView({ block: "center", behavior: "auto" });
      }
      const next = rectOf(el);
      setRect((cur) =>
        cur &&
        Math.abs(cur.top - next.top) < 0.5 &&
        Math.abs(cur.left - next.left) < 0.5 &&
        Math.abs(cur.width - next.width) < 0.5 &&
        Math.abs(cur.height - next.height) < 0.5
          ? cur
          : next
      );
    };
    tick();
    const id = window.setInterval(tick, 160);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, index]);

  // Interactive advance: a click on the highlighted control, or a condition turning true.
  useEffect(() => {
    if (!open || !step) return undefined;
    const timers: number[] = [];
    let onDocClick: ((e: MouseEvent) => void) | null = null;
    if (step.advanceOnClick && step.target) {
      onDocClick = (e: MouseEvent) => {
        const el = findTarget(step.target);
        if (el && e.target instanceof Node && el.contains(e.target)) {
          timers.push(window.setTimeout(() => go(1), 380));
        }
      };
      document.addEventListener("click", onDocClick, true);
    }
    if (step.advanceWhen) {
      const id = window.setInterval(() => {
        if (step.advanceWhen?.()) {
          window.clearInterval(id);
          timers.push(window.setTimeout(() => go(1), 450));
        }
      }, 300);
      timers.push(id);
    }
    return () => {
      if (onDocClick) document.removeEventListener("click", onDocClick, true);
      timers.forEach((t) => window.clearTimeout(t));
      timers.forEach((t) => window.clearInterval(t));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, index]);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Non-modal coach mark (FE-041): the page must stay usable so the visitor can DO each step, so there is no
  // Tab trap and no aria-modal. Focus moves to the card on open (so keyboard / screen-reader users land on it)
  // and returns to whatever launched the tour when it closes. Escape stays with the effect above.
  useDialogFocus(cardRef, cardRef, null, { enabled: open, trap: false });

  // Re-measure when the card opens or its content (the step) changes; the width is fixed, so nothing else
  // changes its height. setCardH bails out on an unchanged value, so this can never loop.
  useLayoutEffect(() => {
    if (cardRef.current) setCardH(cardRef.current.offsetHeight);
  }, [open, step]);

  if (!open || !step) return null;

  // A hidden or not-yet-laid-out window can report 0; fall back so the card never collapses into a corner.
  const vw = typeof window === "undefined" ? 1200 : window.innerWidth || document.documentElement.clientWidth || 1200;
  const vh = typeof window === "undefined" ? 800 : window.innerHeight || document.documentElement.clientHeight || 800;
  const spot = rect
    ? { top: rect.top - PAD, left: rect.left - PAD, width: rect.width + PAD * 2, height: rect.height + PAD * 2 }
    : null;

  // Card placement: prefer the requested side, fall back to whichever has room, finally pin to a corner.
  let cardStyle: React.CSSProperties = {
    top: Math.max(12, vh / 2 - cardH / 2),
    left: Math.max(12, vw / 2 - CARD_W / 2),
  };
  if (spot) {
    const clampLeft = (l: number) => Math.min(Math.max(12, l), vw - CARD_W - 12);
    const below = spot.top + spot.height + GAP;
    const above = spot.top - GAP - cardH;
    const fitsBelow = below + cardH <= vh - 12;
    const fitsAbove = above >= 12;
    const rightX = spot.left + spot.width + GAP;
    const leftX = spot.left - GAP - CARD_W;
    const order = step.placement
      ? [step.placement, "bottom", "top", "right", "left"]
      : ["bottom", "top", "right", "left"];
    let placed = false;
    for (const side of order) {
      if (side === "bottom" && fitsBelow) {
        cardStyle = { top: below, left: clampLeft(spot.left) };
        placed = true;
      } else if (side === "top" && fitsAbove) {
        cardStyle = { top: above, left: clampLeft(spot.left) };
        placed = true;
      } else if (side === "right" && rightX + CARD_W <= vw - 12) {
        cardStyle = { top: Math.min(Math.max(12, spot.top), vh - cardH - 12), left: rightX };
        placed = true;
      } else if (side === "left" && leftX >= 12) {
        cardStyle = { top: Math.min(Math.max(12, spot.top), vh - cardH - 12), left: leftX };
        placed = true;
      }
      if (placed) break;
    }
    if (!placed) cardStyle = { top: vh - cardH - 16, left: vw - CARD_W - 16 };
  }

  if (typeof cardStyle.top === "number") {
    cardStyle = { ...cardStyle, top: Math.min(Math.max(12, cardStyle.top), Math.max(12, vh - cardH - 12)) };
  }

  const isAction = !!(step.action || step.advanceOnClick || step.advanceWhen);
  const last = index === steps.length - 1;
  const t = TONES.violet;

  return (
    <div className="fixed inset-0 z-[80]" style={{ pointerEvents: "none" }} aria-live="polite">
      {/* Dim: a cut-out around the target (clicks pass through), or a plain veil for a centred step. */}
      {spot ? (
        <div
          className="studio-spotlight fixed rounded-2xl"
          style={{
            top: spot.top,
            left: spot.left,
            width: spot.width,
            height: spot.height,
            boxShadow: "0 0 0 9999px rgba(15, 10, 40, 0.62)",
            pointerEvents: "none",
          }}
        >
          {isAction && <div className={`absolute inset-0 rounded-2xl ${TOUR_PULSE}`} />}
          <div className="absolute inset-0 rounded-2xl ring-2 ring-violet-400/90" />
        </div>
      ) : (
        <div className="fixed inset-0 bg-[rgba(15,10,40,0.62)]" style={{ pointerEvents: "auto" }} aria-hidden="true" />
      )}

      <div
        ref={cardRef}
        tabIndex={-1}
        role="dialog"
        aria-label={`Tour: ${step.title}`}
        className="fixed rounded-2xl border border-violet-300/40 bg-background p-4 shadow-2xl shadow-violet-900/30 outline-none"
        style={{ ...cardStyle, width: CARD_W, maxWidth: "calc(100vw - 24px)", pointerEvents: "auto" }}
      >
        <div className="mb-1 flex items-center justify-between gap-2">
          <span className={`text-[11px] font-semibold uppercase tracking-wide ${t.text}`}>
            Step {index + 1} of {steps.length}
          </span>
          <button
            type="button"
            onClick={() => onClose(false)}
            aria-label="Skip the tour"
            className="text-muted-foreground hover:text-foreground"
          >
            <X aria-hidden="true" className="h-4 w-4 shrink-0" />
          </button>
        </div>
        <div className="text-base font-semibold leading-snug">{step.title}</div>
        <div className="mt-1 text-sm text-muted-foreground">{step.body}</div>
        {step.action && (
          <div className="mt-3 flex items-center gap-2 rounded-lg bg-emerald-500/10 px-2.5 py-1.5 text-xs font-medium text-emerald-700 dark:text-emerald-300">
            <Hand aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Try it: {step.action}
          </div>
        )}

        <div className="mt-3 flex gap-1" aria-hidden>
          {steps.map((s, i) => (
            <span
              key={s.id}
              className={`h-1 flex-1 rounded-full ${i < index ? "bg-violet-500" : i === index ? "bg-violet-400" : "bg-muted"}`}
            />
          ))}
        </div>

        <div className="mt-3 flex items-center gap-2">
          <Button variant="ghost" size="sm" disabled={index === 0} onClick={() => go(-1)}>
            <ArrowLeft aria-hidden="true" className="mr-1 h-3.5 w-3.5 shrink-0" /> Back
          </Button>
          <Button variant="ghost" size="sm" className="text-muted-foreground" onClick={() => onClose(false)}>
            Skip tour
          </Button>
          <Button size="sm" className={`ml-auto ${t.cta}`} onClick={() => go(1)}>
            {last ? "Finish" : isAction ? "Skip ahead" : "Next"}{" "}
            {!last && <ArrowRight aria-hidden="true" className="ml-1 h-3.5 w-3.5 shrink-0" />}
          </Button>
        </div>
      </div>
    </div>
  );
}
