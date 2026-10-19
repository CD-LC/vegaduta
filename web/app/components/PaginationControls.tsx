"use client";

import { useEffect, useState, type FormEvent } from "react";
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react";
import { PAGE_SIZE_OPTIONS } from "../lib/usePagination";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";

/*
 * The one standard pager for every list, table and grid in the console.
 *
 * Client mode (slice of an in-memory array) - pair with usePagination():
 *
 *   const pg = usePagination(rows);
 *   pg.pageItems.map(...)
 *   <PaginationControls page={pg.page} totalPages={pg.totalPages}
 *     totalItems={pg.totalItems} pageSize={pg.pageSize}
 *     onPageChange={pg.setPage} onPageSizeChange={pg.setPageSize} itemLabel="users" />
 *
 * Server mode (the API pages for you): keep page/pageSize in state, fetch with
 * offset=(page-1)*pageSize, limit=pageSize, and pass what the API told you.
 * `totalItems` is optional; without it, pass `hasMore` (e.g. you fetched
 * limit+1 rows) and the range reads "11-20" with a "more available" hint.
 * `pageItemCount` is the number of rows on the current page (needed for a
 * correct range end when the total is unknown). Use pageForSizeChange() from
 * lib/usePagination to keep the first visible row in view on a size change.
 *
 * Truncated lists: when the server silently caps a list, pass `capped={N}`;
 * a "Showing the first N. Narrow the filters to see more." notice appears once
 * the list reaches the cap (or the total is unknown).
 *
 * Optional: pageSizeOptions (default 10/25/50/100), loading (disables the
 * controls while a page is in flight), itemLabel ("records" by default).
 * Renders nothing for an empty list. Styling is Tailwind + theme tokens only.
 */
interface PaginationControlsProps {
  page: number;
  totalPages?: number;
  totalItems?: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  itemLabel?: string;
  /** Server mode: more rows exist beyond this page (used when totalItems is unknown). */
  hasMore?: boolean;
  /** Rows on the current page; defaults to a full page. */
  pageItemCount?: number;
  /** The server silently caps the list at this many rows. */
  capped?: number;
  pageSizeOptions?: number[];
  loading?: boolean;
}

const navBtn =
  "inline-flex size-9 shrink-0 items-center justify-center rounded-md border border-border bg-card p-0 text-foreground " +
  "outline-none transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:ring-[3px] focus-visible:ring-ring " +
  "disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-card";

export default function PaginationControls({
  page,
  totalPages,
  totalItems,
  pageSize,
  onPageChange,
  onPageSizeChange,
  itemLabel,
  hasMore,
  pageItemCount,
  capped,
  pageSizeOptions,
  loading = false,
}: PaginationControlsProps) {
  const t = useTranslations("paginationControls");
  const totalKnown = typeof totalItems === "number";
  const lastPage = totalKnown
    ? Math.max(1, totalPages ?? Math.ceil(totalItems / pageSize))
    : undefined;
  const [jump, setJump] = useState(String(page));
  useEffect(() => setJump(String(page)), [page]);

  if (totalKnown && totalItems === 0) return null;
  if (!totalKnown && pageItemCount === 0 && page <= 1 && !hasMore) return null;

  const start = (page - 1) * pageSize + 1;
  const end = totalKnown
    ? Math.min(page * pageSize, totalItems)
    : start + Math.max(0, (pageItemCount ?? pageSize) - 1);
  const label = itemLabel || t("recordsLabel");
  const atFirst = page <= 1;
  const atLast = totalKnown ? page >= (lastPage as number) : !hasMore;
  const showNav = totalKnown ? (lastPage as number) > 1 : page > 1 || !!hasMore;
  const showJump = totalKnown && (lastPage as number) > 5;
  const sizes = Array.from(new Set([...(pageSizeOptions ?? PAGE_SIZE_OPTIONS), pageSize])).sort((a, b) => a - b);
  const showCapNotice = typeof capped === "number" && capped > 0 && (!totalKnown || (totalItems as number) >= capped);

  const go = (target: number) => {
    const max = lastPage ?? Number.POSITIVE_INFINITY;
    const next = Math.min(Math.max(1, Math.floor(target)), max);
    if (next !== page) onPageChange(next);
  };
  const submitJump = (e: FormEvent) => {
    e.preventDefault();
    const n = Number(jump);
    if (Number.isFinite(n)) go(n);
    else setJump(String(page));
  };

  return (
    <div className="border-t border-border px-1 py-2.5 text-xs text-muted-foreground sm:px-4">
      {showCapNotice && (
        <p role="note" className="mb-2 text-amber-600 dark:text-amber-400">
          {t("cappedNotice", { capped })}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-2 sm:justify-between">
        <span role="status" aria-live="polite" aria-atomic="true" className="tabular-nums">
          {totalKnown
            ? t("rangeSummary", { start, end, totalItems, itemLabel: label })
            : t("rangeSummaryUnknown", { start, end, itemLabel: label })}
          {!totalKnown && hasMore ? <span> {t("moreAvailable")}</span> : null}
        </span>

        <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-2">
          <label className="flex items-center gap-2">
            <span>{t("show")}</span>
            <select
              aria-label={t("rowsPerPage")}
              value={pageSize}
              disabled={loading}
              onChange={(e) => onPageSizeChange(Number(e.target.value))}
              className="h-9 rounded-md border border-border bg-card px-2 text-xs text-foreground tabular-nums outline-none focus-visible:ring-[3px] focus-visible:ring-ring disabled:opacity-60"
            >
              {sizes.map((size) => (
                <option key={size} value={size}>
                  {size}
                </option>
              ))}
            </select>
          </label>

          {showNav && (
            <nav aria-label={t("navLabel")} className="flex items-center gap-1.5">
              <button type="button" className={navBtn} disabled={loading || atFirst} onClick={() => go(1)} aria-label={t("firstPage")}>
                <ChevronsLeft aria-hidden="true" size={14} />
              </button>
              <button type="button" className={navBtn} disabled={loading || atFirst} onClick={() => go(page - 1)} aria-label={t("previousPage")}>
                <ChevronLeft aria-hidden="true" size={14} />
              </button>
              <span className="min-w-20 text-center tabular-nums">
                {totalKnown ? t("pageOf", { page, totalPages: lastPage }) : t("pageN", { page })}
              </span>
              <button type="button" className={navBtn} disabled={loading || atLast} onClick={() => go(page + 1)} aria-label={t("nextPage")}>
                <ChevronRight aria-hidden="true" size={14} />
              </button>
              {totalKnown && (
                <button type="button" className={navBtn} disabled={loading || atLast} onClick={() => go(lastPage as number)} aria-label={t("lastPage")}>
                  <ChevronsRight aria-hidden="true" size={14} />
                </button>
              )}
            </nav>
          )}

          {showJump && (
            <form onSubmit={submitJump} className="flex items-center gap-2">
              <label htmlFor="pager-jump" className="sr-only">
                {t("jumpToPage")}
              </label>
              <input
                id="pager-jump"
                type="number"
                inputMode="numeric"
                min={1}
                max={lastPage}
                value={jump}
                disabled={loading}
                onChange={(e) => setJump(e.target.value)}
                className={cn(
                  "h-9 w-16 rounded-md border border-border bg-card px-2 text-center text-xs text-foreground tabular-nums outline-none",
                  "focus-visible:ring-[3px] focus-visible:ring-ring disabled:opacity-60"
                )}
              />
              <button type="submit" disabled={loading} className="h-9 rounded-md border border-border bg-card px-3 text-xs text-foreground hover:bg-accent disabled:opacity-40">
                {t("go")}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
