// Tier-1 model manifest: GET /api/edge/manifest with a built-in fallback.
// Split out of ../tier1.ts.

import { API_BASE } from "../../api";
import { normalizeManifest, type ModelManifest } from "../capabilities";
import { loadBuiltinFallbackManifest } from "./registry";
import { sanitizeTier1Manifest } from "./select";

let manifestCache: ModelManifest | null = null;
let manifestInFlight: Promise<ModelManifest> | null = null;

/**
 * The edge model manifest: one GET /api/edge/manifest per session, falling
 * back to the built-in fallback manifest (tier1/registry.ts) on 404/network failure. Never rejects.
 * (The downloadManager's NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL static-route
 * manifest is the F-1 endgame; this API-served manifest is the Wave-2 source
 * per the plan's backend-first contract.)
 */
export function fetchTier1Manifest(): Promise<ModelManifest> {
  if (manifestCache) return Promise.resolve(manifestCache);
  if (manifestInFlight !== null) return manifestInFlight;
  manifestInFlight = (async () => {
    let manifest: ModelManifest | null = null;
    try {
      if (typeof fetch !== "undefined" && typeof window !== "undefined") {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 8_000);
        try {
          // Unauthenticated on purpose — same permitAll posture as
          // /api/edge/config (the anonymous /try surface needs it too).
          const res = await fetch(`${API_BASE}/api/edge/manifest`, { signal: controller.signal });
          if (res.ok) {
            // Tolerant normalization (capabilities.ts) — schema drift from an
            // older/newer server degrades to the built-in fallback instead of
            // silently emptying the tier-1 model list.
            const normalized = normalizeManifest(await res.json());
            if (normalized && normalized.models.length > 0) manifest = normalized;
          }
          // 404 / any other status → keep the built-in fallback.
        } finally {
          clearTimeout(timer);
        }
      }
    } catch {
      // Network failure → built-in fallback.
    }
    // Whichever source won: never offer a tier-1 id the installed engine
    // can't serve (see sanitizeTier1Manifest — the download would only fail
    // at CreateMLCEngine, after the user already opted in).
    manifestCache = sanitizeTier1Manifest(manifest ?? (await loadBuiltinFallbackManifest()));
    return manifestCache;
  })().finally(() => {
    manifestInFlight = null;
  });
  return manifestInFlight;
}

/** The cached manifest's version string ("" when nothing is cached yet). Used
 *  by the install-stamp bookkeeping so it never has to fetch. */
export function getCachedTier1ManifestVersion(): string {
  return manifestCache ? String(manifestCache.version ?? "") : "";
}
