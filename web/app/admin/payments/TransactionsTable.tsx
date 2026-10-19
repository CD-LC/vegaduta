"use client";

import { Fragment, useMemo, useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import type { PaymentTransaction } from "../../lib/api/payments";
import { fmtPaise } from "../../lib/api/payments";
import PaginationControls from "../../components/PaginationControls";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";
import { FilterPills } from "../commerce/listKit";

/** The transactions endpoint is asked for at most this many newest rows. */
export const TXN_FETCH_LIMIT = 200;

function fmtTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "—";
}

export function txnStatusBadge(status: PaymentTransaction["status"]) {
  if (status === "CAPTURED") return <Badge variant="default">CAPTURED</Badge>;
  if (status === "FAILED" || status === "EXPIRED") return <Badge variant="destructive">{status}</Badge>;
  if (status === "PENDING_APPROVAL" || status === "AWAITING_PAYMENT")
    return <Badge variant="secondary">{status}</Badge>;
  return <Badge variant="outline">{status}</Badge>;
}

/** Server paging state (from usePagedList); omit to page `transactions` in the browser. */
export interface TransactionsPaging {
  page: number;
  pageSize: number;
  total?: number;
  hasMore: boolean;
  loading?: boolean;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
}

export default function TransactionsTable({
  transactions,
  paging,
}: {
  /** All rows (client paging) or just the current page when `paging` is given. */
  transactions: PaymentTransaction[];
  paging?: TransactionsPaging;
}) {
  const t = useTranslations("adminPaymentsPage");
  const tu = useTranslations("adminCommerceUi");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState("");
  const statuses = useMemo(() => Array.from(new Set(transactions.map((x) => x.status))).sort(), [transactions]);
  const filtered = useMemo(
    () => (statusFilter ? transactions.filter((x) => x.status === statusFilter) : transactions),
    [transactions, statusFilter]
  );
  const client = usePagination(filtered);
  // Server mode: the rows are already one page, so the status pills narrow that page only.
  const pg = paging
    ? {
        page: paging.page,
        pageSize: paging.pageSize,
        pageItems: filtered,
        setPage: paging.onPageChange,
      }
    : client;

  return (
    <>
      <div className="mb-3">
        <FilterPills
          options={statuses}
          value={statusFilter}
          onChange={(v) => {
            setStatusFilter(v);
            pg.setPage(1);
          }}
        />
      </div>
      {filtered.length === 0 ? (
        <p className="text-sm text-muted-foreground">{tu("noMatches")}</p>
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("colAmount")}</TableHead>
                <TableHead>{t("colType")}</TableHead>
                <TableHead>{t("colStatus")}</TableHead>
                <TableHead>{t("colVpa")}</TableHead>
                <TableHead>{t("colDescription")}</TableHead>
                <TableHead>{t("colCreated")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pg.pageItems.map((txn) => (
                <Fragment key={txn.id}>
                  <TableRow
                    className="cursor-pointer"
                    onClick={() => setExpanded(expanded === txn.id ? null : txn.id)}
                  >
                    <TableCell className="font-mono">
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        className="h-auto gap-1 px-1 py-0.5 font-mono"
                        aria-expanded={expanded === txn.id}
                        aria-controls={`txn-detail-${txn.id}`}
                        onClick={(e) => {
                          e.stopPropagation();
                          setExpanded(expanded === txn.id ? null : txn.id);
                        }}
                      >
                        {expanded === txn.id ? (
                          <ChevronUp size={14} aria-hidden="true" />
                        ) : (
                          <ChevronDown size={14} aria-hidden="true" />
                        )}
                        {fmtPaise(txn.amountPaise)}
                      </Button>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{txn.type}</Badge>
                    </TableCell>
                    <TableCell>{txnStatusBadge(txn.status)}</TableCell>
                    <TableCell className="font-mono text-xs">{txn.vpa || "—"}</TableCell>
                    <TableCell>{txn.description || "—"}</TableCell>
                    <TableCell className="text-xs text-muted-foreground">{fmtTime(txn.createdAt)}</TableCell>
                  </TableRow>
                  {expanded === txn.id && (
                    <TableRow id={`txn-detail-${txn.id}`} className="bg-secondary/20">
                      <TableCell colSpan={6} className="text-xs">
                        <div className="grid gap-1 font-mono">
                          <div>
                            {t("detailId")}: {txn.id}
                          </div>
                          <div>
                            {t("detailMandate")}: {txn.mandateId}
                          </div>
                          {txn.gatewayPaymentId && (
                            <div>
                              {t("detailGatewayPayment")}: {txn.gatewayPaymentId}
                            </div>
                          )}
                          {txn.sessionId && (
                            <div>
                              {t("detailSession")}: {txn.sessionId}
                            </div>
                          )}
                          {txn.approvalId && (
                            <div>
                              {t("detailApproval")}: {txn.approvalId}
                            </div>
                          )}
                          {txn.failureReason && (
                            <div>
                              {t("detailFailure")}: {txn.failureReason}
                            </div>
                          )}
                          {txn.paidAt && (
                            <div>
                              {t("detailPaidAt")}: {fmtTime(txn.paidAt)}
                            </div>
                          )}
                          {txn.expiresAt && (
                            <div>
                              {t("detailExpiresAt")}: {fmtTime(txn.expiresAt)}
                            </div>
                          )}
                        </div>
                        <div className="mt-2 flex gap-2">
                          {txn.shortUrl && (
                            <a href={txn.shortUrl} target="_blank" rel="noreferrer" className="text-primary underline">
                              {t("openPaymentLink")}
                            </a>
                          )}
                          {txn.receiptUrl && (
                            <a href={txn.receiptUrl} target="_blank" rel="noreferrer" className="text-primary underline">
                              {t("openReceipt")}
                            </a>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <div className="mt-3">
        <PaginationControls
          page={pg.page}
          pageSize={pg.pageSize}
          onPageChange={pg.setPage}
          itemLabel={tu("itemTransactions")}
          {...(paging
            ? {
                totalItems: paging.total,
                hasMore: paging.hasMore,
                pageItemCount: transactions.length,
                loading: paging.loading,
                onPageSizeChange: paging.onPageSizeChange,
              }
            : {
                totalPages: client.totalPages,
                totalItems: client.totalItems,
                onPageSizeChange: client.setPageSize,
                capped: TXN_FETCH_LIMIT,
              })}
        />
      </div>
    </>
  );
}
