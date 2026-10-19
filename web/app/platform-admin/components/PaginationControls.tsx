"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { PAGE_SIZE_OPTIONS } from "../lib/usePagination";
import { Button } from "./ui/button";

// Shared page-size + prev/next control for every web-admin list wired to
// usePagination(). shadcn/token-styled (no i18n — this app uses plain strings),
// mirroring the tenant console's PaginationControls contract so call sites read
// the same on both apps.
export default function PaginationControls({
  page,
  totalPages,
  totalItems,
  pageSize,
  onPageChange,
  onPageSizeChange,
  itemLabel = "records",
  hasMore,
  pageItemCount,
  capped,
  loading = false,
}: {
  page: number;
  totalPages?: number;
  /** Omit in server mode when the API gave no total; pass hasMore instead. */
  totalItems?: number;
  /** Server mode: more rows exist beyond this page (used when totalItems is unknown). */
  hasMore?: boolean;
  /** Rows on the current page (needed for the range end when the total is unknown). */
  pageItemCount?: number;
  /** The server silently caps the list at this many rows. */
  capped?: number;
  loading?: boolean;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  itemLabel?: string;
}) {
  const totalKnown = typeof totalItems === "number";
  if (totalKnown && totalItems === 0) return null;
  if (!totalKnown && (pageItemCount ?? 1) === 0 && page <= 1) return null;

  const lastPage = totalKnown ? Math.max(1, totalPages ?? Math.ceil(totalItems / pageSize)) : undefined;
  const start = (page - 1) * pageSize + 1;
  const end = totalKnown ? Math.min(page * pageSize, totalItems) : start + Math.max(0, (pageItemCount ?? pageSize) - 1);
  const atEnd = totalKnown ? page >= (lastPage as number) : !hasMore;
  const showNav = totalKnown ? (lastPage as number) > 1 : page > 1 || !!hasMore;
  const showCap = capped !== undefined && (totalKnown ? totalItems >= capped : true);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-1 py-2.5 text-xs text-muted-foreground">
      <span className="tabular-nums">
        {totalKnown
          ? `${start}–${end} of ${totalItems} ${itemLabel}`
          : `${start}–${end} ${itemLabel}${hasMore ? " (more available)" : ""}`}
        {showCap && (
          <span className="ml-2 text-[var(--warning-text)]" role="status">
            Showing the first {capped}. Narrow the filters to see more.
          </span>
        )}
      </span>

      <div className="flex items-center gap-2">
        <div className="flex items-center gap-1">
          <span>Show</span>
          {PAGE_SIZE_OPTIONS.map((size) => (
            <button
              key={size}
              type="button"
              aria-pressed={pageSize === size}
              disabled={loading}
              onClick={() => onPageSizeChange(size)}
              className={`rounded-md border-0 bg-transparent px-2 py-0.5 tabular-nums shadow-none transition-colors ${
                pageSize === size ? "bg-primary/12 font-medium text-primary" : "hover:bg-accent hover:text-foreground"
              }`}
            >
              {size}
            </button>
          ))}
        </div>

        {showNav && (
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              className="size-7"
              disabled={loading || page <= 1}
              onClick={() => onPageChange(page - 1)}
              aria-label="Previous page"
            >
              <ChevronLeft size={14} />
            </Button>
            <span className="tabular-nums">{totalKnown ? `${page} / ${lastPage}` : page}</span>
            <Button
              variant="ghost"
              size="icon"
              className="size-7"
              disabled={loading || atEnd}
              onClick={() => onPageChange(page + 1)}
              aria-label="Next page"
            >
              <ChevronRight size={14} />
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
