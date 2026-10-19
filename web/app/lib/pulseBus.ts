"use client";

// Tiny in-memory pub/sub, not a DOM CustomEvent bus - this only ever needs
// to reach GlobalBackgroundFX.js within the same JS realm/tab, so a plain
// module-level Set avoids the SSR "window is undefined" guard a
// window.dispatchEvent approach would need at every call site, and avoids
// serializing detail payloads through the DOM event system for no reason.
//
// The whole point: any component, anywhere (a nav click, an admin
// approve/reject button, eventually anything else) can call firePulse(...)
// to make the global reactive background (GlobalBackgroundFX.js) visibly
// react to what just happened, without importing or coupling to that
// component directly. "kind" drives the ripple's color there - see its own
// KIND_COLORS map, chosen per the researched microinteraction convention
// (green = success/confirm, red = danger/reject, cyan = navigation, violet
// = ambient default for an otherwise-unclassified click).

// What a caller may pass: coordinates are optional (defaulted to viewport
// centre below), kind/strength optional.
export interface PulseInput {
  x?: number;
  y?: number;
  kind?: string;
  strength?: number;
}

// What listeners actually receive: coordinates always resolved to numbers.
export interface PulseDetail {
  x: number;
  y: number;
  kind: string;
  strength: number;
}

export type PulseListener = (detail: PulseDetail) => void;

const LISTENERS = new Set<PulseListener>();

export function onPulse(fn: PulseListener): () => void {
  LISTENERS.add(fn);
  return () => {
    LISTENERS.delete(fn);
  };
}

export function firePulse({ x, y, kind = "neutral", strength = 1 }: PulseInput = {}): PulseDetail {
  const detail: PulseDetail = {
    x: x ?? (typeof window !== "undefined" ? window.innerWidth / 2 : 0),
    y: y ?? (typeof window !== "undefined" ? window.innerHeight / 2 : 0),
    kind,
    strength,
  };
  LISTENERS.forEach((fn) => fn(detail));
  return detail;
}

// Convenience for the overwhelmingly common case - "make the background
// react at wherever this button/link the person just clicked actually is,"
// rather than every call site computing getBoundingClientRect() by hand.
export function firePulseAtElement(el: Element | null | undefined, kind: string = "neutral"): PulseDetail {
  if (!el || typeof el.getBoundingClientRect !== "function") {
    return firePulse({ kind });
  }
  const rect = el.getBoundingClientRect();
  return firePulse({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, kind });
}
