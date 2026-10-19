// Tier resolution and best-fit model selection (split out of ../capabilities.ts,
// which re-exports it): device fit against the manifest's per-model minimums,
// the declared-memory override, the turn-complexity heuristic and selectBestModel.
import type { ManifestModel, ModelManifest } from "./manifest";
import type { EdgeCapabilities, EdgeTier } from "./detection";
import { getStoredModelOverride, type ModelOverride } from "./prefs";

// ---------------------------------------------------------------------------
// Tier resolution
// ---------------------------------------------------------------------------

/** Async inputs to resolveTier that this dependency-free module can't probe
 * itself. The router computes them (tier1.ts's tier1Ready()) and passes them
 * in — readiness means the weights are ALREADY cached and verified on this
 * device, never "a download could be started" (E-0b rule 6). */
export interface TierReadiness {
  /** True when a fitting tier-1 (WebLLM/WebGPU) manifest model is fully
   * downloaded on this device — see tier1.ts getTier1RunModel(). */
  tier1Ready?: boolean;
  /** WAVE-2b (tier 2, WASM): nothing sets this yet. Same never-on-promise
   * rule when it lands: a tier===2 manifest model cached "ready". */
  tier2Ready?: boolean;
}

/**
 * Which local tier this device can serve a turn from RIGHT NOW.
 *
 * Tier 1 (WebLLM) outranks tier 0 when its weights are READY — the plan's
 * binding best-fit rule ("always run the best model the user's system
 * supports"); a strong-WebGPU device without downloaded weights still
 * resolves tier 0. "downloadable"/"downloading" count as tier 0 because
 * creating a Prompt API session triggers/joins Chrome's own model download,
 * with progress surfaced through the download manager (tier0.ts maps the
 * monitor events). Callers that omit `readiness` get the Wave-1 behavior
 * unchanged (tier 0 or hosted).
 */
export function resolveTier(caps: EdgeCapabilities, readiness?: TierReadiness): EdgeTier {
  if (readiness?.tier1Ready && caps.webgpu.available) return 1;
  const a = caps.promptApi.availability;
  if (a === "available" || a === "downloadable" || a === "downloading") return 0;
  if (readiness?.tier2Ready) return 2;
  return 3;
}

// ---------------------------------------------------------------------------
// Best-fit model selection (manifest scaffolding — models ship in Wave 2)
// ---------------------------------------------------------------------------

export interface ModelSelection {
  /** "user" when a stored override decided; "auto" when device scoring did. */
  source: "user" | "auto";
  kind: "model" | "hosted";
  model: ManifestModel | null;
  /** Whether the device meets the chosen model's requirements. A user is
   * allowed to force a model that doesn't fit (plain warning, no block —
   * the tier router's fall-through contract catches a hard failure). */
  fits: boolean;
}

/** D-9: the context window every MLC prebuilt record in this repo's catalog
 * ships with (verified against the installed @mlc-ai/web-llm@0.2.84 package —
 * only TinyLlama v1.0 is smaller, at 2048). A manifest window at or below
 * this baseline changes nothing about device fit; only an admin RAISING the
 * window grows the KV cache past what the record's published vram/memory
 * floors were measured at. */
export const WEBLLM_BASELINE_CONTEXT_WINDOW = 4096;

/** Chrome's navigator.deviceMemory never reports above this many GB — a
 * borderline 8GB laptop and a 64GB workstation report the identical "8". A
 * reading at or above this ceiling therefore can't be trusted to confirm
 * "plenty of memory" for a requirement that itself sits at/above the
 * ceiling; see modelFits below (finding #6, audit 2026-08-10). */
const DEVICE_MEMORY_API_CEILING_GB = 8;

/** localStorage key for the memory (GB) the person declares for this device. */
export const DECLARED_MEMORY_KEY = "vd.edge.declaredMemoryGB";

/** The RAM the person told us they have, or null. Browsers cannot report it:
 * Chrome caps navigator.deviceMemory at 8 and Safari/Firefox omit it, so a
 * 24 GB MacBook reads as "8" or "unknown". Declaring it is the only reliable
 * source; it is advisory (a wrong value can only cause a GPU OOM the router's
 * fall-through already handles). */
export function getDeclaredMemoryGB(): number | null {
  try {
    if (typeof localStorage === "undefined") return null;
    const raw = localStorage.getItem(DECLARED_MEMORY_KEY);
    const n = raw == null ? NaN : Number(raw);
    return Number.isFinite(n) && n > 0 && n <= 1024 ? n : null;
  } catch {
    return null;
  }
}

export function setDeclaredMemoryGB(gb: number | null): void {
  try {
    if (typeof localStorage === "undefined") return;
    if (gb == null) localStorage.removeItem(DECLARED_MEMORY_KEY);
    else localStorage.setItem(DECLARED_MEMORY_KEY, String(gb));
  } catch {
    // Storage blocked - the declaration just doesn't persist.
  }
}

/** "fits" = confirmed. "no" = confirmed it will not fit (no WebGPU, buffer too
 * small, or a REAL memory reading below the floor). "unverified" = the only
 * obstacle is that the browser cannot tell us the memory (Safari/Firefox, or
 * Chrome's capped reading) - not evidence the device is weak. */
export type ModelFitState = "fits" | "unverified" | "no";

/** Data-driven "does this model fit this device" check against the manifest's
 * per-model minimums.
 *
 * Two deliberately conservative rules around navigator.deviceMemory:
 *   - Unknown (null — Firefox/Safari, or any browser without the API) is
 *     NOT a free pass. It's treated as the smallest/fastest capability tier:
 *     any model with a real memory floor (minDeviceMemoryGB > 0) fails to
 *     fit. Only floor-less (0 GB) models are unaffected.
 *   - A reading at/above DEVICE_MEMORY_API_CEILING_GB is "unverified,
 *     assume borderline," not "definitely plenty of memory" — Chrome caps
 *     its report at that ceiling, so the raw number can't distinguish a
 *     borderline device from a much bigger one. It only confidently clears
 *     requirements strictly below the ceiling; a requirement at/above the
 *     ceiling is treated as not (confirmably) met.
 *
 * D-9 re-gate: a tier-1 model whose admin-set contextWindowSize exceeds the
 * 4096 baseline gets its device-memory floor scaled by the window ratio.
 * Honest about the heuristic: the KV cache grows linearly with the window,
 * but the weights don't, and the weights/KV split isn't published per model —
 * scaling the WHOLE floor by the ratio deliberately over-requires (an 8192
 * window on an 8 GB-floor model demands 16 GB), erring toward "refuse to
 * auto-select" rather than a mid-turn GPU OOM. A user force-pick still works
 * (fits is informational there), and the router's fall-through catches hard
 * failures either way. */
/** Three-state fit check. `declaredGB` defaults to the stored declaration; pass
 * null to ignore it. Same floors and window scaling as before. */
export function modelFitState(
  model: ManifestModel,
  caps: EdgeCapabilities,
  declaredGB: number | null = getDeclaredMemoryGB()
): ModelFitState {
  const windowScale =
    model.tier === 1 && model.contextWindowSize != null && model.contextWindowSize > WEBLLM_BASELINE_CONTEXT_WINDOW
      ? model.contextWindowSize / WEBLLM_BASELINE_CONTEXT_WINDOW
      : 1;
  if (model.tier === 1) {
    if (!caps.webgpu.available) return "no";
    if (model.minMaxBufferSize > 0 && (caps.webgpu.maxBufferSize ?? 0) < model.minMaxBufferSize) {
      return "no";
    }
  }
  if (model.minDeviceMemoryGB > 0) {
    const requiredGB = model.minDeviceMemoryGB * windowScale;
    // A declared value beats the browser's capped/missing reading. A real
    // reading BELOW the ceiling is still trusted over a declaration only when
    // the declaration is absent.
    if (declaredGB != null) return declaredGB >= requiredGB ? "fits" : "no";
    if (caps.deviceMemoryGB == null) {
      // Unknown, not weak: assume the smallest tier for AUTO-selection, but
      // do not tell the person their device is bad.
      return "unverified";
    }
    if (caps.deviceMemoryGB >= DEVICE_MEMORY_API_CEILING_GB && requiredGB >= DEVICE_MEMORY_API_CEILING_GB) {
      // Capped reading on both sides: cannot confirm a ceiling-or-above need.
      return "unverified";
    }
    if (caps.deviceMemoryGB < requiredGB) return "no";
  }
  return "fits";
}

/** Boolean gate used by auto-selection: only a CONFIRMED fit passes, so an
 * unverifiable device keeps the conservative behaviour. UI should use
 * modelFitState so "can't verify" is not shown as "will fail". */
export function modelFits(model: ManifestModel, caps: EdgeCapabilities): boolean {
  return modelFitState(model, caps) === "fits";
}

// ---------------------------------------------------------------------------
// Turn complexity — a cheap, SYNCHRONOUS heuristic (never an async
// classification pass; that would add latency to the hot generation path)
// selectBestModel below uses to decide fast-vs-capable. Deliberately built
// only from signals a caller can already have on hand at call time.
// ---------------------------------------------------------------------------

export type TurnComplexity = "simple" | "complex";

/** Cheap, synchronous inputs a selectBestModel caller may have on hand for
 * the turn it's about to route — every field optional, supply whatever you
 * have. Checked (2026-08-06): no caller in this codebase currently threads a
 * pre-send "smarter answer" request into model selection — AgentPlayground's
 * existing `getSmarterAnswer` affordance (IntelligenceSourceControl.tsx has
 * none) re-asks AFTER the fact through the entirely separate hosted
 * `sendServerTurn` path, bypassing edge selection altogether, not a signal
 * available before a turn is routed. `requestSmarterAnswer` is kept here
 * anyway as the natural home for a future pre-send equivalent of that same
 * intent, should one get built. */
export interface TurnComplexitySignal {
  /** Length of the user's message in characters. A long message usually
   * means a detailed, multi-part ask that benefits from the "capable" tier
   * over a quick "fast" one. */
  messageLength?: number;
  /** True when this turn has one or more tools available/active. Tool-using
   * turns need stronger instruction-following (picking the right tool,
   * formatting arguments correctly) than plain conversational replies. */
  toolsActive?: boolean;
  /** True when the person explicitly asked for a stronger/smarter answer for
   * THIS turn before it's sent. See the interface doc above — nothing in
   * this codebase sets this yet. */
  requestSmarterAnswer?: boolean;
}

/** Past this length a plain-text turn is treated as "complex" even with no
 * other signal — well above a one-line question or short follow-up, short of
 * requiring real paragraph/sentence counting. */
const COMPLEX_MESSAGE_LENGTH = 280;

/** Turns a TurnComplexitySignal into the simple/complex verdict
 * selectBestModel acts on. Pure and synchronous. Undefined input (a caller
 * with nothing to report yet) reads as "simple" — the safe default for an
 * ordinary conversational turn. */
export function estimateTurnComplexity(signal: TurnComplexitySignal | undefined): TurnComplexity {
  if (!signal) return "simple";
  if (signal.requestSmarterAnswer) return "complex";
  if (signal.toolsActive) return "complex";
  if (typeof signal.messageLength === "number" && signal.messageLength > COMPLEX_MESSAGE_LENGTH) {
    return "complex";
  }
  return "simple";
}

function resolveTurnComplexity(input: TurnComplexity | TurnComplexitySignal | undefined): TurnComplexity {
  if (input === "simple" || input === "complex") return input;
  return estimateTurnComplexity(input);
}

/**
 * Best-fit selection, per the plan's binding rule: always run the best model
 * the user's system supports — unless the user picked one themselves.
 *
 * - A stored user override (MODEL_OVERRIDE_KEY) ALWAYS wins: "hosted" pins
 *   hosted, a model id pins that model (even if it doesn't fit — fits:false
 *   is informational, surfaced as a warning, never a block).
 * - Auto mode is latency-tier-aware (2026-08-06, replaces the old strict
 *   "always biggest" rule): among fitting models, an ordinary/"simple" turn
 *   (the default when `turnComplexity` is omitted) picks the SMALLEST model
 *   tagged `speedTier: "fast"`; a "complex" turn (see estimateTurnComplexity
 *   above) escalates to the BIGGEST model tagged `speedTier: "capable"`.
 *   If NO fitting model in the manifest carries a speedTier tag at all
 *   (older/undrifted manifest data, or the tagging server-side work hasn't
 *   landed yet) this degrades to the exact pre-existing behavior — biggest
 *   fitting model wins, untagged — so every caller keeps working unchanged
 *   until real speedTier data shows up. If the manifest has tags but none
 *   match the wanted tier (e.g. no "capable" model when escalating), the
 *   largest/smallest tagged model still fitting is used rather than falling
 *   all the way back to hosted.
 * - None fitting → hosted.
 * - WAVE 2 EXTENSION POINT: fold in the one-time ~2s tokens/sec
 *   micro-benchmark on first ready as an extra scoring input, and re-run
 *   selection when the manifest's ETag changes (promote, never mid-session
 *   demote — F-1).
 */
/** Model families whose published weights are fine-tuned for tool/function
 * calling, matched on the MLC model id. Deliberately a short, explicit list
 * of what the installed engine can actually serve TODAY
 * (@mlc-ai/web-llm@0.2.84's prebuiltAppConfig) rather than a clever
 * heuristic: Hermes 2 Pro / 2 Theta / 3 (the family WebLLM's own native
 * `tools` support is gated to) and Functionary. Purpose-built tool models
 * that MLC has not compiled for WebGPU — xLAM among them — are NOT listed,
 * because an id the engine can't fetch is worse than no preference at all.
 * Revisit whenever the engine is upgraded. */
const TOOL_TUNED_MODEL_ID_PATTERNS = [/^hermes-/i, /^functionary/i];

export function isToolTunedModelId(id: string): boolean {
  return TOOL_TUNED_MODEL_ID_PATTERNS.some((pattern) => pattern.test(id));
}

export function selectBestModel(
  manifest: ModelManifest | null,
  caps: EdgeCapabilities,
  userOverride?: ModelOverride | null,
  turnComplexity?: TurnComplexity | TurnComplexitySignal
): ModelSelection {
  const override = userOverride ?? getStoredModelOverride();
  if (override === "hosted") {
    return { source: "user", kind: "hosted", model: null, fits: true };
  }
  if (override && override !== "auto") {
    const pinned = manifest?.models.find((m) => m.id === override) || null;
    if (pinned) {
      return { source: "user", kind: "model", model: pinned, fits: modelFits(pinned, caps) };
    }
    // Pinned model vanished from the manifest — fall back to auto scoring
    // rather than erroring (the manifest is the source of truth).
  }
  const fitting = (manifest?.models || []).filter((m) => modelFits(m, caps));
  if (fitting.length === 0) {
    return { source: "auto", kind: "hosted", model: null, fits: true };
  }

  const tagged = fitting.filter((m) => m.speedTier === "fast" || m.speedTier === "capable");
  if (tagged.length === 0) {
    // Nothing in the manifest is speed-tagged yet — untouched legacy rule.
    const biggest = [...fitting].sort((a, b) => b.sizeBytes - a.sizeBytes)[0];
    return { source: "auto", kind: "model", model: biggest, fits: true };
  }

  const complexity = resolveTurnComplexity(turnComplexity);
  if (complexity === "complex") {
    const capable = tagged.filter((m) => m.speedTier === "capable").sort((a, b) => b.sizeBytes - a.sizeBytes);
    // Tool turns prefer a tool-TRAINED model over a merely larger one
    // (2026-09-11). Size is a poor proxy for tool-call reliability: on the
    // Berkeley function-calling leaderboard a purpose-trained small model
    // beats a general-purpose model of the same size by a wide margin, and
    // this file's own tool protocol (tier1.ts's hand-rolled envelope) asks
    // the model for exactly the behavior that training teaches. Applies
    // ONLY when the caller told us tools are active for this turn, and only
    // among models already judged fitting and capable — so a device with no
    // tool-tuned model cached keeps the previous biggest-capable pick,
    // unchanged.
    const toolsActive = typeof turnComplexity === "object" && turnComplexity?.toolsActive === true;
    const toolTuned = toolsActive ? capable.find((m) => isToolTunedModelId(m.id)) : undefined;
    const pick = toolTuned ?? capable[0] ?? [...tagged].sort((a, b) => b.sizeBytes - a.sizeBytes)[0];
    return { source: "auto", kind: "model", model: pick, fits: true };
  }
  const fast = tagged.filter((m) => m.speedTier === "fast").sort((a, b) => a.sizeBytes - b.sizeBytes);
  const pick = fast[0] ?? [...tagged].sort((a, b) => a.sizeBytes - b.sizeBytes)[0];
  return { source: "auto", kind: "model", model: pick, fits: true };
}
