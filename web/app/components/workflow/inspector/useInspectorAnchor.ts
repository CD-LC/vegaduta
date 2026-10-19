import { useLayoutEffect, useState, type RefObject } from "react";
import { isNum } from "./format";
import type { InspectorAnchor } from "./types";

export type AnchoredPosition = { left: number; top: number; side: "left" | "right" | "none"; notch: number };

/**
 * Node anchoring (desktop only; CSS turns the card into a bottom sheet on
 * phones): places the card beside the selected node, flipping to the left or
 * docking when there is no room.
 */
export function useInspectorAnchor(rootRef: RefObject<HTMLDivElement | null>, anchor?: InspectorAnchor | null) {
  const [pos, setPos] = useState<AnchoredPosition | null>(null);
  const ax = anchor?.x;
  const ay = anchor?.y;
  const aw = anchor?.width ?? 0;
  const ah = anchor?.height ?? 0;
  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el || !isNum(ax) || !isNum(ay)) {
      setPos(null);
      return undefined;
    }
    const place = () => {
      const parent = el.offsetParent instanceof HTMLElement ? el.offsetParent : null;
      const pw = parent?.clientWidth ?? window.innerWidth;
      const ph = parent?.clientHeight ?? window.innerHeight;
      const w = el.offsetWidth;
      const h = el.offsetHeight;
      const gap = 14;
      const pad = 8;
      let side: "left" | "right" | "none" = "right";
      let left = ax + aw + gap;
      if (left + w > pw - pad) {
        const alt = ax - gap - w;
        if (alt >= pad) {
          left = alt;
          side = "left";
        } else {
          left = Math.max(pad, pw - pad - w);
          side = "none";
        }
      }
      const cy = ay + ah / 2;
      const top = Math.max(pad, Math.min(cy - 44, ph - pad - h));
      const notch = Math.max(18, Math.min(cy - top, h - 18));
      setPos((p) =>
        p && p.left === left && p.top === top && p.side === side && p.notch === notch ? p : { left, top, side, notch },
      );
    };
    place();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", place);
      return () => window.removeEventListener("resize", place);
    }
    const ro = new ResizeObserver(place);
    ro.observe(el);
    if (el.offsetParent instanceof HTMLElement) ro.observe(el.offsetParent);
    return () => ro.disconnect();
  }, [rootRef, ax, ay, aw, ah]);
  return pos;
}
