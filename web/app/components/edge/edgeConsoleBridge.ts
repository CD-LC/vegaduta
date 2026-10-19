// TASK A2 — the Edge console's bridge onto lib/edge.
//
// RECONCILED (verify pass, 2026-08-05): this file was written as a shim while
// agent A1's lib/edge contracts landed in parallel. Those contracts exist now,
// so everything here delegates to the real implementations instead of
// duplicating them:
//   1. PREFERENCE — lib/edge/capabilities' EdgeRoutingPreference
//      ("edge-first" | "hosted-only" | "ask", stored at edge.routingPref,
//      DEFAULT edge-first) is the source of truth. The console's tri-state
//      EdgeMode ("edge-first" | "hosted-only" | "manual") is a pure VIEW over
//      that preference + the legacy edge.optIn / edge.modelOverride pair —
//      "manual" is edge-first routing with a pinned model.
//   2. IN-USE — lib/edge/capabilities' EdgeActivity observable (fired by
//      tierRouter around tier 0 and tier1.ts around its own turns). The old
//      "edge:inuse-changed" CustomEvent contract is gone — nothing dispatched
//      it; the module observable is real.
//   3. DELETES — tier1.ts's deleteTier1Model (WebLLM's own eviction incl.
//      config/wasm scopes + engine unload) and vision.ts's deleteVisionModel
//      (worker teardown + transformers-cache eviction). The raw Cache API
//      copies that used to live here are deleted.
// What remains local: the EdgeMode view mapping, pin-clearing on delete, and
// last-used bookkeeping (fed by the activity observable).

import {
  getEdgeActivity,
  getEdgeOptIn,
  getEdgeRoutingPreference,
  getStoredModelOverride,
  setEdgeOptIn,
  setEdgeRoutingPreference,
  setModelOverride,
  subscribeEdgeActivity,
  type EdgeActivity,
} from "../../lib/edge/capabilities";
import { deleteTier1Model as engineDeleteTier1Model } from "../../lib/edge/tier1";
import {
  deleteVisionModel as engineDeleteVisionModel,
  isVisionModelCached,
  VISION_ASSET_ID,
  VISION_MODEL_SIZE_BYTES,
} from "../../lib/edge/vision";
import {
  deleteSttModel as engineDeleteSttModel,
  isSttModelCached,
  STT_ASSET_ID,
  STT_MODEL_SIZE_BYTES,
} from "../../lib/edge/audio/whisper";
import {
  deleteTtsModel as engineDeleteTtsModel,
  isTtsModelCached,
  TTS_ASSET_ID,
  TTS_MODEL_SIZE_BYTES,
} from "../../lib/edge/audio/kokoro";

// Re-exported so the console keeps one import site for the vision/STT/TTS
// asset constants.
export { VISION_ASSET_ID, VISION_MODEL_SIZE_BYTES, STT_ASSET_ID, STT_MODEL_SIZE_BYTES, TTS_ASSET_ID, TTS_MODEL_SIZE_BYTES };

// ---------------------------------------------------------------------------
// Guarded localStorage (same shape as every lib/edge sibling)
// ---------------------------------------------------------------------------

function safeLocalGet(key: string): string | null {
  try {
    if (typeof localStorage === "undefined") return null;
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeLocalSet(key: string, value: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(key, value);
  } catch {
    // Best-effort.
  }
}

// ---------------------------------------------------------------------------
// 1. Preference view — edge-first | hosted-only | manual
// ---------------------------------------------------------------------------

export type EdgeMode = "edge-first" | "hosted-only" | "manual";

/** The console's tri-state, DERIVED from the real preference module — no
 * separate stored key, so this can never drift from what routeTurn does:
 *   - hosted-only preference, a "hosted" model pin, or an explicit opt-out
 *     ("ask" without the person having enabled on-device) → "hosted-only"
 *     (that is honestly where replies run right now);
 *   - otherwise a pinned model id → "manual", auto → "edge-first". */
export function getEdgeMode(): EdgeMode {
  if (getEdgeRoutingPreference() === "hosted-only") return "hosted-only";
  const override = getStoredModelOverride();
  if (override === "hosted") return "hosted-only";
  if (!getEdgeOptIn()) return "hosted-only";
  return override === "auto" ? "edge-first" : "manual";
}

/** Persist the mode through the real preference module AND the legacy pair
 * the router also reads — user override always wins and survives reloads.
 * The capabilities.ts setters dispatch EDGE_PREFS_EVENT, keeping the chip and
 * every other edge surface in sync. "Manual" keeps edge-first ROUTING
 * (a pinned model must actually run) — the pin is what makes it manual. */
export function setEdgeMode(mode: EdgeMode): void {
  if (mode === "hosted-only") {
    setEdgeRoutingPreference("hosted-only");
    setModelOverride("hosted");
    setEdgeOptIn(false);
    return;
  }
  setEdgeRoutingPreference("edge-first");
  if (mode === "edge-first") {
    setModelOverride("auto");
  } else if (getStoredModelOverride() === "hosted") {
    // manual: keep the user's pinned model if they have one; a "hosted"
    // leftover contradicts the mode and resets to auto until they pick.
    setModelOverride("auto");
  }
  setEdgeOptIn(true);
}

/** "[Use now]" on a marketplace/installed card: pin the model and make sure
 * on-device attempts are actually on. Manual mode by definition. */
export function pinModel(modelId: string): void {
  setEdgeRoutingPreference("edge-first");
  setModelOverride(modelId);
  setEdgeOptIn(true);
}

// ---------------------------------------------------------------------------
// 2. In-use view — { active, modelId? } over the EdgeActivity observable
// ---------------------------------------------------------------------------

export interface EdgeInUseState {
  active: boolean;
  modelId?: string;
}

function toInUseState(activity: EdgeActivity): EdgeInUseState {
  return activity.active
    ? { active: true, modelId: activity.modelId ?? undefined }
    : { active: false };
}

export function getEdgeInUse(): EdgeInUseState {
  return toInUseState(getEdgeActivity());
}

export function subscribeEdgeInUse(listener: (state: EdgeInUseState) => void): () => void {
  return subscribeEdgeActivity((activity) => {
    if (activity.active && activity.modelId) recordLastUsed(activity.modelId);
    listener(toInUseState(activity));
  });
}

// ---------------------------------------------------------------------------
// Last-used bookkeeping (fed by the activity observable above)
// ---------------------------------------------------------------------------

const LAST_USED_KEY = "edge.assets.lastUsed.v1";

function readLastUsedMap(): Record<string, number> {
  try {
    const raw = safeLocalGet(LAST_USED_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, number>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function recordLastUsed(assetId: string): void {
  const map = readLastUsedMap();
  map[assetId] = Date.now();
  safeLocalSet(LAST_USED_KEY, JSON.stringify(map));
}

/** Epoch ms of the last on-device turn served by this asset, or null (never
 * seen while a console/chip surface was mounted and subscribed). */
export function getLastUsed(assetId: string): number | null {
  const value = readLastUsedMap()[assetId];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

// ---------------------------------------------------------------------------
// 3. Engine-owned asset deletion — thin wrappers over the real owners
// ---------------------------------------------------------------------------

/** Delete a WebLLM (tier 1) model from this device via tier1.ts's own
 * eviction (weights/config/wasm scopes, marker, chip row, live-engine
 * unload), then clear a pin on the deleted model back to auto so routing
 * doesn't sit on a model that no longer exists. Never rejects. */
export async function deleteTier1Model(modelId: string): Promise<void> {
  await engineDeleteTier1Model(modelId);
  if (getStoredModelOverride() === modelId) setModelOverride("auto");
}

export function isVisionModelInstalled(): boolean {
  return isVisionModelCached();
}

/** Delete the on-device vision model — vision.ts owns the teardown (live
 * worker, transformers-cache entries, marker, chip row). Never rejects. */
export async function deleteVisionModel(): Promise<void> {
  await engineDeleteVisionModel();
}

export function isSttModelInstalled(): boolean {
  return isSttModelCached();
}

/** Delete the on-device speech-to-text model — whisper.ts owns the teardown
 * (live worker, transformers-cache entries, marker, chip row). Never rejects. */
export async function deleteSttModel(): Promise<void> {
  await engineDeleteSttModel();
}

export function isTtsModelInstalled(): boolean {
  return isTtsModelCached();
}

/** Delete the on-device text-to-speech model — kokoro.ts owns the teardown
 * (live worker, transformers-cache entries, marker, chip row). Never rejects. */
export async function deleteTtsModel(): Promise<void> {
  await engineDeleteTtsModel();
}

// ---------------------------------------------------------------------------
// Shared formatting
// ---------------------------------------------------------------------------

