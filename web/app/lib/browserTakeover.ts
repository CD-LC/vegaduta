import type { BrowserTakeoverInput, BrowserTakeoverView } from "./api/browserTakeover";

// Pure helpers behind the browser takeover panel (the person drives the agent's browser). Kept out of the
// component so the rules - how a pointer position becomes a frame pixel, which key presses are relayed, how a
// paste is bounded, how the countdown reads - are unit-testable without a DOM. They mirror core's validation
// (BrowserTakeoverService: key whitelist, text cap, coordinate bounds) only to give instant feedback; the
// server stays the authority and re-checks everything.

/** Named keys core relays (anything else must be a single printable character). */
export const NAMED_KEYS = new Set([
  "Enter",
  "Tab",
  "Backspace",
  "Delete",
  "Escape",
  "ArrowUp",
  "ArrowDown",
  "ArrowLeft",
  "ArrowRight",
  "Home",
  "End",
  "PageUp",
  "PageDown",
]);

/** Default per-paste cap; the live value comes from core's `maxTypeChars`. */
export const DEFAULT_MAX_TYPE_CHARS = 256;

/**
 * A pointer position inside the drawn frame, in FRAME pixels. `rect` is the canvas's on-screen box, (frameWidth x
 * frameHeight) the pixel size of the frame it shows - core scales these to page coordinates, so the client never
 * needs to know the page's size. Returns null when the box has no area (not laid out yet).
 */
export function pointerToFramePoint(
  clientX: number,
  clientY: number,
  rect: { left: number; top: number; width: number; height: number },
  frameWidth: number,
  frameHeight: number,
): { x: number; y: number } | null {
  if (!Number.isFinite(clientX) || !Number.isFinite(clientY)) return null;
  if (!(rect.width > 0) || !(rect.height > 0) || !(frameWidth > 0) || !(frameHeight > 0)) return null;
  const fx = ((clientX - rect.left) / rect.width) * frameWidth;
  const fy = ((clientY - rect.top) / rect.height) * frameHeight;
  return {
    x: Math.max(0, Math.min(frameWidth, Math.round(fx))),
    y: Math.max(0, Math.min(frameHeight, Math.round(fy))),
  };
}

type KeyLike = { key: string; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean };

/**
 * The input event for a key press, or null when it should not be relayed (a bare modifier, a shortcut such as
 * Ctrl+C, a function key). Space is sent as the named key so the server can tell it from a typed character.
 */
export function keyToInput(e: KeyLike): BrowserTakeoverInput | null {
  if (e.ctrlKey || e.metaKey) return null;
  if (e.key === " ") return { type: "key", key: "Space" };
  if (NAMED_KEYS.has(e.key)) return { type: "key", key: e.key };
  if (Array.from(e.key).length === 1 && !/\p{C}/u.test(e.key)) return { type: "key", key: e.key };
  return null;
}

/** A pasted text bounded for relay: line ends normalised, control characters dropped, capped at `max` characters. */
export function pasteToInput(text: string, max: number = DEFAULT_MAX_TYPE_CHARS): BrowserTakeoverInput | null {
  const cleaned = Array.from((text || "").replace(/\r\n?/g, "\n"))
    .filter((ch) => ch === "\n" || ch === "\t" || !/\p{C}/u.test(ch))
    .slice(0, Math.max(1, max))
    .join("");
  return cleaned ? { type: "type", text: cleaned } : null;
}

/** A wheel gesture as a scroll input, line/page deltas converted to pixels and clamped to `cap`. */
export function wheelToInput(
  e: { deltaX: number; deltaY: number; deltaMode: number },
  point: { x: number; y: number },
  cap = 2000,
): BrowserTakeoverInput | null {
  const unit = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 800 : 1;
  const clamp = (n: number) => Math.max(-cap, Math.min(cap, Math.round(n * unit)));
  const dx = clamp(e.deltaX);
  const dy = clamp(e.deltaY);
  return dx === 0 && dy === 0 ? null : { type: "scroll", dx, dy, x: point.x, y: point.y };
}

/** "4:32" - mm:ss, never negative. */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/** When to renew the short-lived token: half its lifetime, but never tighter than every 5 seconds. */
export function tokenRefreshDelayMs(ttlSeconds: number): number {
  return Math.max(5_000, Math.floor((Number.isFinite(ttlSeconds) ? ttlSeconds : 60) * 500));
}

/** True while the person's attention is wanted: the agent asked (or they paused) and no hand-back happened yet. */
export function isTakeoverOpen(view: BrowserTakeoverView | null | undefined): view is BrowserTakeoverView {
  return !!view && (view.state === "PAUSED_FOR_USER" || view.state === "USER_CONTROL" || view.state === "RESUMING");
}

/**
 * The seconds to show on the countdown: whichever limit fires first. While the person types the idle clock keeps
 * resetting, so the hard maximum is what eventually shows; once they stop, the idle clock does.
 */
export function remainingSeconds(idleRemainingSeconds: number, maxRemainingSeconds: number): number {
  return Math.max(0, Math.min(idleRemainingSeconds, maxRemainingSeconds));
}

/** True when a session in these steps has driven the browser (so "take over" is meaningful). */
export function sessionUsedBrowser(steps: ({ type?: string; tool?: string } | undefined)[] | undefined): boolean {
  return (steps || []).some((s) => typeof s?.tool === "string" && s.tool.startsWith("browser_"));
}
