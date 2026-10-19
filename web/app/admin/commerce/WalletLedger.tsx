"use client";

import type { WalletLedgerEntry } from "../../lib/api/agentTrust";
import PaginationControls from "../../components/PaginationControls";
import { Badge } from "../../components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import { useTranslations } from "../../lib/i18n";
import { usePagedList } from "../issues/pagedLoad";
import { ErrorRetry, ListSkeleton } from "./listKit";

// Older cores return at most the newest 200 ledger rows and cannot page; the hook then pages those in the browser.
const LEDGER_LEGACY_LIMIT = 200;

function fmtTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "—";
}

/** One wallet's ledger, fetched a page at a time (limit/offset + X-Total-Count). */
export default function WalletLedger({ walletId }: { walletId: string }) {
  const t = useTranslations("adminCommercePage");
  const tu = useTranslations("adminCommerceUi");
  const list = usePagedList<WalletLedgerEntry>(
    `/api/wallets/${encodeURIComponent(walletId)}/ledger`,
    LEDGER_LEGACY_LIMIT
  );

  if (list.error && list.rows.length === 0) {
    return <ErrorRetry message={list.error} onRetry={() => void list.reload()} className="mb-0" />;
  }
  if (list.loading && list.rows.length === 0) return <ListSkeleton rows={2} />;
  if (list.rows.length === 0) return <p className="text-xs text-muted-foreground">{t("ledgerEmpty")}</p>;

  return (
    <>
      <div className="overflow-x-auto">
        <Table className="text-xs">
          <TableHeader>
            <TableRow>
              <TableHead>{t("colKind")}</TableHead>
              <TableHead>{t("colAmount")}</TableHead>
              <TableHead>{t("colBalanceAfter")}</TableHead>
              <TableHead>{t("colReference")}</TableHead>
              <TableHead>{t("colCreated")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.rows.map((e) => (
              <TableRow key={e.id}>
                <TableCell>
                  <Badge variant="outline">{e.entryKind}</Badge>
                </TableCell>
                <TableCell className="font-mono">{e.amountMinor}</TableCell>
                <TableCell className="font-mono">{e.balanceAfter}</TableCell>
                <TableCell className="font-mono">
                  {e.referenceType ? `${e.referenceType}:${e.referenceId ?? ""}` : e.idempotencyKey}
                </TableCell>
                <TableCell>{fmtTime(e.createdAt)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <div className="mt-2">
        <PaginationControls
          page={list.page}
          totalItems={list.total}
          hasMore={list.hasMore}
          pageItemCount={list.rows.length}
          pageSize={list.pageSize}
          onPageChange={list.setPage}
          onPageSizeChange={list.setPageSize}
          itemLabel={tu("itemLedger")}
          loading={list.loading}
        />
      </div>
    </>
  );
}
