import { publicApiBase } from "../../lib/env";
// Server-only fetch helper for the PUBLIC /plugins marketplace page - the
// same contract as components/templates/serverFetch.ts (see that file for the
// full reasoning): runs inside the React Server Component render so the page
// ships real HTML for crawlers, hits only the public unauthenticated GET, and
//   1. Never throws - any failure returns null and PluginsGallery takes over
//      with its own loading/error UI.
//   2. Never hangs - short abort timeout so SSR doesn't block on an
//      unreachable backend.
//
// API base is read straight from env rather than importing lib/api.ts, whose
// module graph pulls in browser-only deps (keycloak-js via auth.ts).

// Wire types for GET /api/plugins/catalog - mirrors PluginCatalogController's
// records (core/app/.../common/plugins). Every nullable field below is
// genuinely null until the first artifact upload / store publish: `artifact:
// null` is the honest "no build published yet" state the UI must render as
// "coming soon", never a dead download link.
export type PluginArtifact = {
  fileName: string;
  sizeBytes: number;
  sha256: string;
};

export type PluginEntry = {
  id: string;
  displayName: string;
  description: string;
  latestVersion: string | null;
  releasedAt: string | null; // ISO-8601 instant
  releaseNotes: string | null;
  artifact: PluginArtifact | null;
  externalStoreUrl: string | null;
};

export type PluginCatalog = {
  version: string;
  plugins: PluginEntry[];
};

const API_BASE = publicApiBase();
const SERVER_FETCH_TIMEOUT_MS = 2500;

export async function fetchPluginCatalogServer(): Promise<PluginCatalog | null> {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), SERVER_FETCH_TIMEOUT_MS);
    let res: Response;
    try {
      res = await fetch(`${API_BASE}/api/plugins/catalog`, {
        signal: controller.signal,
        // The endpoint itself serves a 5-minute public max-age; don't let
        // Next additionally cache a stale/empty response from a down backend.
        cache: "no-store",
        headers: { Accept: "application/json" },
      });
    } finally {
      clearTimeout(timer);
    }
    if (!res.ok) return null;
    return (await res.json()) as PluginCatalog;
  } catch {
    // Backend down / unreachable / timed out - degrade to null; the client
    // component re-attempts and renders the proper error state.
    return null;
  }
}

// The download URL is derived, not stored (PluginArtifact's javadoc) - one
// place builds it so the card and any future caller can't drift. Segments
// already passed the server's strict [A-Za-z0-9._-]+ allowlist, so encoding
// is a no-op belt-and-braces.
export function pluginDownloadPath(pluginId: string, version: string, fileName: string): string {
  return `/api/plugins/artifacts/${encodeURIComponent(pluginId)}/${encodeURIComponent(version)}/${encodeURIComponent(fileName)}`;
}
