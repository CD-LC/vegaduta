"use client";

// The wrapper every chart in the console sits in. It owns the things a bare
// chart always forgets:
//   - a title and a REQUIRED plain-language `summary` sentence, shown as text
//     above the chart and handed to the chart inside (via context) as its
//     SVG aria-label, so the picture and the sentence can never disagree;
//   - an optional period switch slot;
//   - a "View as table" toggle that renders the same data with ui/table;
//   - loading, error and empty states. In any of those the chart body is NOT
//     rendered at all - an empty axis is never drawn.
import { useId, useState, type ComponentType, type ReactNode } from "react";
import { ChartSummaryContext } from "./chartContext";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { EmptyState } from "../ax/page-kit";
import { Button } from "../ui/button";
import { Skeleton } from "../ui/skeleton";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";

/** Tables longer than this scroll inside the card (about 8 visible rows). */
const LONG_TABLE_ROWS = 12;
/** Tables longer than this are paged (a 365-day series is 15 pages, not one endless scroll). */
const PAGED_TABLE_ROWS = 25;
const EMPTY_ROWS: Array<Record<string, ReactNode>> = [];

export interface ChartTableColumn {
  /** Property name read from each row. */
  key: string;
  header: ReactNode;
  align?: "left" | "right";
}

export interface ChartTableData {
  columns: ChartTableColumn[];
  /** One object per row, keyed by column `key`. `id` (optional) is used as the React key. */
  rows: Array<Record<string, ReactNode>>;
  /** Optional visible caption under the table. */
  caption?: ReactNode;
}

export interface ChartFrameProps {
  title: ReactNode;
  /** One plain sentence saying what the chart shows, e.g. "42 of 50 runs succeeded this week." */
  summary: string;
  /** The chart. Not rendered while loading, on error, when empty, or in table view. */
  children?: ReactNode;
  /** Slot for a period switch (e.g. 7d / 30d tabs), top right. */
  periodSwitch?: ReactNode;
  /** Same data as the chart; enables the "View as table" toggle. */
  table?: ChartTableData;
  defaultView?: "chart" | "table";
  loading?: boolean;
  /** Error message / node. When set, replaces the chart. */
  error?: ReactNode;
  /** Force the empty state. Defaults to true when `table` is given with zero rows. */
  empty?: boolean;
  /** Empty-state title; defaults to the built-in "No data yet". */
  emptyTitle?: ReactNode;
  emptyDescription?: ReactNode;
  /** Optional CTA (a Button / Link) under the empty-state text. */
  emptyAction?: ReactNode;
  emptyIcon?: ComponentType<{ className?: string }>;
  /** Small print under the chart (source, "updated 2 min ago", partial-period note). */
  footer?: ReactNode;
  className?: string;
}

export default function ChartFrame({
  title,
  summary,
  children,
  periodSwitch,
  table,
  defaultView = "chart",
  loading = false,
  error,
  empty,
  emptyTitle,
  emptyDescription,
  emptyAction,
  emptyIcon,
  footer,
  className,
}: ChartFrameProps) {
  const t = useTranslations("charts");
  const [view, setView] = useState<"chart" | "table">(defaultView);
  const tablePager = usePagination(table?.rows ?? EMPTY_ROWS, PAGED_TABLE_ROWS);
  const headingId = `chart-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;

  const hasError = error !== undefined && error !== null && error !== false && error !== "";
  const isEmpty = empty ?? (table ? table.rows.length === 0 : false);
  const ready = !loading && !hasError && !isEmpty;
  const showTable = ready && !!table && view === "table";

  let body: ReactNode;
  if (loading) {
    body = (
      <div aria-busy="true" data-chart-state="loading">
        <Skeleton className="h-28 w-full" />
      </div>
    );
  } else if (hasError) {
    body = (
      <div
        role="alert"
        data-chart-state="error"
        className="rounded-lg border border-border px-4 py-3 text-sm"
        style={{ color: "var(--state-error)" }}
      >
        {error}
      </div>
    );
  } else if (isEmpty) {
    body = (
      <div data-chart-state="empty">
        <EmptyState
          icon={emptyIcon}
          title={emptyTitle ?? t("noData")}
          description={emptyDescription}
          action={emptyAction}
          className="px-4 py-8"
        />
      </div>
    );
  } else if (showTable && table) {
    // A long table (90 daily rows) scrolls inside the card instead of pushing
    // everything below it off the page. The scroll area is focusable so the
    // keyboard can reach the rows.
    const paged = table.rows.length > PAGED_TABLE_ROWS;
    const visibleRows = paged ? tablePager.pageItems : table.rows;
    const long = visibleRows.length > LONG_TABLE_ROWS;
    const grid = (
      <Table aria-labelledby={headingId}>
        {table.caption && <caption className="mt-2 text-xs text-muted-foreground">{table.caption}</caption>}
        <TableHeader>
          <TableRow>
            {table.columns.map((column) => (
              <TableHead
                key={column.key}
                scope="col"
                className={cn("h-9 px-3", column.align === "right" && "text-end")}
              >
                {column.header}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {visibleRows.map((row, index) => (
            <TableRow key={typeof row.id === "string" || typeof row.id === "number" ? row.id : index}>
              {table.columns.map((column) => (
                <TableCell
                  key={column.key}
                  className={cn("px-3 py-2", column.align === "right" && "text-end tabular-nums")}
                >
                  {row[column.key]}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    );
    const scrollable = long ? (
      <div
        data-chart-table-scroll=""
        role="region"
        aria-labelledby={headingId}
        tabIndex={0}
        className="max-h-80 overflow-y-auto rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
      >
        {grid}
      </div>
    ) : (
      grid
    );
    body = paged ? (
      <div className="space-y-2">
        {scrollable}
        <PaginationControls
          page={tablePager.page}
          totalPages={tablePager.totalPages}
          totalItems={tablePager.totalItems}
          pageSize={tablePager.pageSize}
          onPageChange={tablePager.setPage}
          onPageSizeChange={tablePager.setPageSize}
        />
      </div>
    ) : (
      scrollable
    );
  } else {
    body = <ChartSummaryContext.Provider value={summary}>{children}</ChartSummaryContext.Provider>;
  }

  return (
    <section aria-labelledby={headingId} className={cn("min-w-0 space-y-3", className)}>
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
        <div className="min-w-0 flex-1 basis-48 space-y-0.5">
          <h3 id={headingId} className="text-sm font-semibold tracking-tight text-foreground">
            {title}
          </h3>
          {ready && (
            <p data-chart-summary="" className="text-sm text-muted-foreground">
              {summary}
            </p>
          )}
        </div>
        {(periodSwitch || (ready && table)) && (
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            {periodSwitch}
            {ready && table && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                data-chart-view-toggle=""
                onClick={() => setView((current) => (current === "table" ? "chart" : "table"))}
              >
                {view === "table" ? t("viewAsChart") : t("viewAsTable")}
              </Button>
            )}
          </div>
        )}
      </div>
      <div className="min-w-0">{body}</div>
      {footer && ready && <div className="text-xs text-muted-foreground">{footer}</div>}
    </section>
  );
}
