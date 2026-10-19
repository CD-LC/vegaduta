"use client";

import { useTranslations } from "../lib/i18n";

/**
 * "Showing the newest N of TOTAL" + "Load more" under a server-capped list.
 * Renders nothing when everything is loaded, so short lists look unchanged.
 * For numbered pages use PaginationControls (components/PaginationControls.tsx)
 * instead; this bar is for append-style feeds.
 */
export default function LoadMoreBar({
  loaded,
  total,
  hasMore,
  loading,
  onLoadMore,
  itemLabel,
}: {
  loaded: number;
  total: number | null;
  hasMore: boolean;
  loading: boolean;
  onLoadMore: () => void;
  itemLabel: string;
}) {
  const t = useTranslations("loadMoreBar");
  if (!hasMore) return null;
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-2 border-t border-border px-1 py-2.5 text-xs text-muted-foreground sm:justify-between sm:px-4">
      <span role="status" aria-live="polite" className="tabular-nums">
        {total != null ? t("showingOfTotal", { loaded, total, itemLabel }) : t("showingLatest", { loaded, itemLabel })}
      </span>
      <button
        type="button"
        disabled={loading}
        aria-busy={loading}
        onClick={onLoadMore}
        className="h-9 rounded-md border border-border bg-card px-3.5 text-xs text-foreground outline-none transition-colors hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-40"
      >
        {loading ? t("loading") : t("loadMore")}
      </button>
    </div>
  );
}
