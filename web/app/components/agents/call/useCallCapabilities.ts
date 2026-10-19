"use client";

// What this browser/agent can do for a call: the client-side automatic
// fallback policy, whether on-device replies are on, whether the local vision
// model is cached, and whether the agent's model accepts image input. Together
// they decide whether "Call + camera" is offered (Fix #9, P1: a text-only
// agent must not open a live server-side vision session).

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { EDGE_PREFS_EVENT, getEdgeFallbackPolicy, getEdgeOptIn, type EdgeFallbackPolicy } from "../../../lib/edge/capabilities";
import { isVisionModelCached } from "../../../lib/edge/vision";

/** Pure: the canSee rule shared with AgentPlayground's CameraPanel gate. */
export function computeCanSee(supportsImageInput: boolean, edgeOptInActive: boolean, edgeVisionReady: boolean): boolean {
  return supportsImageInput || (edgeOptInActive && edgeVisionReady);
}

export function useCallCapabilities(agent: { providerName?: string | null; model?: string | null } | null | undefined) {
  // Client-side (per-browser) preference for what trySendTurnLocally's
  // AUTOMATIC fallback points do. Defaults to "auto" until synced from
  // localStorage, and re-synced on EDGE_PREFS_EVENT.
  const [fallbackPolicy, setFallbackPolicyState] = useState<EdgeFallbackPolicy>("auto");
  // Whether the fallback-policy selector is actually reachable code right now
  // (every automatic-fallback point is skipped when on-device replies are off).
  const [edgeOptInActive, setEdgeOptInActive] = useState(false);
  const [edgeVisionReady, setEdgeVisionReady] = useState(false);
  const [supportsImageInput, setSupportsImageInput] = useState(false);

  useEffect(() => {
    if (typeof window === "undefined") return undefined;
    const sync = () => {
      setFallbackPolicyState(getEdgeFallbackPolicy());
      setEdgeOptInActive(getEdgeOptIn());
      setEdgeVisionReady(isVisionModelCached());
    };
    sync();
    window.addEventListener(EDGE_PREFS_EVENT, sync);
    return () => window.removeEventListener(EDGE_PREFS_EVENT, sync);
  }, []);

  const providerName = agent?.providerName;
  const model = agent?.model;
  useEffect(() => {
    let cancelled = false;
    if (!providerName || !model) {
      setSupportsImageInput(false);
      return undefined;
    }
    api
      .listProviderModels(providerName)
      .then((models) => {
        if (cancelled) return;
        const entry = (models || []).find((m) => m.modelId === model);
        setSupportsImageInput(!!entry?.supportsImageInput);
      })
      .catch(() => {
        if (!cancelled) setSupportsImageInput(false);
      });
    return () => {
      cancelled = true;
    };
  }, [providerName, model]);

  return {
    fallbackPolicy,
    setFallbackPolicyState,
    edgeOptInActive,
    canSee: computeCanSee(supportsImageInput, edgeOptInActive, edgeVisionReady),
  };
}
