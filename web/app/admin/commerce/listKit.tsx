"use client";

import type { ReactNode } from "react";
import { AlertCircle } from "lucide-react";
import PaginationControls from "../../components/PaginationControls";
import { Alert } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Skeleton } from "../../components/ui/skeleton";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { usePagination } from "../../lib/usePagination";

// Shared list building blocks for the commerce / payments / agent-trust admin
// pages: a client-side paged list, an error banner with Retry, a loading
// skeleton and status filter pills.

/** Renders `items` one page at a time with the standard pager underneath. */
export function PagedList<T>({
  items,
  renderItem,
  itemLabel,
  capped,
  className = "grid gap-2",
}: {
  items: T[];
  renderItem: (item: T) => ReactNode;
  itemLabel: string;
  /** The server silently caps this list at N rows. */
  capped?: number;
  className?: string;
}) {
  const pg = usePagination(items);
  return (
    <>
      <div className={className}>{pg.pageItems.map(renderItem)}</div>
      <div className="mt-3">
        <PaginationControls
          page={pg.page}
          totalPages={pg.totalPages}
          totalItems={pg.totalItems}
          pageSize={pg.pageSize}
          onPageChange={pg.setPage}
          onPageSizeChange={pg.setPageSize}
          itemLabel={itemLabel}
          capped={capped}
        />
      </div>
    </>
  );
}

export function ErrorRetry({ message, onRetry, className }: { message: string; onRetry: () => void; className?: string }) {
  const t = useTranslations("adminCommerceUi");
  return (
    <Alert variant="destructive" className={cn("mb-4", className)}>
      <AlertCircle aria-hidden="true" />
      <div className="col-start-2 flex flex-wrap items-center justify-between gap-2">
        <span>{message}</span>
        <Button type="button" variant="outline" size="sm" onClick={onRetry}>
          {t("retry")}
        </Button>
      </div>
    </Alert>
  );
}

export function ListSkeleton({ rows = 3 }: { rows?: number }) {
  const t = useTranslations("adminCommerceUi");
  return (
    <div className="grid gap-2" role="status" aria-label={t("loading")}>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-12 w-full" />
      ))}
    </div>
  );
}

/** Status filter pills ("All" plus each option). */
export function FilterPills({
  options,
  value,
  onChange,
}: {
  options: string[];
  value: string;
  onChange: (v: string) => void;
}) {
  const t = useTranslations("adminCommerceUi");
  const all = ["", ...options];
  return (
    <div className="flex flex-wrap gap-1.5" role="group" aria-label={t("filterLabel")}>
      {all.map((o) => (
        <Button
          key={o || "all"}
          type="button"
          size="sm"
          variant={value === o ? "default" : "outline"}
          aria-pressed={value === o}
          onClick={() => onChange(o)}
        >
          {o || t("filterAll")}
        </Button>
      ))}
    </div>
  );
}
