"use client";

// The per-agent cost/ROI table: search, client-side paging and a phone-friendly column set.
import { useMemo, useState } from "react";
import { AlertTriangle, Gauge, Pencil, Search } from "lucide-react";
import { EmptyState } from "../components/ax/page-kit";
import PaginationControls from "../components/PaginationControls";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../components/ui/table";
import type { AgentRoiResponseDto, AgentRoiRowDto } from "../lib/api/roi";
import { useTranslations } from "../lib/i18n";
import { usePagination } from "../lib/usePagination";
import { DASH, formatCharges, formatCount, formatMoney, formatPercentFromRatio, formatRoiPercent, formatUsd, roiNoteKey, toNumber, totals } from "./roiModel";

type T = (key: string, values?: Record<string, string | number>) => string;

function CostCell({ row, t }: { row: AgentRoiRowDto; t: T }) {
  const text = formatUsd(row.estimatedCostUsd);
  return (
    <span title={row.costComplete ? t("costEstimatedHint") : t("costIncompleteHint")}>
      {row.costComplete || text === DASH ? text : `≥ ${text}`}
      {!row.costComplete && <AlertTriangle className="ml-1 inline h-3.5 w-3.5 align-text-bottom" aria-label={t("costIncompleteHint")} />}
      {toNumber(row.platformHostedEstimatedCostUsd) ? (
        <span className="block text-xs text-muted-foreground" data-testid="roi-cost-split">
          {t("costSplit", { byok: formatUsd(row.byokEstimatedCostUsd), pool: formatUsd(row.platformHostedEstimatedCostUsd) })}
        </span>
      ) : null}
    </span>
  );
}

function RoiCell({ row, basis, t }: { row: AgentRoiRowDto; basis: string; t: T }) {
  const noteKey = roiNoteKey(row.roiNote);
  if (toNumber(row.roiPercent) === null && toNumber(row.valueTotal) === null && toNumber(row.minutesSavedTotal) === null) {
    return <span className="text-muted-foreground" data-testid="roi-cell-none">{noteKey ? t(noteKey) : DASH}</span>;
  }
  return (
    <div className="space-y-0.5" data-testid="roi-cell">
      <div className="font-medium">{formatRoiPercent(row.roiPercent)}</div>
      {toNumber(row.netValue) !== null && <div className="text-xs">{t("netValue", { value: formatMoney(row.netValue, row.valueCurrency) })}</div>}
      {toNumber(row.minutesSavedTotal) !== null && <div className="text-xs">{t("minutesSaved", { minutes: formatCount(Math.round(toNumber(row.minutesSavedTotal) ?? 0)) })}</div>}
      <div className="text-xs text-muted-foreground">{basis}{noteKey ? ` · ${t(noteKey)}` : ""}</div>
    </div>
  );
}

export default function AgentTable({ table, t, mayEdit, onEdit }: { table: AgentRoiResponseDto; t: T; mayEdit: boolean; onEdit: (r: AgentRoiRowDto) => void }) {
  const tf = useTranslations("roiFamily");
  const sum = useMemo(() => totals(table.agents), [table.agents]);
  const [query, setQuery] = useState("");
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? table.agents.filter((r) => r.agentName.toLowerCase().includes(q)) : table.agents;
  }, [table.agents, query]);
  const pg = usePagination(filtered);
  if (table.agents.length === 0) {
    return <EmptyState icon={Gauge} title={t("emptyTitle")} description={t("emptyBody")} />;
  }
  const num = "text-right align-top";
  // Secondary rate columns drop out below lg so the table fits a phone without a wide scroll.
  const rate = `${num} hidden lg:table-cell`;
  return (
    <div className="min-w-0 space-y-3">
      <div className="relative max-w-sm">
        <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" aria-hidden="true" />
        <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={tf("searchAgents")} aria-label={tf("searchAgents")} className="pl-8" data-testid="roi-agent-search" />
      </div>
      <div className="min-w-0 rounded-xl border border-border">
        <Table data-testid="roi-table">
          <caption className="sr-only">{t("tableTitle")}</caption>
          <TableHeader>
            <TableRow>
              <TableHead scope="col" className="sticky left-0 bg-card">{t("colAgent")}</TableHead>
              <TableHead scope="col" className="text-right">{t("colConversations")}</TableHead>
              <TableHead scope="col" className="text-right">{t("colResolved")}</TableHead>
              <TableHead scope="col" className="text-right">{t("colCost")}</TableHead>
              <TableHead scope="col" className="hidden text-right lg:table-cell">{t("colCostPerResolved")}</TableHead>
              <TableHead scope="col" className="hidden text-right lg:table-cell">{t("colCharges")}</TableHead>
              <TableHead scope="col" className="hidden text-right lg:table-cell">{t("colHandover")}</TableHead>
              <TableHead scope="col" className="hidden text-right lg:table-cell">{t("colCorrection")}</TableHead>
              <TableHead scope="col" className="hidden text-right lg:table-cell">{t("colDenial")}</TableHead>
              <TableHead scope="col">{t("colRoi")}</TableHead>
              {mayEdit && <TableHead scope="col"><span className="sr-only">{t("colActions")}</span></TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {pg.pageItems.map((row) => (
              <TableRow key={row.agentId} data-testid={`roi-row-${row.agentId}`}>
                <TableCell className="sticky left-0 bg-card align-top font-medium">{row.agentName}</TableCell>
                <TableCell className={num}>{formatCount(row.conversations)}</TableCell>
                <TableCell className={num}>
                  {formatCount(row.resolved)}
                  {row.resolvedAssumed > 0 && !table.includeAssumedResolutions && (
                    <div className="text-xs text-muted-foreground">{t("resolvedAssumedNote", { count: formatCount(row.resolvedAssumed) })}</div>
                  )}
                </TableCell>
                <TableCell className={num}><CostCell row={row} t={t} /></TableCell>
                <TableCell className={rate}>{formatUsd(row.estimatedCostPerResolvedUsd, 4)}</TableCell>
                <TableCell className={rate} data-testid="roi-charges">{formatCharges(row.platformChargesMinor, row.platformChargesCurrency)}</TableCell>
                <TableCell className={rate}>{formatPercentFromRatio(row.handoverRate)}</TableCell>
                <TableCell className={rate}>{formatPercentFromRatio(row.humanCorrectionRate)}</TableCell>
                <TableCell className={rate}>{formatPercentFromRatio(row.approvalDenialRate)}</TableCell>
                <TableCell className="align-top"><RoiCell row={row} basis={table.roiBasis} t={t} /></TableCell>
                {mayEdit && (
                  <TableCell className="align-top">
                    <Button type="button" variant="ghost" size="sm" onClick={() => onEdit(row)} aria-label={t("editAssumptionsFor", { agent: row.agentName })}>
                      <Pencil className="h-4 w-4" aria-hidden="true" />
                    </Button>
                  </TableCell>
                )}
              </TableRow>
            ))}
            {filtered.length === 0 && (
              <TableRow><TableCell colSpan={mayEdit ? 11 : 10} className="text-center text-muted-foreground">{tf("noMatch")}</TableCell></TableRow>
            )}
          </TableBody>
          <tfoot className="border-t border-border bg-muted/20">
            <TableRow data-testid="roi-totals">
              <TableCell className="sticky left-0 bg-card font-medium">{t("totals")}</TableCell>
              <TableCell className={num}>{formatCount(sum.conversations)}</TableCell>
              <TableCell className={num}>{formatCount(sum.resolved)}</TableCell>
              <TableCell className={num}>{sum.costComplete ? "" : "≥ "}{formatUsd(sum.estimatedCostUsd)}</TableCell>
              <TableCell className={`${rate}`}>{sum.resolved > 0 ? formatUsd(sum.estimatedCostUsd / sum.resolved, 4) : DASH}</TableCell>
              <TableCell colSpan={mayEdit ? 6 : 5} className="text-xs font-normal text-muted-foreground">{t("tableFootnote")}</TableCell>
            </TableRow>
          </tfoot>
        </Table>
      </div>
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel={tf("agentsLabel")}
      />
    </div>
  );
}

