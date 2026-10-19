// Constants and cache-key helpers of the edge download manager. Split out of
// ../downloadManager.ts. Reads public env at module load, like the original.

import { getPublicEnv, publicApiBase } from "../../env";

/** Same convention as app/lib/api.ts (read-only to this wave): baked at build
 * time via the domain overlay; the localhost fallback only exists for local
 * dev and never ships (RULE 0 — deploys always carry NEXT_PUBLIC_API_BASE_URL). */
export const API_BASE = publicApiBase();
/** F-1 primary manifest source, feature-detected: a 404 means core doesn't
 * serve it (yet) and the env fallback below takes over. */
export const MANIFEST_API_URL = `${API_BASE}/api/edge/manifest`;
/** F-1 fallback: versioned manifest at the static model route. Unset and with
 * the API route 404ing → no manifest, no downloads (the Wave-1 behavior). */
export const MANIFEST_FALLBACK_URL = getPublicEnv("NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL") || "";
export const MANIFEST_ETAG_KEY = "edge.manifest.etag";
export const MANIFEST_BODY_KEY = "edge.manifest.body";
/** Which source URL produced the stored ETag/body — a conditional GET is only
 * valid against the same source. */
export const MANIFEST_SOURCE_KEY = "edge.manifest.source";
/** F-1: the manifest version this device has adopted (set when installs
 * complete, or silently when nothing is installed). A fetched version that
 * differs while assets ARE installed = "update available". */
export const MANIFEST_KNOWN_VERSION_KEY = "edge.manifest.knownVersion";
/** Registry of fully-verified installs: {[assetId]: {version, cacheKey, ...}}. */
export const INSTALLED_REGISTRY_KEY = "edge.assets.installed.v1";

/** Cache API bucket for all edge model shards (shared with future workers). */
export const ASSET_CACHE_NAME = "edge-models-v1";
/** Synthetic same-origin URL namespace for cached shards — never routed. */
export const SHARD_URL_PREFIX = "/__edge-model-cache__/";
/** Our Range-based shard size when the manifest doesn't dictate one. Each
 * shard is cached on arrival, so interruption resumes at the last shard, not
 * from zero (E-0b rule 4). */
export const DEFAULT_SHARD_BYTES = 64 * 1024 * 1024;
/** Progress events are throttled to this interval (state changes always emit). */
export const PROGRESS_EMIT_INTERVAL_MS = 200;
/** 2026-09-19. The manifest GET had NO timeout: a core that accepts the
 * connection and never answers (cold start after scale-to-zero, a hung
 * revision) left fetchManifest() — and therefore every startTransfer awaiting
 * it — pending forever. Same 8s tier1.ts fetchTier1Manifest already uses. */
export const MANIFEST_FETCH_TIMEOUT_MS = 8_000;
/** Byte-level stall: reader.read() yields a chunk every few ms on a live
 * connection, so a full minute of nothing is a dead socket, not a slow one. */
export const TRANSFER_STALL_MS = 60_000;
/** Hard ceiling for one asset, progress or not — the last-resort guard against
 * a trickle that never ends. Generous on purpose (GB-class assets, slow links). */
export const TRANSFER_OVERALL_MS = 4 * 60 * 60 * 1000;
/** Tries per startTransfer call, including the first. Each retry resumes at
 * the last cached shard (Range), so it costs only what is missing. */
export const TRANSFER_MAX_ATTEMPTS = 4;
/** Simultaneous manager-driven transfers. Each holds up to one shard
 * (DEFAULT_SHARD_BYTES) in memory; see ConcurrencyGate's comment. */
export const MAX_CONCURRENT_TRANSFERS = 2;
/** F-1 double-buffer: every version gets its own shard namespace. "::" is
 * URL-encoded inside the synthetic shard URL, so decode-then-split is safe. */
export const CACHE_KEY_SEP = "::";

export function makeCacheKey(assetId: string, version: string): string {
  return `${assetId}${CACHE_KEY_SEP}${version}`;
}

export function shardUrl(cacheKey: string, shardIndex: number): string {
  return `${SHARD_URL_PREFIX}${encodeURIComponent(cacheKey)}/${shardIndex}`;
}

/** Logical asset id for a cached shard request pathname, or null. */
export function logicalIdOfPathname(pathname: string): string | null {
  if (!pathname.startsWith(SHARD_URL_PREFIX)) return null;
  const rest = pathname.slice(SHARD_URL_PREFIX.length);
  const slash = rest.indexOf("/");
  const segment = slash >= 0 ? rest.slice(0, slash) : rest;
  try {
    const cacheKey = decodeURIComponent(segment);
    const sep = cacheKey.indexOf(CACHE_KEY_SEP);
    return sep >= 0 ? cacheKey.slice(0, sep) : cacheKey;
  } catch {
    return null;
  }
}
