// Shared "engine is warming up" notifier for optional services running
// min_replicas=0 on Azure Container Apps (code-intel, app-builder — set 0
// 2026-09-26 as a deliberate cost cut). When the browser hits a scaled-to-0
// service, Container Apps holds the request open until a replica boots —
// measured >25s for code-intel, ~9s for app-builder — which used to read as
// a dead page. This tracker lets each service's lib.ts report "a call has
// been in flight >SLOW_MS" so the page can show a warming-up notice instead
// of silence.
//
// Counted, not boolean: two overlapping calls must not have the first one's
// completion clear the notice while the second is still cold-starting.

export type WarmingListener = (warming: boolean) => void;

const SLOW_MS = 5000; // well past any warm response, well before >25s cold start

export function createWarmingTracker() {
  const listeners = new Set<WarmingListener>();
  let inFlight = 0;

  const emit = () => listeners.forEach((fn) => fn(inFlight > 0));

  function begin() {
    let slow = false;
    const timer = setTimeout(() => {
      slow = true;
      inFlight += 1;
      emit();
    }, SLOW_MS);
    // Callers MUST invoke this in a finally — returns the settle function.
    return () => {
      clearTimeout(timer);
      if (slow) {
        inFlight -= 1;
        emit();
      }
    };
  }

  return {
    begin,
    subscribe(fn: WarmingListener): () => void {
      listeners.add(fn);
      return () => {
        listeners.delete(fn);
      };
    },
  };
}
