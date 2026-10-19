// ---------------------------------------------------------------------------
// On-device activity signal — a lightweight module-level observable the chip
// and pages subscribe to for the "running on-device right now" indicator.
// Fired by the generation paths (tierRouter around tier 0, tier1.ts around
// its own turns) — this module only carries the state, it never probes.
// ---------------------------------------------------------------------------

export interface EdgeActivity {
  /** True while an on-device generation is in flight. */
  active: boolean;
  /** Which local tier is generating (null when idle). */
  tier: 0 | 1 | null;
  /** The model id doing the work — a manifest/WebLLM id for tier 1,
   * tier0.ts's TIER0_ASSET_ID ("chrome-built-in") for tier 0; null when idle. */
  modelId: string | null;
  /** Epoch ms the current generation started; null when idle. */
  startedAt: number | null;
}

const IDLE_ACTIVITY: EdgeActivity = { active: false, tier: null, modelId: null, startedAt: null };

let edgeActivity: EdgeActivity = IDLE_ACTIVITY;
/** Overlapping turns (two conversations generating at once) are refcounted so
 * the indicator only clears when the LAST one finishes. */
let edgeActivityDepth = 0;
const edgeActivityListeners = new Set<(activity: EdgeActivity) => void>();

function emitEdgeActivity(): void {
  for (const listener of edgeActivityListeners) {
    try {
      listener(edgeActivity);
    } catch {
      // A broken listener never breaks the generation path.
    }
  }
}

/** Current snapshot — safe anywhere (idle on the server). */
export function getEdgeActivity(): EdgeActivity {
  return edgeActivity;
}

/** Subscribe to activity changes. Returns the unsubscribe function. The
 * listener is NOT called with the current value on subscribe — read
 * getEdgeActivity() for the initial state (same pattern as downloadManager). */
export function subscribeEdgeActivity(listener: (activity: EdgeActivity) => void): () => void {
  edgeActivityListeners.add(listener);
  return () => {
    edgeActivityListeners.delete(listener);
  };
}

/** Generation-path hook (tierRouter / tier1.ts): an on-device turn started.
 * Every begin MUST be paired with one endEdgeActivity() (use try/finally). */
export function beginEdgeActivity(tier: 0 | 1, modelId: string): void {
  edgeActivityDepth += 1;
  // Last-begin wins for the label — a concurrent second turn is rare and the
  // indicator is informational, not an accounting surface.
  edgeActivity = { active: true, tier, modelId, startedAt: Date.now() };
  emitEdgeActivity();
}

/** Generation-path hook: an on-device turn finished (success OR failure). */
export function endEdgeActivity(): void {
  edgeActivityDepth = Math.max(0, edgeActivityDepth - 1);
  if (edgeActivityDepth === 0 && edgeActivity.active) {
    edgeActivity = IDLE_ACTIVITY;
    emitEdgeActivity();
  }
}
