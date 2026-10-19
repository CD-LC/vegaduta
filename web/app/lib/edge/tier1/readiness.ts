// Which tier-1 model would run right now, and is it already on the device?
// Split out of ../tier1.ts.

import {
  edgeEnabled,
  modelFits,
  selectBestModel,
  type EdgeCapabilities,
  type ManifestModel,
  type TurnComplexity,
  type TurnComplexitySignal,
} from "../capabilities";
import { fetchTier1Manifest } from "./manifest";
import { hasAnyTier1Marker, isTier1ModelReady } from "./weightStore";

/**
 * The model a tier-1 turn would run RIGHT NOW, or null when tier 1 can't
 * serve. Selection follows the plan's binding best-fit rule (user override
 * always wins, else largest fitting manifest model — selectBestModel), then
 * requires the weights to be ALREADY cached:
 *   - the selected model cached → run it;
 *   - selected model not cached but another fitting tier-1 model is → run
 *     the largest cached one (F-1: promote to the better model only once its
 *     download completes — never block on it, never demote what works).
 * Never rejects.
 */
export async function getTier1RunModel(
  caps: EdgeCapabilities,
  // EDGE-C (Wave 2): the per-turn complexity signal the router threads in so
  // selectBestModel's fast-vs-capable pick actually sees the turn it's
  // picking for (estimateTurnComplexity's "dead code twice over" finding
  // E-5). Optional — session-start/preview callers (evaluateEdgeSession,
  // previewRouting) have no turn in hand and omit it, which reads as
  // "simple", the pre-existing default.
  turnComplexity?: TurnComplexity | TurnComplexitySignal
): Promise<ManifestModel | null> {
  try {
    if (typeof window === "undefined" || !edgeEnabled()) return null;
    if (!caps.webgpu.available) return null;
    // Cheap short-circuit: no model has ever finished downloading on this
    // device → nothing can be ready, no manifest fetch needed on the hot path.
    if (!hasAnyTier1Marker()) return null;
    const manifest = await fetchTier1Manifest();
    const tier1Models = manifest.models.filter((m) => m.tier === 1);
    if (tier1Models.length === 0) return null;
    const selection = selectBestModel({ ...manifest, models: tier1Models }, caps, undefined, turnComplexity);
    if (selection.kind === "hosted") return null;
    if (selection.model && (await isTier1ModelReady(selection.model.id))) return selection.model;
    // A user-pinned model that isn't downloaded stays pinned — do not run a
    // different model than the one the user chose (user selection always
    // wins, including its absence).
    if (selection.source === "user") return null;
    const fitting = tier1Models
      .filter((m) => modelFits(m, caps))
      .sort((a, b) => b.sizeBytes - a.sizeBytes);
    for (const candidate of fitting) {
      // Sequential on purpose: readiness is memoized per model per session,
      // and marker-less models answer false without touching the Cache API.
      if (await isTier1ModelReady(candidate.id)) return candidate;
    }
    return null;
  } catch {
    return null;
  }
}

/** The tier router's readiness probe (feeds resolveTier's tier1Ready input).
 * True ONLY when a fitting model's weights are already on this device. */
export async function tier1Ready(caps: EdgeCapabilities): Promise<boolean> {
  return (await getTier1RunModel(caps)) !== null;
}
