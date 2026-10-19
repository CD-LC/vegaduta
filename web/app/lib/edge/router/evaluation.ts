// Session-start evaluation (login / app load) - the edge-first entry point.
// Split out of ../tierRouter.ts, which re-exports evaluateEdgeSession.

import {
  detectCapabilities,
  edgeEnabled,
  getEdgeOptIn,
  getEdgeRoutingPreference,
  getStoredModelOverride,
  resolveTier,
  selectBestModel,
} from "../capabilities";
import { fetchTier1Manifest, getTier1RunModel } from "../tier1";
import { getTier2RunModel, TIER2_ASSET_ID } from "../tier2";
import { TIER0_ACTIVITY_MODEL_ID, type HostedFallthroughReason } from "./shared";

export type EdgeSessionEvaluation =
  /** A local tier can serve plain text turns RIGHT NOW (weights cached /
   * built-in model present) — routeTurn will go on-device first. */
  | { status: "on-device"; tier: 0 | 1 | 2; modelId: string | null }
  /** Device is capable (edge-first, WebGPU) but NO model is downloaded yet.
   * Per E-0b nothing auto-downloads — the UI renders the "ready to enable"
   * nudge (EDGE_BRANDING.readyToEnable*) and startTier1Download runs only on
   * the person's click. suggestedModelId is the auto-selected best fit. */
  | { status: "ready-to-enable"; suggestedModelId: string | null; suggestedModelName: string | null }
  /** Turns go hosted, with the same reason vocabulary as routeTurn. */
  | { status: "hosted"; reason: HostedFallthroughReason };

/**
 * One call on session start (login/app load): detect capabilities and answer
 * what this device's default should be. Pure evaluation — it never downloads,
 * never creates a session, never writes a preference. Never rejects.
 */
export async function evaluateEdgeSession(): Promise<EdgeSessionEvaluation> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") {
      return { status: "hosted", reason: "edge-disabled" };
    }
    if (getStoredModelOverride() === "hosted" || getEdgeRoutingPreference() === "hosted-only") {
      return { status: "hosted", reason: "user-hosted-override" };
    }
    if (!getEdgeOptIn()) {
      return { status: "hosted", reason: "awaiting-user-choice" };
    }
    const caps = await detectCapabilities();
    const t1Model = await getTier1RunModel(caps);
    if (t1Model) {
      return { status: "on-device", tier: 1, modelId: t1Model.id };
    }
    // Wave 2b: getTier2RunModel answers non-null only when the CPU model is
    // already fully cached here (it never downloads, same contract as
    // getTier1RunModel), so a device that has installed it now reports
    // "on-device" at session start instead of "hosted"/"ready-to-enable".
    const t2Model = await getTier2RunModel();
    const startTier = resolveTier(caps, { tier1Ready: false, tier2Ready: t2Model !== null });
    if (startTier === 0) {
      return { status: "on-device", tier: 0, modelId: TIER0_ACTIVITY_MODEL_ID };
    }
    if (startTier === 2 && t2Model) {
      return { status: "on-device", tier: 2, modelId: TIER2_ASSET_ID };
    }
    if (caps.webgpu.available) {
      // Capable, nothing downloaded: surface the enable nudge with the
      // auto-pick the download flow would use (E-0b — never auto-download).
      const manifest = await fetchTier1Manifest();
      const tier1Models = manifest.models.filter((m) => m.tier === 1);
      // EDGE-C call-site wiring: session start has no turn in hand, so the
      // complexity signal is an explicit empty one ("simple") — the enable
      // nudge suggests the lightest fast-tagged model, matching the download
      // flow's own auto-pick (startTier1Download).
      const selection = selectBestModel({ ...manifest, models: tier1Models }, caps, undefined, {
        messageLength: 0,
      });
      const suggested = selection.kind === "model" ? selection.model : null;
      return {
        status: "ready-to-enable",
        suggestedModelId: suggested?.id ?? null,
        suggestedModelName: suggested?.displayName ?? null,
      };
    }
    return { status: "hosted", reason: "no-local-tier" };
  } catch {
    return { status: "hosted", reason: "no-local-tier" };
  }
}
