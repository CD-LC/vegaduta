"use client";

import { useEffect, useMemo, useState } from "react";

// Shared client-side pagination for the console's many "list of records"
// panels (Users, Approvals, Audit Log, Knowledge docs, MCP servers, Devices,
// workflow runs, Quick Task history, LLM call logs, agent tool-call/memory
// logs...). Every one of those endpoints already returns the tenant's full
// array in one response (see api.js - none of them take page/size params),
// so this slices client-side rather than adding server-side paging to a
// dozen endpoints at once. Revisit with real server-side paging if any of
// these lists grows large enough that shipping the full array becomes a
// real payload-size problem.
export const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];

/**
 * Page that keeps the first item of the current page in view after the page
 * size changes (e.g. page 3 of 10 = items 21-30; at size 50 that is page 1,
 * at size 5 it is page 5). Pure so server-mode callers can reuse it.
 */
export function pageForSizeChange(page: number, oldSize: number, newSize: number): number {
  const firstIndex = (Math.max(1, page) - 1) * Math.max(1, oldSize);
  return Math.floor(firstIndex / Math.max(1, newSize)) + 1;
}

export function usePagination<T>(items: T[], defaultPageSize = PAGE_SIZE_OPTIONS[0]) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(defaultPageSize);

  const totalItems = items.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  const clampedPage = Math.min(Math.max(1, page), totalPages);

  // Snap back into range whenever the underlying list shrinks (a search
  // filter narrows it, a row gets deleted, page size changes) so the view
  // never silently lands on a blank page.
  useEffect(() => {
    if (page !== clampedPage) setPage(clampedPage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clampedPage]);

  const pageItems = useMemo(() => {
    const start = (clampedPage - 1) * pageSize;
    return items.slice(start, start + pageSize);
  }, [items, clampedPage, pageSize]);

  function changePageSize(size: number) {
    const next = Number(size);
    if (!Number.isFinite(next) || next < 1) return;
    // Keep the first visible item on screen instead of snapping back to page 1.
    setPage(pageForSizeChange(clampedPage, pageSize, next));
    setPageSize(next);
  }

  return {
    page: clampedPage,
    pageSize,
    totalItems,
    totalPages,
    pageItems,
    setPage,
    setPageSize: changePageSize,
  };
}
