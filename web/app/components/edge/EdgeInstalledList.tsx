"use client";

import { formatLastUsed } from "./edgeConsoleHelpers";
import { formatBytes } from "../../lib/format";
import {
  Check,
  
  
  
  
  
  
  
  
  
  
  
  
  
  Trash2,
  
  
  
} from "lucide-react";
import { cn } from "../../lib/utils";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { Button } from "../ui/button";
import {
  
  
  
  
  
  
  getLastUsed,
  
  
  
  
  
  
  
  
  
  
  
  
  
  
} from "./edgeConsoleBridge";

export interface InstalledRow {
  id: string;
  name: string;
  sizeBytes: number | null;
  deletable: boolean;
  onDelete?: () => void;
  badge: "default" | "auto" | "browser" | null;
}

// The Installed list as its own component so it can own a pagination hook -
// the rows are assembled after EdgeConsole's `!enabled` early return, so a
// top-level hook there couldn't stay unconditionally-called.
export function InstalledRowsList({
  rows,
  t,
  busyIds,
}: {
  rows: InstalledRow[];
  t: (key: string, values?: any) => string;
  busyIds: Record<string, boolean>;
}) {
  const pagination = usePagination(rows);
  return (
    <>
      <ul className="divide-y divide-border">
        {pagination.pageItems.map((row) => (
          <li key={row.id} className="flex items-center justify-between gap-3 bg-card px-4 py-3">
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className="truncate text-sm text-foreground">{row.name}</span>
                {row.badge && (
                  <span
                    className={cn(
                      "inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium",
                      row.badge === "browser" ? "bg-secondary text-muted-foreground" : "bg-primary/10 text-primary"
                    )}
                  >
                    {row.badge === "default" && <Check size={12} aria-hidden="true" />}
                    {row.badge === "default"
                      ? t("installed.default")
                      : row.badge === "auto"
                        ? t("installed.autoPick")
                        : t("installed.browserManaged")}
                  </span>
                )}
              </div>
              <div className="mt-0.5 text-[11px] text-muted-foreground">
                {row.sizeBytes != null && <span>{formatBytes(row.sizeBytes)} · </span>}
                {t("installed.lastUsed")}: {formatLastUsed(getLastUsed(row.id), t("installed.never"))}
              </div>
            </div>
            {row.deletable && row.onDelete && (
              <Button
                size="sm"
                variant="ghost"
                onClick={row.onDelete}
                disabled={!!busyIds[row.id]}
                aria-label={`${t("market.delete")} ${row.name}`}
              >
                <Trash2 size={13} aria-hidden="true" />
                {t("market.delete")}
              </Button>
            )}
          </li>
        ))}
      </ul>
      <PaginationControls
        page={pagination.page}
        totalPages={pagination.totalPages}
        totalItems={pagination.totalItems}
        pageSize={pagination.pageSize}
        onPageChange={pagination.setPage}
        onPageSizeChange={pagination.setPageSize}
        itemLabel="models"
      />
    </>
  );
}

export function InstalledActions({
  useNowLabel,
  onUseNow,
  active,
  activeLabel,
}: {
  useNowLabel: string;
  onUseNow: () => void;
  active: boolean;
  activeLabel: string;
}) {
  if (active) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-1 text-[11px] font-medium text-primary">
        <Check size={11} aria-hidden="true" />
        {activeLabel}
      </span>
    );
  }
  return (
    <Button size="sm" variant="secondary" onClick={onUseNow}>
      {useNowLabel}
    </Button>
  );
}
