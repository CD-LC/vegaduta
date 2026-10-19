"use client";


import { useCallback, useEffect,  useState } from "react";
import {
  detectCapabilities,
  getStoredModelOverride,
  EDGE_PREFS_EVENT,
  type EdgeCapabilities,
  type ModelManifest,
  type ModelOverride,
  type PromptApiAvailability,
} from "../../lib/edge/capabilities";
import { downloadManager, type AssetStatus } from "../../lib/edge/downloadManager";
import {
  fetchTier1Manifest,
} from "../../lib/edge/tier1";
import {  tier0Availability } from "../../lib/edge/tier0";
import { listMotionEngines, type MotionEngineOption } from "../../lib/edge/video/rife";
import {
  getEdgeInUse,
  getEdgeMode,
  subscribeEdgeInUse,
  type EdgeInUseState,
  type EdgeMode,
} from "./edgeConsoleBridge";

/** Device capabilities, model manifest, download assets, prefs, in-use state and storage for the Edge console. */
export function useEdgeConsoleData(enabled: boolean) {
  const [caps, setCaps] = useState<EdgeCapabilities | null>(null);
  const [textManifest, setTextManifest] = useState<ModelManifest | null>(null);
  const [manifestLoaded, setManifestLoaded] = useState(false);
  const [assets, setAssets] = useState<AssetStatus[]>([]);
  const [mode, setMode] = useState<EdgeMode>("hosted-only");
  const [override, setOverride] = useState<ModelOverride>("auto");
  const [inUse, setInUse] = useState<EdgeInUseState>({ active: false });
  const [tier0Status, setTier0Status] = useState<PromptApiAvailability | "checking">("checking");
  const [motionEngines, setMotionEngines] = useState<MotionEngineOption[]>([]);
  const [motionBytesById, setMotionBytesById] = useState<Record<string, number>>({});
  const [storage, setStorage] = useState<{ usageBytes: number; quotaBytes: number } | null>(null);
  // Bumped after installs/deletes so localStorage-derived installed state
  // (isTier1ModelCached / isVisionModelInstalled) re-reads.
  const [installedTick, setInstalledTick] = useState(0);

  const refreshStorage = useCallback(() => {
    downloadManager
      .estimateStorage()
      .then((est) => setStorage(est))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return undefined;
    let cancelled = false;
    detectCapabilities()
      .then((c) => {
        if (!cancelled) setCaps(c);
      })
      .catch(() => {});
    // fetchTier1Manifest always resolves (built-in fallback) — the text-model
    // marketplace is never empty. The downloadManager manifest fetch runs too
    // so its ETag/update channel stays warm and asset urls resolve.
    fetchTier1Manifest()
      .then((m) => {
        if (!cancelled) {
          setTextManifest(m);
          setManifestLoaded(true);
        }
      })
      .catch(() => {
        if (!cancelled) setManifestLoaded(true);
      });
    void downloadManager.fetchManifest().catch(() => {});
    tier0Availability()
      .then((a) => {
        if (!cancelled) setTier0Status(a);
      })
      .catch(() => {
        if (!cancelled) setTier0Status("unavailable");
      });
    listMotionEngines()
      .then((options) => {
        if (!cancelled) setMotionEngines(options);
      })
      .catch(() => {});
    setAssets(downloadManager.listAssets());
    refreshStorage();

    const unsubscribeAssets = downloadManager.subscribe((event) => {
      if (event.type !== "asset") return;
      setAssets(downloadManager.listAssets());
      if (event.asset.state === "ready" || event.asset.state === "cancelled") {
        setInstalledTick((n) => n + 1);
        refreshStorage();
      }
    });
    const syncPrefs = () => {
      setMode(getEdgeMode());
      setOverride(getStoredModelOverride());
    };
    syncPrefs();
    window.addEventListener(EDGE_PREFS_EVENT, syncPrefs);
    setInUse(getEdgeInUse());
    const unsubscribeInUse = subscribeEdgeInUse(setInUse);
    return () => {
      cancelled = true;
      unsubscribeAssets();
      unsubscribeInUse();
      window.removeEventListener(EDGE_PREFS_EVENT, syncPrefs);
    };
  }, [enabled, refreshStorage]);

  // Cached-byte tracking for the motion-engine candidates, kept separate from
  // the main effect above: sizeBytes isn't reliable in their manifest entries
  // (0 until real weights are published), so installed size comes from the
  // download manager's actual Cache API measurement instead of a manifest
  // number, same reasoning as everything else that depends on motionEngines.
  useEffect(() => {
    if (!enabled || motionEngines.length === 0) return undefined;
    let cancelled = false;
    const ids = new Set(motionEngines.map((o) => o.id));
    const refreshMotionBytes = () => {
      Promise.all(motionEngines.map((o) => downloadManager.cachedBytes(o.id).then((b) => [o.id, b] as const)))
        .then((pairs) => {
          if (!cancelled) setMotionBytesById(Object.fromEntries(pairs));
        })
        .catch(() => {});
    };
    refreshMotionBytes();
    const unsubscribe = downloadManager.subscribe((event) => {
      if (event.type !== "asset" || !ids.has(event.asset.id)) return;
      if (event.asset.state === "ready" || event.asset.state === "cancelled") refreshMotionBytes();
    });
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [enabled, motionEngines]);
  return {
    caps, textManifest, manifestLoaded, assets, mode, override, inUse, tier0Status, setTier0Status,
    motionEngines, motionBytesById, storage, installedTick, setInstalledTick, refreshStorage,
  };
}
