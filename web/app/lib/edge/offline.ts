// Reliability blueprint Phase 2 (docs/RELIABILITY-BLUEPRINT-2026-08-06-PART2.md
// "continue at Phase 2") — offline detection + recovery, the one Part 3
// requirement with literally zero prior implementation anywhere in
// web/app/lib/edge (confirmed by grep before writing this file).
//
// Contract: OFFLINE is a parallel state reachable from any point in a tier's
// turn, per the blueprint's state-machine diagram. This module only answers
// "are we online right now" and "tell me when that changes" — it does NOT
// itself cancel in-flight turns; callers (tierRouter.ts) consult
// isEdgeOffline() before starting a new attempt, and consumers that want to
// react to a mid-turn drop subscribe directly. Auto-recovery is inherent: the
// next routeTurn() call after an 'online' event just works again, no manual
// retry required, exactly as the blueprint specifies.
//
// House style: subscribe/listener pattern matching downloadManager.ts's
// AssetStatus stream and requestLifecycle.ts's controller.subscribe().

export type EdgeOfflineListener = (online: boolean) => void;

const listeners = new Set<EdgeOfflineListener>();
let wired = false;

/** Best-effort, safe on the server and in browsers without the API (answers
 * true — never block a turn on an environment that can't tell us). */
export function isEdgeOffline(): boolean {
  try {
    if (typeof navigator === "undefined" || typeof navigator.onLine !== "boolean") return false;
    return navigator.onLine === false;
  } catch {
    return false;
  }
}

function notify(): void {
  const online = !isEdgeOffline();
  for (const listener of listeners) {
    try {
      listener(online);
    } catch {
      // A broken listener must never affect connectivity tracking.
    }
  }
}

function ensureWired(): void {
  if (wired || typeof window === "undefined") return;
  wired = true;
  try {
    window.addEventListener("online", notify);
    window.addEventListener("offline", notify);
  } catch {
    // Best-effort — isEdgeOffline() still works as a poll-on-demand fallback.
  }
}

/** Subscribe to online/offline transitions. Fires once immediately with the
 * current status, then on every change. Returns an unsubscribe function. */
export function subscribeEdgeOnlineStatus(listener: EdgeOfflineListener): () => void {
  ensureWired();
  listeners.add(listener);
  try {
    listener(!isEdgeOffline());
  } catch {
    // Best-effort.
  }
  return () => {
    listeners.delete(listener);
  };
}
