"use client";

import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import type { PagedRows } from "./api/paged";

export type ServerPages<T> = {
  rows: T[];
  setRows: Dispatch<SetStateAction<T[]>>;
  /** Real total when the server reported one, else null. */
  total: number | null;
  /** Older rows exist beyond what is loaded. */
  hasMore: boolean;
  loading: boolean;
  loadingMore: boolean;
  /**
   * The last loadMore failed or made no progress (every returned row was already held). hasMore stays
   * true so the Load more button can retry, but auto-loaders MUST NOT keep calling loadMore while this
   * is set - that is what prevents a request loop. Cleared by reload() and by the next manual attempt.
   */
  loadError: boolean;
  /** Replace the list with the newest page (a refresh). */
  reload: (opts?: { quiet?: boolean }) => Promise<void>;
  /** Append the next older page. No-op while a load is in flight or nothing is left. */
  loadMore: () => Promise<void>;
};

/**
 * Offset paging over a server-capped list. The first page is the newest rows;
 * loadMore() asks for `offset = rows already held`, which stays correct after a
 * local delete (the server's rows shifted up too), and appended rows are de-duplicated by id
 * because a row created meanwhile shifts older rows down by one.
 */
export function useServerPages<T>(
  fetchPage: (offset: number, limit: number) => Promise<PagedRows<T>>,
  pageSize: number,
  getId: (row: T) => string = (row: any) => String(row?.id)
): ServerPages<T> {
  const [rows, setRows] = useState<T[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const seq = useRef(0);
  const rowsRef = useRef<T[]>(rows);
  rowsRef.current = rows;
  const busy = useRef(false);
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;

  const reload = useCallback(
    async (opts?: { quiet?: boolean }) => {
      const mine = ++seq.current;
      if (!opts?.quiet) setLoading(true);
      setLoadError(false);
      try {
        const page = await fetchRef.current(0, pageSize);
        if (mine !== seq.current) return;
        setRows(page.rows);
        setTotal(page.total);
        setHasMore(page.hasMore);
      } catch {
        if (mine !== seq.current) return;
        setRows([]);
        setTotal(null);
        setHasMore(false);
      } finally {
        if (mine === seq.current) setLoading(false);
      }
    },
    [pageSize]
  );

  const loadMore = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    const mine = seq.current;
    setLoadingMore(true);
    try {
      const page = await fetchRef.current(rowsRef.current.length, pageSize);
      if (mine !== seq.current) return;
      const seen = new Set(rowsRef.current.map(getId));
      const fresh = page.rows.filter((r) => !seen.has(getId(r)));
      setRows((prev) => {
        const have = new Set(prev.map(getId));
        return [...prev, ...fresh.filter((r) => !have.has(getId(r)))];
      });
      setTotal(page.total);
      setHasMore(page.hasMore);
      // No progress (a page of rows we already hold, e.g. inserts shifted the window): flag it so
      // auto-loaders stop re-requesting the same offset; the button can still retry.
      setLoadError(fresh.length === 0 && page.hasMore);
    } catch {
      // Keep hasMore as is so the button can retry; loadError stops auto-loaders from hammering a failing endpoint.
      if (mine === seq.current) setLoadError(true);
    } finally {
      busy.current = false;
      setLoadingMore(false);
    }
  }, [pageSize, getId]);

  useEffect(
    () => () => {
      seq.current++;
    },
    []
  );

  return { rows, setRows, total, hasMore, loading, loadingMore, loadError, reload, loadMore };
}
