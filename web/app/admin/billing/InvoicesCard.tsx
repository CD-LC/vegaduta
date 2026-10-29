"use client";

import Link from "next/link";
import { FileText as DocumentTextIcon, ExternalLink as ArrowTopRightOnSquareIcon } from "lucide-react";
import type { TenantInvoiceSummary } from "../../lib/api";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { EmptyState } from "../../components/ax/page-kit";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { useTranslations } from "../../lib/i18n";

// Field-name probing for the not-yet-final invoice wire shape (see
// TenantInvoiceSummary in api.ts).
const invoiceNumber = (inv: TenantInvoiceSummary) =>
  inv.invoiceNumber || inv.number || (typeof inv.id === "string" ? inv.id.slice(0, 8).toUpperCase() : "—");

const invoiceDate = (inv: TenantInvoiceSummary) => {
  const raw = inv.invoiceDate || inv.date || inv.createdAt;
  if (!raw) return "—";
  const d = new Date(raw);
  return isNaN(d.getTime()) ? String(raw) : d.toLocaleDateString();
};

const invoiceAmount = (inv: TenantInvoiceSummary) => {
  if (inv.amount === null || inv.amount === undefined || inv.amount === "") return "—";
  const n = Number(inv.amount);
  const amount = isNaN(n)
    ? String(inv.amount)
    : n.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return inv.currency ? `${amount} ${inv.currency}` : amount;
};

/** Invoice history (client-paged) followed by `children` (the billing profile form). */
export default function InvoicesCard({
  invoices,
  onOpenInvoice,
  children,
}: {
  invoices: TenantInvoiceSummary[];
  onOpenInvoice: (id: string) => void;
  children?: React.ReactNode;
}) {
  const t = useTranslations("adminBillingPage");
  const x = useTranslations("adminBillingExtra");
  const pg = usePagination(invoices);
  return (
    <Card className="mb-4 gap-0 p-6">
      <div className="mb-1 flex items-center gap-2">
        <DocumentTextIcon width={15} height={15} aria-hidden="true" />
        <h2 className="m-0 text-[0.95rem] font-semibold">{t("invoicesTitle")}</h2>
      </div>
      {invoices.length === 0 ? (
        <EmptyState
          icon={DocumentTextIcon}
          title={x("noInvoicesTitle")}
          description={x("noInvoicesDescription")}
          className="mt-3 py-8"
          action={
            <div className="flex flex-wrap items-center justify-center gap-3">
              <Button asChild size="sm" variant="outline">
                <Link href="/features">{x("comparePlans")}</Link>
              </Button>
              <Link href="/help#admin" className="text-sm font-medium text-primary underline-offset-4 hover:underline">
                {x("learnBilling")}
              </Link>
            </div>
          }
        />
      ) : (
        <div className="mt-2">
          {pg.pageItems.map((inv, i) => (
            <button
              key={inv.id || i}
              type="button"
              onClick={() => inv.id && onOpenInvoice(inv.id)}
              title={t("invoiceOpenHint")}
              className={`flex w-full cursor-pointer items-center gap-3 bg-transparent px-1 py-2.5 text-start text-[0.8rem] text-foreground outline-none hover:bg-accent/40 focus-visible:ring-[3px] focus-visible:ring-ring ${
                i === 0 ? "" : "border-t border-border"
              }`}
            >
              <span className="whitespace-nowrap font-mono text-muted-foreground">{invoiceDate(inv)}</span>
              <span className="min-w-0 truncate font-mono">{invoiceNumber(inv)}</span>
              <span className="ms-auto whitespace-nowrap font-mono">{invoiceAmount(inv)}</span>
              <ArrowTopRightOnSquareIcon
                aria-hidden="true"
                width={13}
                height={13}
                className="shrink-0 text-muted-foreground"
              />
            </button>
          ))}
          <PaginationControls
            page={pg.page}
            totalPages={pg.totalPages}
            totalItems={pg.totalItems}
            pageSize={pg.pageSize}
            onPageChange={pg.setPage}
            onPageSizeChange={pg.setPageSize}
            itemLabel={t("itemLabelInvoices")}
          />
        </div>
      )}
      {children}
    </Card>
  );
}
