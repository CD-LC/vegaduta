// Cache API shard storage and shard/asset verification for the edge download
// manager. Split out of ../downloadManager.ts: these are the stateless parts
// (no queue, no registry, no listeners), so they take everything they need as
// arguments. Every function is best-effort and never rejects: a missing Cache
// API (SSR, unsupported worker) or a quota failure answers false / null / 0.
// Available in workers too; touches no DOM.

import type { ManifestAssetEntry } from "./types";
import { ASSET_CACHE_NAME, SHARD_URL_PREFIX, logicalIdOfPathname, shardUrl } from "./constants";
import { VerificationError, sha256Hex } from "./transferUtils";

/** navigator.storage.estimate(), or null where unsupported. Never rejects. */
export async function estimateStorage(): Promise<{ usageBytes: number; quotaBytes: number } | null> {
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.estimate) return null;
    const est = await navigator.storage.estimate();
    return { usageBytes: est.usage || 0, quotaBytes: est.quota || 0 };
  } catch {
    return null;
  }
}

/**
 * Pre-download quota check (E-0b rule 5: refuse politely if quota is short).
 * Unknown estimate -> true: the environments that hide the estimate would
 * otherwise never download, and a real shortage still fails safely at write
 * time inside the transfer engine.
 */
export async function hasStorageHeadroomFor(bytes: number, safetyFactor = 1.2): Promise<boolean> {
  const est = await estimateStorage();
  if (!est || !est.quotaBytes) return true;
  return est.quotaBytes - est.usageBytes >= bytes * safetyFactor;
}

/** Ask the browser to protect the cached models from eviction. Best-effort. */
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    if (typeof navigator === "undefined" || !navigator.storage?.persist) return false;
    return await navigator.storage.persist();
  } catch {
    return false;
  }
}

/** Store one shard under a versioned cache key. Shard-granular so an
 * interrupted download resumes at the last shard, not from zero (E-0b rule 4).
 * Returns false on any failure (quota, unsupported) rather than throwing. */
export async function putShard(cacheKey: string, shardIndex: number, data: ArrayBuffer | Blob): Promise<boolean> {
  try {
    if (typeof caches === "undefined") return false;
    const cache = await caches.open(ASSET_CACHE_NAME);
    await cache.put(
      shardUrl(cacheKey, shardIndex),
      new Response(data, { headers: { "content-type": "application/octet-stream" } })
    );
    return true;
  } catch {
    return false;
  }
}

/** Read one shard under a RESOLVED cache key (the manager resolves a logical
 * asset id through its install registry first). */
export async function getShardByKey(cacheKey: string, shardIndex: number): Promise<ArrayBuffer | null> {
  try {
    if (typeof caches === "undefined") return null;
    const cache = await caches.open(ASSET_CACHE_NAME);
    const hit = await cache.match(shardUrl(cacheKey, shardIndex));
    return hit ? await hit.arrayBuffer() : null;
  } catch {
    return null;
  }
}

/** How many contiguous shards (0..n-1) are cached under a cache key: the
 * engine's resume point. */
export async function countContiguousShards(cacheKey: string): Promise<number> {
  try {
    if (typeof caches === "undefined") return 0;
    const cache = await caches.open(ASSET_CACHE_NAME);
    let index = 0;
    // Bounded probe: match() per index until the first gap.
    for (;;) {
      const hit = await cache.match(shardUrl(cacheKey, index));
      if (!hit) return index;
      index += 1;
    }
  } catch {
    return 0;
  }
}

async function sizeOf(cache: Cache, req: Request): Promise<number> {
  const hit = await cache.match(req);
  if (!hit) return 0;
  const len = Number(hit.headers.get("content-length"));
  return Number.isFinite(len) && len > 0 ? len : (await hit.arrayBuffer()).byteLength;
}

/** Total cached bytes across EVERY version of one logical asset (the "Manage
 * on-device models" list's size column). */
export async function cachedBytes(assetId: string): Promise<number> {
  try {
    if (typeof caches === "undefined") return 0;
    const cache = await caches.open(ASSET_CACHE_NAME);
    const keys = await cache.keys();
    let total = 0;
    for (const req of keys) {
      if (logicalIdOfPathname(new URL(req.url).pathname) !== assetId) continue;
      total += await sizeOf(cache, req);
    }
    return total;
  } catch {
    return 0;
  }
}

export async function cachedBytesForKey(cacheKey: string): Promise<number> {
  try {
    if (typeof caches === "undefined") return 0;
    const cache = await caches.open(ASSET_CACHE_NAME);
    const keys = await cache.keys();
    const prefix = `${SHARD_URL_PREFIX}${encodeURIComponent(cacheKey)}/`;
    let total = 0;
    for (const req of keys) {
      if (!new URL(req.url).pathname.startsWith(prefix)) continue;
      total += await sizeOf(cache, req);
    }
    return total;
  } catch {
    return 0;
  }
}

/** Evict every shard under ONE cache key (one version of one asset). */
export async function deleteShards(cacheKey: string): Promise<void> {
  try {
    if (typeof caches === "undefined") return;
    const cache = await caches.open(ASSET_CACHE_NAME);
    const keys = await cache.keys();
    const prefix = `${SHARD_URL_PREFIX}${encodeURIComponent(cacheKey)}/`;
    for (const req of keys) {
      if (new URL(req.url).pathname.startsWith(prefix)) await cache.delete(req);
    }
  } catch {
    // Best-effort.
  }
}

/** Evict the shards of every version of a logical asset. Best-effort. */
export async function deleteAssetShards(assetId: string): Promise<void> {
  try {
    if (typeof caches === "undefined") return;
    const cache = await caches.open(ASSET_CACHE_NAME);
    const keys = await cache.keys();
    for (const req of keys) {
      if (logicalIdOfPathname(new URL(req.url).pathname) === assetId) await cache.delete(req);
    }
  } catch {
    // Best-effort.
  }
}

/** Per-shard hash check (when published) before the shard is cached: the
 * scalable verification path for GB-class assets. */
export async function verifyAndPutShard(
  cacheKey: string,
  shardIndex: number,
  shard: Uint8Array,
  entry: ManifestAssetEntry | null
): Promise<void> {
  const expected = entry?.shardSha256?.[shardIndex];
  if (expected) {
    const actual = await sha256Hex(shard);
    // null = no SubtleCrypto in this context -> fail open (see sha256Hex).
    if (actual !== null && actual.toLowerCase() !== expected.toLowerCase()) {
      throw new VerificationError();
    }
  }
  const stored = await putShard(cacheKey, shardIndex, shard.buffer as ArrayBuffer);
  if (!stored) throw new Error("shard write failed (storage)");
}

/** Whole-asset SHA-256: assembles the shards in memory, so manifests should
 * reserve `sha256` for small assets and publish `shardSha256` for large ones.
 * An assembly/allocation failure counts as failed verification. */
export async function verifyWholeAsset(cacheKey: string, shardCount: number, expected: string): Promise<boolean> {
  try {
    const parts: ArrayBuffer[] = [];
    let total = 0;
    for (let i = 0; i < shardCount; i += 1) {
      const shard = await getShardByKey(cacheKey, i);
      if (!shard) return false;
      parts.push(shard);
      total += shard.byteLength;
    }
    const joined = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) {
      joined.set(new Uint8Array(part), offset);
      offset += part.byteLength;
    }
    const actual = await sha256Hex(joined);
    if (actual === null) return true; // No SubtleCrypto -> fail open.
    return actual.toLowerCase() === expected.toLowerCase();
  } catch {
    return false;
  }
}
