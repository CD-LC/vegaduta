// Typed errors for the tier-1 runtime (mirror of Tier0Error - same shape,
// same router handling). Split out of ../tier1.ts; no runtime imports.

export type Tier1ErrorCode =
  | "unsupported" // not a browser / edge disabled / no WebGPU
  | "unavailable" // no ready (downloaded) model on this device
  | "engine-failed" // engine creation / model load failed (incl. GPU OOM)
  | "prompt-failed" // the turn itself failed mid-generation
  | "generation-timeout" // the generation call stalled (no new chunk for too long)
  | "turn-ceiling-exceeded" // the whole turn (across all tool rounds) ran past the wall-clock ceiling
  | "device-lost" // WebGPU device lost mid-load/mid-generation (often OOM)
  | "context-window-exceeded" // D-9: the irreducible prompt can't fit the model's context window (input-shaped, not model-health)
  | "aborted"; // caller's AbortSignal fired

export class Tier1Error extends Error {
  code: Tier1ErrorCode;
  /** Original error message, kept for logs — never for the UI. */
  detail: string | null;

  constructor(code: Tier1ErrorCode, message: string, detail?: unknown) {
    super(message);
    this.name = "Tier1Error";
    this.code = code;
    this.detail = detail instanceof Error ? detail.message : detail != null ? String(detail) : null;
  }
}

export function toTier1Error(err: unknown, fallbackCode: Tier1ErrorCode): Tier1Error {
  if (err instanceof Tier1Error) return err;
  const isAbort = err instanceof Error && err.name === "AbortError";
  if (isAbort) return new Tier1Error("aborted", "On-device reply was stopped.", err);
  // @mlc-ai/web-llm exports a real, dedicated DeviceLostError (lib/error.ts,
  // set via `this.name = "DeviceLostError"` in its constructor — verified
  // against the installed package) for WebGPU context/device loss; its own
  // message explains this "often occurs due to running out of memory (OOM)".
  // That's the actual, documented GPU-failure/memory-exhaustion signal this
  // library surfaces — checked by name here (not `instanceof`) so this stays
  // synchronous and doesn't require importing @mlc-ai/web-llm outside the
  // dynamic-import boundary (see the bundle rule in this file's header).
  const isDeviceLost = err instanceof Error && err.name === "DeviceLostError";
  if (isDeviceLost) {
    return new Tier1Error(
      "device-lost",
      "The on-device model lost access to the GPU (often caused by running low on memory).",
      err
    );
  }
  // D-9 belt-and-braces: the engine's own overflow signal. @mlc-ai/web-llm
  // exports a dedicated ContextWindowSizeExceededError (lib/error.ts, sets
  // `this.name = "ContextWindowSizeExceededError"` — verified against the
  // installed 0.2.84 package) thrown when the assembled prompt's REAL token
  // count exceeds the loaded window — the case our chars/3.5 estimator
  // under-counted. Checked by name (not instanceof) for the same
  // dynamic-import-boundary reason as DeviceLostError above. The router maps
  // this code to the "context-window-exceeded" fallthrough reason and never
  // trips the circuit breaker for it (input-shaped, not model-health).
  const isContextOverflow = err instanceof Error && err.name === "ContextWindowSizeExceededError";
  if (isContextOverflow) {
    return new Tier1Error(
      "context-window-exceeded",
      "This request is too large for the on-device model's context window.",
      err
    );
  }
  const message =
    fallbackCode === "engine-failed"
      ? "Could not start the on-device model."
      : "The on-device model could not finish this reply.";
  return new Tier1Error(fallbackCode, message, err);
}
