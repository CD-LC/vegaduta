"use client";

import { useTranslations } from "../../lib/i18n";
import type { DataTableSummary, DataTablesConfig } from "../../lib/dataTables";
import { ChartFrame } from "../charts";
import { formatPercent } from "../charts/chartMath";
import CapacityMeter from "./CapacityMeter";
import { capacity, capacityOverview, type CapacityLevel } from "./capacity";

const LEVEL_KEY: Record<CapacityLevel, string> = {
  ok: "capacityOk",
  near: "capacityNear",
  full: "capacityFull",
  unknown: "capacityUnknown",
};

/**
 * How much of the Data Tables allowance is used: tables against `maxTables`, and (in the table view) every table's
 * rows against `maxRowsPerTable`. Both come straight from the list and config endpoints, which are exact, so these
 * are totals. The per-table meters themselves sit on the rows of the table list; this frame carries the sentence
 * and the table fallback for all of them.
 */
export default function DataTableCapacity({ tables, config }: { tables: DataTableSummary[]; config: DataTablesConfig }) {
  const t = useTranslations("dataTables");
  if (tables.length === 0) return null;

  const overview = capacityOverview(
    tables.map((x) => x.rowCount),
    config
  );
  const num = (n: number) => n.toLocaleString();
  const tablesSentence =
    overview.tables.rate === null
      ? t("capacityTablesNoLimit", { count: tables.length })
      : t("capacityTables", { count: tables.length, max: num(config.maxTables) });
  const rowsSentence =
    config.maxRowsPerTable <= 0
      ? ""
      : overview.nearCount + overview.fullCount === 0
        ? t("capacityRowsOk", { max: num(config.maxRowsPerTable) })
        : t("capacityRowsFlagged", { near: overview.nearCount, full: overview.fullCount, max: num(config.maxRowsPerTable) });
  const summary = rowsSentence ? `${tablesSentence} ${rowsSentence}` : tablesSentence;

  return (
    <ChartFrame
      className="min-w-0 rounded-lg border border-border bg-card p-4"
      title={t("capacityTitle")}
      summary={summary}
      table={{
        columns: [
          { key: "name", header: t("capacityColTable") },
          { key: "rows", header: t("capacityColRows"), align: "right" },
          { key: "limit", header: t("capacityColLimit"), align: "right" },
          { key: "used", header: t("capacityColUsed"), align: "right" },
          { key: "status", header: t("capacityColStatus") },
        ],
        rows: tables.map((x) => {
          const c = capacity(x.rowCount, config.maxRowsPerTable);
          return {
            id: x.id,
            name: x.name,
            rows: num(x.rowCount),
            limit: c.rate === null ? "-" : num(config.maxRowsPerTable),
            used: formatPercent(c.rate, c.rate !== null && c.rate > 0 && c.rate < 0.01 ? 2 : 0) ?? "-",
            status: t(LEVEL_KEY[c.level]),
          };
        }),
      }}
    >
      {overview.tables.rate === null ? null : (
        <div className="min-w-0 max-w-md space-y-1" data-testid="data-tables-capacity-meter">
          <CapacityMeter value={overview.tables} label={tablesSentence} nearLabel={t("capacityNear")} fullLabel={t("capacityFull")} />
          <p className="text-xs text-muted-foreground">
            {t("capacityTablesFree", { count: Math.max(0, config.maxTables - tables.length) })}
          </p>
        </div>
      )}
    </ChartFrame>
  );
}
