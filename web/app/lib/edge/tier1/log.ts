// Console-only structured logging for the tier-1 runtime. Split out of ../tier1.ts.

// ---------------------------------------------------------------------------
// Structured observability (warm-up problem fix, 2026-08-07): prewarmTier1Engine
// previously swallowed every outcome silently — there was no signal for edge
// startup time, warm-up duration, or model-init status. This is a lightweight,
// console-only structured log (never a network call) deliberately kept
// SEPARATE from reportEdgeTurn/POST /api/edge/turns: that endpoint's
// EdgeTurnTelemetryController records an RL turn episode (with a TASK_SUCCESS/
// COST reward) for every authenticated report it receives — a warm-up isn't a
// completed conversational turn, so routing it through that endpoint would
// fabricate reward-bearing episodes with no real user interaction behind
// them. Actual per-turn routing decisions/reasons/fallback events already go
// through reportEdgeTurn from tierRouter.ts unchanged.
export function logTier1Event(event: Record<string, unknown>): void {
  try {
    if (typeof console === "undefined") return;
     
    console.debug("[edge:tier1]", { ...event, ts: Date.now() });
  } catch {
    // Logging must never affect routing/warm-up behavior.
  }
}
