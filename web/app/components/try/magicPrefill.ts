"use client";

// magicPrefill — the handoff seam between the landing hero's build console
// (HeroOutcome.tsx) and Magic Studio's prompt box.
//
// The hero's "run this spec" link scrolls to #live-studio carrying the exact
// sentence the visitor just watched compile. Two delivery paths, because the
// receiver may not be listening yet:
//   - sessionStorage: survives the gap when MagicStudio's lazy chunk mounts
//     AFTER the click (it is a nextDynamic import on the landing).
//   - a window event: reaches an already-mounted MagicStudio instantly, so a
//     second click still lands even though storage was already consumed.
// MagicStudio also reads a plain `?prompt=` query param on mount, so a shared
// or bookmarked link behaves the same way.
//
// Storage over localStorage on purpose: a prefill is a one-shot handoff, not
// a preference — it must not follow the visitor to the next session.

export const MAGIC_PREFILL_KEY = "vegaduta:magic-prompt";
export const MAGIC_PREFILL_EVENT = "vegaduta:magic-prefill";

export function queueMagicPrompt(text: string): void {
  const cleaned = text.trim();
  if (!cleaned) return;
  try {
    window.sessionStorage.setItem(MAGIC_PREFILL_KEY, cleaned);
  } catch {
    /* storage refused (private mode) — the event below still delivers it */
  }
  window.dispatchEvent(new CustomEvent(MAGIC_PREFILL_EVENT));
}

/** Read-and-clear: returns the queued prompt exactly once. */
export function takeMagicPrompt(): string | null {
  try {
    const v = window.sessionStorage.getItem(MAGIC_PREFILL_KEY);
    if (v) window.sessionStorage.removeItem(MAGIC_PREFILL_KEY);
    return v;
  } catch {
    return null;
  }
}

/** `?prompt=` on the URL — shareable deep link into the studio. Read once. */
export function promptFromUrl(): string | null {
  try {
    const v = new URLSearchParams(window.location.search).get("prompt");
    return v?.trim() || null;
  } catch {
    return null;
  }
}
