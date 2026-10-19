// Manifest sourcing for ../downloadManager.ts (split out of it): one conditional,
// ETag-cached GET per source, and the localStorage copy it falls back to.
import type { ModelManifest } from "../capabilities";
import { MANIFEST_BODY_KEY, MANIFEST_ETAG_KEY, MANIFEST_FETCH_TIMEOUT_MS, MANIFEST_SOURCE_KEY } from "./constants";
import { coerceManifest, safeLocalGet, safeLocalSet, type ManifestFetchResult } from "./transferUtils";

export async function fetchManifestFrom(url: string, force: boolean): Promise<ManifestFetchResult> {
  try {
    const headers: Record<string, string> = {};
    const etag = safeLocalGet(MANIFEST_ETAG_KEY);
    const etagSource = safeLocalGet(MANIFEST_SOURCE_KEY);
    if (etag && !force && etagSource === url) headers["If-None-Match"] = etag;
    const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timer = controller ? setTimeout(() => controller.abort(), MANIFEST_FETCH_TIMEOUT_MS) : null;
    let res: Response;
    try {
      res = await fetch(url, { headers, signal: controller?.signal });
    } finally {
      if (timer) clearTimeout(timer);
    }
    if (res.status === 304) return { kind: "not-modified" };
    if (res.status === 404) return { kind: "missing" };
    if (!res.ok) return { kind: "error" };
    const body = coerceManifest(await res.json());
    if (!body) return { kind: "error" };
    safeLocalSet(MANIFEST_ETAG_KEY, res.headers.get("etag") || "");
    // The NORMALIZED body is what round-trips through localStorage, so a
    // cached copy re-read later carries string versions too.
    safeLocalSet(MANIFEST_BODY_KEY, JSON.stringify(body));
    safeLocalSet(MANIFEST_SOURCE_KEY, url);
    return { kind: "manifest", manifest: body };
  } catch {
    return { kind: "error" };
  }
}

export function readCachedManifest(): ModelManifest | null {
  try {
    const raw = safeLocalGet(MANIFEST_BODY_KEY);
    if (!raw) return null;
    return coerceManifest(JSON.parse(raw));
  } catch {
    return null;
  }
}
