/**
 * setInterval that stays quiet while the tab is hidden.
 *
 * Background tabs used to keep every session-long poller (usage quota,
 * notifications, health, model-change, view-as) firing forever, which is pure
 * load on core that scales with open tabs, not with people actually looking.
 * Ticks are skipped while `document.hidden`; on return to the tab the callback
 * runs once immediately so the UI is never stale on focus.
 *
 * Returns a cleanup function. Safe to call during SSR (returns a no-op).
 */
export function visibleInterval(fn: () => void, ms: number): () => void {
  if (typeof window === "undefined" || typeof document === "undefined") return () => {};
  const id = window.setInterval(() => {
    if (!document.hidden) fn();
  }, ms);
  const onVisible = (): void => {
    if (!document.hidden) fn();
  };
  document.addEventListener("visibilitychange", onVisible);
  return () => {
    window.clearInterval(id);
    document.removeEventListener("visibilitychange", onVisible);
  };
}
