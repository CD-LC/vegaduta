"use client";

import { Inbox } from "lucide-react";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import { Skeleton } from "../ui/skeleton";
import EmptyState from "../EmptyState";
import ApprovalStatusBadge from "./ApprovalStatusBadge";
import { actionLabel, actorLabel, relTime, type ApprovalRequestItem, type Translate } from "./approvalsModel";

/** Selectable list of approval requests. Rows are keyboard focusable; Enter/Space selects. */
export default function ApprovalsTable({
  rows,
  loading,
  selectedId,
  onSelect,
  t,
}: {
  rows: ApprovalRequestItem[];
  loading: boolean;
  selectedId: string | null;
  onSelect: (id: string) => void;
  t: Translate;
}) {
  if (loading && rows.length === 0) {
    return (
      <div className="grid gap-2 p-4" aria-busy="true" aria-label={t("loadingLabel")}>
        {[0, 1, 2, 3, 4].map((i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </div>
    );
  }
  if (rows.length === 0) {
    return <EmptyState icon={Inbox} title={t("emptyTitle")} hint={t("noRequestsFilter")} />;
  }
  return (
    <Table aria-label={t("title")}>
      <TableHeader>
        <TableRow>
          <TableHead>{t("colStatus")}</TableHead>
          <TableHead>{t("colAction")}</TableHead>
          <TableHead className="hidden md:table-cell">{t("colRequester")}</TableHead>
          <TableHead className="text-right">{t("colAge")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((r) => {
          const actor = actorLabel(r.requestedBy, t);
          const selected = r.id === selectedId;
          const summary = r.summary || t("noSummaryRecorded");
          return (
            <TableRow
              key={r.id}
              tabIndex={0}
              aria-current={selected ? "true" : undefined}
              data-state={selected ? "selected" : undefined}
              className={`cursor-pointer outline-none focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:ring-inset ${selected ? "bg-accent/60" : ""}`}
              onClick={() => onSelect(r.id)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  onSelect(r.id);
                }
              }}
            >
              <TableCell className="w-px whitespace-nowrap">
                <ApprovalStatusBadge status={r.status} />
              </TableCell>
              <TableCell className="max-w-0 min-w-40">
                <div className="truncate font-medium" title={r.actionType}>
                  {actionLabel(r.actionType, t)}
                </div>
                <div className="truncate text-xs text-muted-foreground" title={summary}>
                  {summary}
                </div>
              </TableCell>
              <TableCell className="hidden max-w-48 md:table-cell">
                <div className="flex min-w-0 items-center gap-2">
                  {actor.kind && (
                    <span className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                      {actor.kind}
                    </span>
                  )}
                  <span className="truncate text-sm" title={actor.full || undefined}>
                    {actor.short}
                  </span>
                </div>
              </TableCell>
              <TableCell className="whitespace-nowrap text-right text-sm text-muted-foreground">
                {relTime(r.createdAt, t)}
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
