import { getPublicEnv } from "../env";
// Edge capability detection + tier
// resolution + best-fit model selection.
//
// One detection pass per browser session (cached in sessionStorage), per the
// plan in docs/EDGE-CAPABILITIES-IMPLEMENTATION-PLAN-2026-08-04.md §E-0. This
// module is deliberately dependency-free and DOM-light (feature probes only)
// so its core logic can move into a worker later without change.
//
// Everything downstream of this file is gated on edgeEnabled() — with
// NEXT_PUBLIC_EDGE_AGENTS unset, no edge UI renders and no probe runs.
//
// The pieces live under ./caps/ (prefs, detection, modelSelection, labels,
// manifest, activity) and are re-exported here: import from this module, not
// from caps/*, so tests that mock "edge/capabilities" keep covering every caller.

/** Build-time UI gate for the whole edge-agents surface (Wave 1: default off).
 * NEXT_PUBLIC_* bakes at build time — flipping this on staging means a domain
 * -overlay rebuild, same as every other NEXT_PUBLIC_ value. */
export function edgeEnabled(): boolean {
  return getPublicEnv("NEXT_PUBLIC_EDGE_AGENTS") === "1";
}

export { edgeFallbackLabel, edgeFallbackShortLabel, voiceAudioMimeType } from "./caps/labels";
export { normalizeManifest, type ManifestModel, type ModelManifest } from "./caps/manifest";
export {
  beginEdgeActivity,
  endEdgeActivity,
  getEdgeActivity,
  subscribeEdgeActivity,
  type EdgeActivity,
} from "./caps/activity";
export {
  DECLARED_MEMORY_KEY,
  WEBLLM_BASELINE_CONTEXT_WINDOW,
  estimateTurnComplexity,
  getDeclaredMemoryGB,
  isToolTunedModelId,
  modelFitState,
  modelFits,
  resolveTier,
  selectBestModel,
  setDeclaredMemoryGB,
  type ModelFitState,
  type ModelSelection,
  type TierReadiness,
  type TurnComplexity,
  type TurnComplexitySignal,
} from "./caps/modelSelection";
export {
  detectCapabilities,
  normalizeAvailability,
  type EdgeCapabilities,
  type EdgeTier,
  type PromptApiAvailability,
} from "./caps/detection";
export {
  EDGE_BANNER_DISMISSED_KEY,
  EDGE_FALLBACK_ASK_TIMEOUT_MS,
  EDGE_FALLBACK_POLICY_KEY,
  EDGE_NETWORK_TOOLS_KEY,
  EDGE_OPT_IN_KEY,
  EDGE_PREFS_EVENT,
  EDGE_REDACTION_KEY,
  EDGE_ROUTING_PREF_KEY,
  EDGE_WEBMCP_KEY,
  MODEL_OVERRIDE_KEY,
  getEdgeBannerDismissed,
  getEdgeFallbackPolicy,
  getEdgeLearningOptIn,
  getEdgeOptIn,
  getEdgeRedactionMode,
  getEdgeRoutingPreference,
  getNetworkToolsEnabled,
  getStoredModelOverride,
  getWebMcpEnabled,
  resolveAutomaticEdgeFallback,
  setEdgeBannerDismissed,
  setEdgeFallbackPolicy,
  setEdgeLearningOptIn,
  setEdgeOptIn,
  setEdgeRedactionMode,
  setEdgeRoutingPreference,
  setModelOverride,
  setNetworkToolsEnabled,
  setWebMcpEnabled,
  type EdgeFallbackPolicy,
  type EdgeRedactionMode,
  type EdgeRoutingPreference,
  type ModelOverride,
} from "./caps/prefs";
