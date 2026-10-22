"use client";

// Small pieces that make /roi, /usage and /insights read as one family: cross-links and the price-list pager.
// Strings live in the roiFamily namespace (messages/patches/insights-roi-misc.json).
import Link from "next/link";
import PaginationControls from "../components/PaginationControls";
import { hiddenOnPersonalInstall } from "../lib/personalInstall";
import { useTranslations } from "../lib/i18n";
import { usePagination } from "../lib/usePagination";

export function FamilyLinks() {
  const tf = useTranslations("roiFamily");
  return (
    <nav
      aria-label={tf("familyLabel")}
      className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm"
      data-testid="roi-family-links"
    >
      <Link href="/usage" className="text-primary underline-offset-4 hover:underline">
        {tf("linkUsage")}
      </Link>
      <Link href="/insights" className="text-primary underline-offset-4 hover:underline">
        {tf("linkInsights")}
      </Link>
    </nav>
  );
}

export function SeeCostPerAgentLink() {
  const tf = useTranslations("roiFamily");
  // /roi is a billing page a personal install does not have (lib/edition.ts EDITION_ROUTES): no dead link.
  if (hiddenOnPersonalInstall("/roi")) return null;
  return (
    <Link href="/roi" className="text-primary underline-offset-4 hover:underline">
      {tf("seeCostPerAgent")}
    </Link>
  );
}

export interface PagerState {
  page: number;
  totalPages: number;
  totalItems: number;
  pageSize: number;
  setPage: (page: number) => void;
  setPageSize: (size: number) => void;
}

export function usePricePaging<T>(items: T[]) {
  return usePagination(items);
}

export function PricesPager({ pager }: { pager: PagerState }) {
  const tf = useTranslations("roiFamily");
  return (
    <PaginationControls
      page={pager.page}
      totalPages={pager.totalPages}
      totalItems={pager.totalItems}
      pageSize={pager.pageSize}
      onPageChange={pager.setPage}
      onPageSizeChange={pager.setPageSize}
      itemLabel={tf("pricesLabel")}
    />
  );
}
