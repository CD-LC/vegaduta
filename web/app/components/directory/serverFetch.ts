// Server-only fetch helper for the PUBLIC /directory page - the same contract
// as components/plugins/serverFetch.ts: runs inside the RSC render so crawlers
// get real HTML, hits only the public unauthenticated GET
// /api/directory/agents (permitAll in SecurityConfig - the published entries
// are signed snapshots, not live tenant data), never throws and never hangs.

import { publicApiBase } from "../../lib/env";
export type DirectoryEntry = {
  agentId: string;
  displayName: string | null;
  description: string | null;
  capabilities: string[];
  homepageUrl: string | null;
  status: string;
  publishedAt: string | null;
  updatedAt: string | null;
  card?: Record<string, any> | null;
  manifest?: Record<string, any> | null;
};

export type DirectoryPage = {
  entries: DirectoryEntry[];
  /** Rows in THIS page. */
  count: number;
  /** All matches, offset the page started at, and whether more follow; absent on a core that cannot page yet. */
  total?: number;
  offset?: number;
  hasMore?: boolean;
};

/** Cards per page: fits the 2-column grid and matches the first page the server renders for crawlers. */
export const DIRECTORY_PAGE_SIZE = 24;

const API_BASE = publicApiBase();
// Longer than the plugins/templates helpers: this endpoint walks every tenant's
// published snapshots, and a cold first hit that times out ships "directory
// unavailable" in the crawler-visible HTML even though the API is healthy.
const SERVER_FETCH_TIMEOUT_MS = 6000;

export async function fetchDirectoryServer(q?: string, limit = DIRECTORY_PAGE_SIZE): Promise<DirectoryPage | null> {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), SERVER_FETCH_TIMEOUT_MS);
    let res: Response;
    try {
      res = await fetch(`${API_BASE}/api/directory/agents?limit=${limit}${q ? `&q=${encodeURIComponent(q)}` : ""}`, {
        signal: controller.signal,
        cache: "no-store",
        headers: { Accept: "application/json" },
      });
    } finally {
      clearTimeout(timer);
    }
    if (!res.ok) return null;
    return (await res.json()) as DirectoryPage;
  } catch {
    // Backend down / unreachable / timed out - degrade to null; the client
    // component re-attempts and renders the honest empty state.
    return null;
  }
}
