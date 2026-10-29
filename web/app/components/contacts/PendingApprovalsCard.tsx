"use client";

import { Check, Inbox, UserCheck, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import PaginationControls from "../PaginationControls";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "../ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import { SectionTitle } from "./ContactsGroupsImport";
import { ChannelChip, STACK_LABEL, useTimeAgo, type BulkProgress, type PendingPairing } from "./shared";

/** Pending-approvals queue: selectable table (>= sm) and stacked list (< sm). */
export default function PendingApprovalsCard({
  pending,
  pagination,
  selected,
  rowBusy,
  bulk,
  allOnPageSelected,
  selectAllRef,
  selectAllStackRef,
  onToggleSelectAll,
  onToggleSelected,
  onApprove,
  onReject,
}: {
  pending: PendingPairing[];
  pagination: {
    pageItems: PendingPairing[];
    page: number;
    totalPages: number;
    totalItems: number;
    pageSize: number;
    setPage: (page: number) => void;
    setPageSize: (size: number) => void;
  };
  selected: Set<string>;
  rowBusy: Set<string>;
  bulk: BulkProgress | null;
  allOnPageSelected: boolean;
  selectAllRef: React.RefObject<HTMLInputElement | null>;
  selectAllStackRef: React.RefObject<HTMLInputElement | null>;
  onToggleSelectAll: () => void;
  onToggleSelected: (id: string) => void;
  onApprove: (sessionId: string) => void;
  onReject: (sessionId: string) => void;
}) {
  const t = useTranslations("contactsPage");
  const timeAgo = useTimeAgo();
  const pendingPagination = pagination;
  const toggleSelectAllOnPage = onToggleSelectAll;
  const toggleSelected = onToggleSelected;

  const pendingCode = (p: PendingPairing) =>
    p.pairingCode ? (
      <Badge variant="outline" className="font-mono">
        {p.pairingCode}
      </Badge>
    ) : (
      <span className="text-muted-foreground">—</span>
    );

  const pendingActions = (p: PendingPairing, isBusy: boolean, buttonClassName?: string) => (
    <>
      <Button
        type="button"
        size="sm"
        variant="outline"
        className={buttonClassName}
        disabled={isBusy}
        onClick={() => onApprove(p.sessionId)}
      >
        <Check aria-hidden="true" size={14} /> {t("approveButton")}
      </Button>
      <Button
        type="button"
        size="sm"
        variant="ghost"
        className={`text-destructive hover:bg-destructive/10 hover:text-destructive ${buttonClassName || ""}`}
        disabled={isBusy}
        onClick={() => onReject(p.sessionId)}
      >
        <X aria-hidden="true" size={14} /> {t("rejectButton")}
      </Button>
    </>
  );

  return (
    <Card className="gap-4">
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <SectionTitle icon={<UserCheck aria-hidden="true" size={16} />}>{t("pendingTitle")}</SectionTitle>
          {pending.length > 0 && <Badge variant="secondary">{pending.length}</Badge>}
        </div>
        <CardDescription>{t("pendingHint")}</CardDescription>
      </CardHeader>
      <CardContent>
        {pending.length === 0 ? (
          <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-border px-4 py-10 text-center">
            <span
              className="inline-flex h-11 w-11 items-center justify-center rounded-full bg-muted text-muted-foreground"
              aria-hidden="true"
            >
              <Inbox aria-hidden="true" size={20} />
            </span>
            <div className="text-sm font-medium text-foreground">{t("pendingEmptyTitle")}</div>
            <p className="max-w-md text-sm text-muted-foreground">{t("pendingEmptyBody")}</p>
          </div>
        ) : (
          <>
            {/* >= sm: selectable table */}
            <div className="hidden sm:block">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-11 pe-0">
                      <input
                        ref={selectAllRef}
                        type="checkbox"
                        className="h-4 w-4 cursor-pointer align-middle"
                        checked={allOnPageSelected}
                        onChange={toggleSelectAllOnPage}
                        disabled={!!bulk}
                        aria-label={t("colSelectAll")}
                      />
                    </TableHead>
                    <TableHead className="w-[170px]">{t("colChannel")}</TableHead>
                    <TableHead>{t("colSender")}</TableHead>
                    <TableHead className="w-[150px]">{t("colCode")}</TableHead>
                    <TableHead className="w-[140px]">{t("colWaiting")}</TableHead>
                    <TableHead className="w-[220px] text-end">{t("colActions")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pendingPagination.pageItems.map((p) => {
                    const isSelected = selected.has(p.sessionId);
                    const isBusy = rowBusy.has(p.sessionId) || !!bulk;
                    return (
                      <TableRow
                        key={p.sessionId}
                        className={`h-14 ${isSelected ? "bg-accent/40 hover:bg-accent/50" : ""}`}
                        data-selected={isSelected || undefined}
                      >
                        <TableCell className="pe-0">
                          <input
                            type="checkbox"
                            className="h-4 w-4 cursor-pointer align-middle"
                            checked={isSelected}
                            onChange={() => toggleSelected(p.sessionId)}
                            disabled={!!bulk}
                            aria-label={t("colSelectRow", { id: p.externalIdMasked })}
                          />
                        </TableCell>
                        <TableCell>
                          <ChannelChip channel={p.channel} />
                        </TableCell>
                        <TableCell className="font-mono text-sm text-foreground">{p.externalIdMasked}</TableCell>
                        <TableCell>{pendingCode(p)}</TableCell>
                        <TableCell className="text-sm text-muted-foreground">{timeAgo(p.createdAt)}</TableCell>
                        <TableCell className="text-end">
                          <span className="inline-flex justify-end gap-2">{pendingActions(p, isBusy)}</span>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
            {/* < sm: stacked rows - tap the row to select it, actions on their own line */}
            <div className="flex flex-col gap-2 sm:hidden" data-testid="pending-stack">
              <label className="flex cursor-pointer items-center gap-3 px-1 py-1 text-sm text-muted-foreground">
                <input
                  ref={selectAllStackRef}
                  type="checkbox"
                  className="h-4 w-4 cursor-pointer"
                  checked={allOnPageSelected}
                  onChange={toggleSelectAllOnPage}
                  disabled={!!bulk}
                  aria-label={t("colSelectAll")}
                />
                {t("colSelectAll")}
              </label>
              <ul className="flex flex-col gap-2">
                {pendingPagination.pageItems.map((p) => {
                  const isSelected = selected.has(p.sessionId);
                  const isBusy = rowBusy.has(p.sessionId) || !!bulk;
                  return (
                    <li
                      key={p.sessionId}
                      className={`flex flex-col gap-3 rounded-lg border p-3 transition-colors ${isSelected ? "border-accent-foreground/20 bg-accent/40" : "border-border"}`}
                      data-selected={isSelected || undefined}
                    >
                      <label className="flex cursor-pointer items-center gap-3">
                        <input
                          type="checkbox"
                          className="h-4 w-4 shrink-0 cursor-pointer"
                          checked={isSelected}
                          onChange={() => toggleSelected(p.sessionId)}
                          disabled={!!bulk}
                          aria-label={t("colSelectRow", { id: p.externalIdMasked })}
                        />
                        <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1">
                          <ChannelChip channel={p.channel} />
                          <span className="truncate font-mono text-sm text-foreground">{p.externalIdMasked}</span>
                        </span>
                      </label>
                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 ps-7 text-sm text-muted-foreground">
                        <span className="inline-flex items-center gap-1.5">
                          <span className={STACK_LABEL}>{t("colCode")}</span>
                          {pendingCode(p)}
                        </span>
                        <span className="inline-flex items-center gap-1.5">
                          <span className={STACK_LABEL}>{t("colWaiting")}</span>
                          {timeAgo(p.createdAt)}
                        </span>
                      </div>
                      <div className="grid grid-cols-2 gap-2">{pendingActions(p, isBusy, "h-10 w-full")}</div>
                    </li>
                  );
                })}
              </ul>
            </div>
            <PaginationControls
              page={pendingPagination.page}
              totalPages={pendingPagination.totalPages}
              totalItems={pendingPagination.totalItems}
              pageSize={pendingPagination.pageSize}
              onPageChange={pendingPagination.setPage}
              onPageSizeChange={pendingPagination.setPageSize}
              itemLabel={t("itemLabelRequests")}
            />
          </>
        )}
      </CardContent>
    </Card>
  );
}
