// Server-only fetch helpers for the PUBLIC template pages. These run inside
// the React Server Component render so the gallery/detail ship real HTML that
// crawlers see WITHOUT executing JS (the lesson from the academy crawler bug:
// content must not depend on client-side reveal). They hit only the public,
// unauthenticated GET endpoints. Backend is live (verified 2026-07-30); the
// two requirements below are kept regardless, since a network hiccup can
// still take it down transiently.
//   1. Never throw - a rejected fetch here would 500 the whole route. Every
//      path returns null on any failure, and the client component then takes
//      over with its own loading/error UI.
//   2. Never hang - a short abort timeout so SSR doesn't block on an
//      unreachable backend.
//
// NOTE: API base is read straight from env here rather than importing api.ts,
// whose module graph pulls in browser-only deps (keycloak-js via auth.ts).
// Only TYPES are imported (erased at compile time).
import type {
  CommunityTemplateDetail,
  CommunityTemplateKind,
  CommunityTemplatePage,
  CommunityTemplateSummary,
} from "../../lib/api";
import type { GalleryFilters } from "./TemplatesGallery";
import { publicApiBase } from "../../lib/env";

const API_BASE = publicApiBase();
const SERVER_FETCH_TIMEOUT_MS = 2500;

async function safeGet<T>(path: string): Promise<T | null> {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), SERVER_FETCH_TIMEOUT_MS);
    let res: Response;
    try {
      res = await fetch(`${API_BASE}${path}`, {
        signal: controller.signal,
        // Public content, but freshness matters (moderation state changes) -
        // don't let Next cache a stale/empty response from a down backend.
        cache: "no-store",
        headers: { Accept: "application/json" },
      });
    } finally {
      clearTimeout(timer);
    }
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    // Backend down / unreachable / timed out - degrade to null. The client
    // component re-attempts and renders the proper error state.
    return null;
  }
}

// `kind` on the wire is the UPPERCASE Java enum value, but public gallery URLs
// keep it lowercase (?kind=agent) because this is an indexed marketing-relevant
// surface and lowercase query values read better. The two representations are
// converted ONLY here and in TemplatesGallery's buildQuery, so nothing else has
// to think about casing.
export function galleryQuery(f: GalleryFilters, pageSize: number): string {
  const p = new URLSearchParams();
  if (f.kind !== "all") p.set("kind", f.kind); // uppercase - this one goes to the API
  if (f.category !== "all") p.set("category", f.category);
  if (f.tag) p.set("tag", f.tag);
  // f.page is the UI-facing 1-based page; the controller's `page` param is a
  // Spring `Pageable` index (0-based). Bug fixed 2026-08-10: this used to
  // send f.page verbatim once it exceeded 1, which asked the backend for one
  // page further than the user actually requested (UI "page 2" -> backend
  // page 2 = the THIRD page, silently skipping the real second page). Always
  // subtract 1, clamped at 0, so UI page 1 maps to backend page 0.
  const backendPage = Math.max(0, f.page - 1);
  if (backendPage > 0) p.set("page", String(backendPage));
  // The controller's @RequestParam is `size`, not `pageSize` - confirmed live
  // 2026-07-30. Getting this wrong doesn't error, it just silently falls back
  // to the server's own default (page 0, size 24), which is what made it easy
  // to miss until the backend actually shipped.
  p.set("size", String(pageSize));
  return p.toString();
}

export function fetchCommunityTemplatesServer(
  filters: GalleryFilters,
  pageSize: number
): Promise<CommunityTemplatePage | null> {
  const qs = galleryQuery(filters, pageSize);
  return safeGet<CommunityTemplateSummary[]>(`/api/templates/gallery${qs ? `?${qs}` : ""}`).then(
    (items) =>
      items && {
        items,
        page: filters.page,
        pageSize,
        // Heuristic ("got a full page, there might be more"), not a real
        // count - the endpoint returns a bare array with no total. See
        // CommunityTemplatePage's own comment in lib/api.ts.
        hasMore: items.length >= pageSize,
      }
  );
}

export function fetchCommunityTemplateServer(id: string): Promise<CommunityTemplateDetail | null> {
  return safeGet<CommunityTemplateDetail>(`/api/templates/gallery/${encodeURIComponent(id)}`);
}

// Normalises a raw ?kind= search param (accepted case-insensitively, so both
// /templates?kind=agent and ?kind=AGENT work) into the uppercase wire value.
export function parseKind(raw: string | string[] | undefined): CommunityTemplateKind | "all" {
  const v = (Array.isArray(raw) ? raw[0] : raw)?.toUpperCase();
  return v === "AGENT" || v === "WORKFLOW" ? v : "all";
}
