"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch, toJsonQuietOn } from "../../lib/api/core";
import { pageForSizeChange, PAGE_SIZE_OPTIONS } from "../../lib/usePagination";

/**
 * Server-paged list with a graceful fallback, for the admin lists whose backend
 * is moving to `limit` / `offset` + `X-Total-Count` / `X-Has-More`
 * (docs/api/LIST-ENDPOINT-PAGING.md).
 *
 * New core: each page is fetched on demand and the pager gets the real total.
 * Older core (no `X-Total-Count`, `offset` ignored): the hook notices on the
 * first response, loads the complete list once (up to `legacyLimit` rows) and
 * pages it client-side, so paging still works - just without a true total.
 */
// 403/404/405 are reported inline by the page (older core / feature off), not as a global toast.
const QUIET = [403, 404, 405];

async function fetchPage<T>(path: string, limit: number): Promise<{ rows: T[]; total?: number; hasMore: boolean }> {
  const res = await apiFetch(path, { skipGetCache: true });
  const totalHeader = res.headers?.get?.("X-Total-Count");
  const moreHeader = res.headers?.get?.("X-Has-More");
  const body = await toJsonQuietOn<T[]>(QUIET)(res);
  const rows = Array.isArray(body) ? body : [];
  const total =
    totalHeader != null && totalHeader !== "" && Number.isFinite(Number(totalHeader)) ? Number(totalHeader) : undefined;
  return { rows, total, hasMore: moreHeader != null ? moreHeader === "true" : rows.length >= limit };
}

export interface PagedListState<T> {
  /** Rows to render for the current page. */
  rows: T[];
  /** Real total when known (server header, or the full legacy list length). */
  total: number | undefined;
  hasMore: boolean;
  /** True when the server did not page and the list is sliced in the browser. */
  legacy: boolean;
  page: number;
  pageSize: number;
  setPage: (page: number) => void;
  setPageSize: (size: number) => void;
  /** Back to page 1 (call when a filter changes). */
  resetPage: () => void;
  loading: boolean;
  error: string | null;
  /** Re-fetch the current page; `silent` keeps rows on screen (polling). */
  reload: (silent?: boolean) => Promise<void>;
}

export function usePagedList<T>(basePath: string | null, legacyLimit: number): PagedListState<T> {
  const [page, setPageState] = useState(1);
  const [pageSize, setPageSizeState] = useState(PAGE_SIZE_OPTIONS[0]);
  const [serverRows, setServerRows] = useState<T[]>([]);
  const [total, setTotal] = useState<number | undefined>(undefined);
  const [hasMore, setHasMore] = useState(false);
  const [legacy, setLegacy] = useState<{ path: string; all: T[] } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const legacyPath = useRef<string | null>(null);
  const seq = useRef(0);

  const load = useCallback(
    async (silent = false, force = false) => {
      if (!basePath) return;
      // Already known to be an unpaged endpoint: page changes are local.
      if (!force && legacyPath.current === basePath) return;
      const mine = ++seq.current;
      if (!silent) setLoading(true);
      const sep = basePath.includes("?") ? "&" : "?";
      try {
        const fetchOnce = (limit: number, offset: number) =>
          fetchPage<T>(`${basePath}${sep}limit=${limit}${offset > 0 ? `&offset=${offset}` : ""}`, limit);
        const wasLegacy = legacyPath.current === basePath;
        const res = await fetchOnce(wasLegacy ? legacyLimit : pageSize, wasLegacy ? 0 : (page - 1) * pageSize);
        if (mine !== seq.current) return;
        let unpaged = wasLegacy;
        let all = res.rows;
        if (!wasLegacy && res.total === undefined) {
          // No X-Total-Count: older core. If it ignored `limit` we already hold the whole list; if it honoured
          // `limit` but not `offset`, ask once for the full list and page it here.
          unpaged = true;
          if (res.rows.length >= pageSize && res.rows.length < legacyLimit) {
            const full = await fetchOnce(legacyLimit, 0);
            if (mine !== seq.current) return;
            all = full.rows;
          }
        }
        if (unpaged) {
          legacyPath.current = basePath;
          setLegacy({ path: basePath, all });
          setTotal(all.length);
          setHasMore(false);
        } else {
          legacyPath.current = null;
          setLegacy(null);
          setServerRows(res.rows);
          setTotal(res.total);
          setHasMore(res.hasMore);
        }
        setError(null);
      } catch (e) {
        if (mine !== seq.current) return;
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (mine === seq.current) setLoading(false);
      }
    },
    [basePath, page, pageSize, legacyLimit]
  );

  useEffect(() => {
    void load();
  }, [load]);

  const reload = useCallback((silent = false) => load(silent, true), [load]);

  // A different path (filter change) invalidates what we learned about the endpoint.
  const legacyAll = legacy && legacy.path === basePath ? legacy.all : null;
  const rows = legacyAll ? legacyAll.slice((page - 1) * pageSize, page * pageSize) : serverRows;

  return {
    rows,
    total: legacyAll ? legacyAll.length : total,
    hasMore: legacyAll ? page * pageSize < legacyAll.length : hasMore,
    legacy: legacyAll !== null,
    page,
    pageSize,
    setPage: setPageState,
    setPageSize: (size) => {
      setPageState(pageForSizeChange(page, pageSize, size));
      setPageSizeState(size);
    },
    resetPage: () => setPageState(1),
    loading,
    error,
    reload,
  };
}
