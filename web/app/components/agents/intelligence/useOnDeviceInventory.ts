"use client";

// What this browser/device can run on-device, for IntelligenceSourceControl: the Chrome built-in
// model (tier 0) status, the tier-1 manifest, downloaded assets, the stored model override and
// learning opt-in, the audio/vision utility probes, which model is answering right now, and the
// last on-device memory-write outcome. Also owns the explicit-gesture setup/download handlers for
// those rows. Split out of IntelligenceSourceControl; every probe still runs only when `enabled`.

import { useEffect, useState } from "react";
import {
  detectCapabilities,
  getEdgeLearningOptIn,
  subscribeEdgeActivity,
  EDGE_PREFS_EVENT,
  type EdgeCapabilities,
  type ManifestModel,
} from "../../../lib/edge/capabilities";
import { getStoredModelOverride, type ModelOverride } from "../../../lib/edge/routingState";
import { createTier0Session, tier0Availability } from "../../../lib/edge/tier0";
import { fetchTier1Manifest, startTier1Download } from "../../../lib/edge/tier1";
import { downloadManager, type AssetStatus } from "../../../lib/edge/downloadManager";
import {
  getEdgeMemoryWriteStatus,
  subscribeEdgeMemoryWriteStatus,
  type EdgeMemoryWriteStatus,
} from "../../../lib/edge/toolBridge";
// Phase 2 (2026-08-06): on-device audio/vision utilities surfaced alongside
// the text-model list — same "install status + Set up CTA" pattern as
// the tier0 row, reusing each module's own public contract untouched (never
// downloads anything on its own; ensure*Model() only runs on this panel's own
// explicit button click, same E-0b rule 2 gesture the tier0/tier1 rows honor).
import { edgeSttAvailable } from "../../../lib/edge/audio/whisper";
import { edgeTtsAvailable } from "../../../lib/edge/audio/kokoro";
import { edgeVisionAvailable } from "../../../lib/edge/vision";
import { type UTILITY_DEFS, type Tier0Status, type UtilityKind } from "./shared";

export function useOnDeviceInventory(enabled: boolean) {
  const [tier0Status, setTier0Status] = useState<Tier0Status>("checking");
  const [caps, setCaps] = useState<EdgeCapabilities | null>(null);
  const [tier1Models, setTier1Models] = useState<ManifestModel[]>([]);
  const [assets, setAssets] = useState<AssetStatus[]>([]);
  const [override, setOverride] = useState<ModelOverride>("auto");
  const [tier0Busy, setTier0Busy] = useState(false);
  const [learningOptIn, setLearningOptInState] = useState(false);
  // Phase 2: on-device audio/vision utility rows. "Available" is an async
  // per-device capability probe (WebGPU/WASM support), checked once on mount
  // like tier0Status; "cached" is a cheap sync localStorage read, computed
  // inline at render time rather than mirrored into state (ensure*Model sets
  // its marker synchronously before its promise resolves, so clearing busy
  // is enough to make the next render see the fresh value).
  const [utilityAvailable, setUtilityAvailable] = useState<Record<UtilityKind, boolean | null>>({
    stt: null,
    tts: null,
    vision: null,
  });
  const [utilityBusy, setUtilityBusy] = useState<Record<UtilityKind, boolean>>({
    stt: false,
    tts: false,
    vision: false,
  });
  // "Highlight which component is processing" (direct ask): the model id
  // CURRENTLY answering a turn, or null when idle - drives a live ring
  // around whichever row (tier0 or a tier1 model) is actually in use right
  // now, sourced from the same subscribeEdgeActivity signal
  // AgentPlayground's processing-phase indicator uses, so this panel and the
  // chat transcript never disagree about what's running.
  const [liveModelId, setLiveModelId] = useState<string | null>(null);
  // Bug fix (2026-08-06 diagnosis): remember_fact writes were "deliberately
  // silent" on both success AND failure, which meant a person couldn't tell
  // "it's fine" from "it's broken" once the learning opt-in was on. This
  // mirrors the confirmed-failure snapshot toolBridge.ts now tracks — lights
  // up only on a real ok:false (or a swallowed `{"error": ...}` result),
  // never on every successful write.
  const [memoryWriteStatus, setMemoryWriteStatus] = useState<EdgeMemoryWriteStatus | null>(null);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return undefined;
    setMemoryWriteStatus(getEdgeMemoryWriteStatus());
    return subscribeEdgeMemoryWriteStatus(setMemoryWriteStatus);
  }, [enabled]);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return undefined;
    const sync = () => {
      setOverride(getStoredModelOverride());
      setLearningOptInState(getEdgeLearningOptIn());
    };
    sync();
    window.addEventListener(EDGE_PREFS_EVENT, sync);
    let cancelled = false;
    tier0Availability()
      .then((a) => {
        if (!cancelled) setTier0Status(a);
      })
      .catch(() => {
        if (!cancelled) setTier0Status("unavailable");
      });
    detectCapabilities()
      .then((c) => {
        if (!cancelled) setCaps(c);
      })
      .catch(() => {});
    // fetchTier1Manifest never rejects (built-in fallback), so the model
    // list can't silently come up empty just because the server route is
    // missing — the old root of the dead "unavailable" state.
    fetchTier1Manifest()
      .then((m) => {
        if (!cancelled) setTier1Models(m.models.filter((model) => model.tier === 1));
      })
      .catch(() => {});
    Promise.all([edgeSttAvailable(), edgeTtsAvailable(), edgeVisionAvailable()])
      .then(([stt, tts, vision]) => {
        if (!cancelled) setUtilityAvailable({ stt, tts, vision });
      })
      .catch(() => {});
    setAssets(downloadManager.listAssets());
    const unsubscribe = downloadManager.subscribe((event) => {
      if (event.type === "asset") setAssets(downloadManager.listAssets());
    });
    const unsubscribeActivity = subscribeEdgeActivity((activity) => {
      setLiveModelId(activity.active ? activity.modelId : null);
    });
    return () => {
      cancelled = true;
      unsubscribe();
      unsubscribeActivity();
      window.removeEventListener(EDGE_PREFS_EVENT, sync);
    };
  }, [enabled]);

  // The mount-time tier0Availability() check above is a single snapshot — if
  // Chrome reports "downloading" (or the initial "checking" never settles),
  // nothing was re-polling it, so the row stayed on "Setting up…" forever
  // even after Chrome's own download finished or failed. Re-check on an
  // interval while in either non-terminal state; stop once it resolves.
  useEffect(() => {
    if (!enabled || typeof window === "undefined") return undefined;
    if (tier0Status !== "downloading" && tier0Status !== "checking") return undefined;
    let cancelled = false;
    const id = window.setInterval(() => {
      tier0Availability()
        .then((a) => {
          if (!cancelled) setTier0Status(a);
        })
        .catch(() => {
          if (!cancelled) setTier0Status("unavailable");
        });
    }, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [enabled, tier0Status]);

  // A3: explicit user gesture → Chrome's own built-in-model download
  // (create() triggers/joins it; progress flows into the shared chip via
  // tier0.ts's monitor). The session is created only to drive setup and
  // dropped immediately.
  async function handleTier0Setup() {
    if (tier0Busy) return;
    setTier0Busy(true);
    try {
      const session = await createTier0Session();
      session.destroy();
    } catch {
      // Typed failure already reported through downloadManager — the row's
      // status text below reflects it; never an error wall here.
    } finally {
      try {
        setTier0Status(await tier0Availability());
      } catch {
        // Keep the last known status.
      }
      setTier0Busy(false);
    }
  }

  // A3: explicit user gesture → tier-1 model download through the SHARED
  // pipeline (startTier1Download → downloadManager), never auto-triggered.
  // Progress renders inline below AND in the header chip. Never rejects.
  async function handleTier1Download(model: ManifestModel) {
    const deviceCaps = caps ?? (await detectCapabilities());
    if (!caps) setCaps(deviceCaps);
    await startTier1Download(deviceCaps, model.id);
  }

  // Phase 2: explicit user gesture → the utility's own ensure*Model(), which
  // enqueues into the SAME shared downloadManager pipeline as tier0/tier1
  // (progress renders inline below AND in the header chip). Never called
  // automatically — only from this button's onClick (E-0b rule 2).
  async function handleUtilitySetup(def: (typeof UTILITY_DEFS)[number]) {
    if (utilityBusy[def.kind]) return;
    setUtilityBusy((prev) => ({ ...prev, [def.kind]: true }));
    try {
      await def.ensure();
    } catch {
      // Typed failure already reported through downloadManager — the row's
      // status text below reflects it; never an error wall here.
    } finally {
      setUtilityBusy((prev) => ({ ...prev, [def.kind]: false }));
    }
  }

  return {
    tier0Status,
    caps,
    tier1Models,
    assets,
    override,
    tier0Busy,
    learningOptIn,
    setLearningOptInState,
    utilityAvailable,
    utilityBusy,
    liveModelId,
    memoryWriteStatus,
    handleTier0Setup,
    handleTier1Download,
    handleUtilitySetup,
  };
}
