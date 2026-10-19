// Shared helpers for the local code assistant (LocalCodePanel and its hooks).

// ---------------------------------------------------------------------------
// Model gate
// ---------------------------------------------------------------------------

/** Why asking is or isn't available. A boolean collapsed "the deployment has
 * on-device AI switched off" into "you have no model downloaded", which sent
 * people to /edge — a console that is itself dark when the flag is off. */
export type ModelGate = { kind: "checking" } | { kind: "ready" } | { kind: "edge-off" } | { kind: "no-model" };

/** Message for a thrown value without leaking an object into the DOM as
 * "[object Object]" — every catch on this page funnels through it. */
export function errorText(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  if (typeof err === "string" && err) return err;
  return fallback;
}

/** An aborted operation is a person clicking Cancel/Stop, not a failure —
 * these never render an error. */
export function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}
