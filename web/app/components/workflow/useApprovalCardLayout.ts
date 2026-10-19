"use client";

// Layout and focus hooks of the approval pause card: anchoring beside the
// parked node (flipped/clamped inside the stage, a bottom sheet below 640px)
// and handing focus back when the card or its decision buttons unmount.
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type FocusEvent as ReactFocusEvent,
  type RefObject,
} from "react";
import type { ApprovalCardAnchor } from "./ApprovalPauseCard";

export type CardPosition = { left: number; top: number; side: "left" | "right" };

/** Position of the card beside `anchor`; null docks it (no anchor, or a narrow viewport). */
export function useCardPlacement(
  cardRef: RefObject<HTMLDivElement | null>,
  anchor: ApprovalCardAnchor | null | undefined,
): CardPosition | null {
  const [pos, setPos] = useState<CardPosition | null>(null);

  const place = useCallback(() => {
    const el = cardRef.current;
    const parent = el?.offsetParent;
    if (!el || !(parent instanceof HTMLElement) || !anchor || window.matchMedia("(max-width: 639.98px)").matches) {
      setPos((p) => (p === null ? p : null));
      return;
    }
    const gap = 14;
    const pw = parent.clientWidth;
    const ph = parent.clientHeight;
    const cw = el.offsetWidth;
    const ch = el.offsetHeight;
    let side: "left" | "right" = "right";
    let left = anchor.x + (anchor.width ?? 0) + gap;
    if (left + cw > pw - 8) {
      const alt = anchor.x - cw - gap;
      if (alt >= 8) {
        left = alt;
        side = "left";
      } else {
        left = Math.max(8, pw - cw - 8);
      }
    }
    const top = Math.round(Math.min(Math.max(8, anchor.y - 6), Math.max(8, ph - ch - 8)));
    left = Math.round(left);
    setPos((p) => (p && p.left === left && p.top === top && p.side === side ? p : { left, top, side }));
  }, [anchor, cardRef]);

  useLayoutEffect(() => {
    place();
    const el = cardRef.current;
    if (!el || typeof ResizeObserver === "undefined") return undefined;
    const ro = new ResizeObserver(() => place());
    ro.observe(el);
    if (el.offsetParent instanceof HTMLElement) ro.observe(el.offsetParent);
    return () => ro.disconnect();
  }, [place, cardRef]);

  return pos;
}

/**
 * Focus bookkeeping: where focus came from when it entered the card, and
 * whether it is still inside. The card unmounts on close/resolve and its
 * decision buttons unmount on success, so focus must be put back by hand.
 */
export function useCardFocusReturn(
  cardRef: RefObject<HTMLDivElement | null>,
  done: string | null,
  onFocusFallback: (() => void) | undefined,
) {
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const hasFocusRef = useRef(false);
  const onFocusFallbackRef = useRef(onFocusFallback);
  useEffect(() => {
    onFocusFallbackRef.current = onFocusFallback;
  }, [onFocusFallback]);

  useEffect(() => {
    // Removing a focused button fires no blur, so hasFocusRef still says true.
    if (done && hasFocusRef.current) cardRef.current?.focus();
  }, [done, cardRef]);

  useEffect(() => {
    return () => {
      if (!hasFocusRef.current) return;
      const back = returnFocusRef.current;
      const fallback = onFocusFallbackRef.current;
      // After the unmount commits: only rescue focus that fell to <body>.
      setTimeout(() => {
        const active = document.activeElement;
        if (active && active !== document.body) return;
        if (back && back.isConnected) back.focus();
        else fallback?.();
      }, 0);
    };
  }, []);

  function onFocusCapture(e: ReactFocusEvent<HTMLDivElement>) {
    hasFocusRef.current = true;
    const from = e.relatedTarget;
    if (from instanceof HTMLElement && !cardRef.current?.contains(from)) returnFocusRef.current = from;
  }

  function onBlurCapture(e: ReactFocusEvent<HTMLDivElement>) {
    const to = e.relatedTarget;
    if (to instanceof Node && cardRef.current?.contains(to)) return;
    // Focus left for somewhere real (Tab out, a click elsewhere).
    if (to) hasFocusRef.current = false;
  }

  return { onFocusCapture, onBlurCapture };
}
