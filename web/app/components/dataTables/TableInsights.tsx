"use client";

import { useEffect, useMemo, useState } from "react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { barHeights, formatStat, tableStats, type ColumnStats } from "../../lib/dataTableInsights";
import { buildRowsQuery, type DataTableFilterRow, type DataTableRow, type DataTableSummary } from "../../lib/dataTables";
import { Card, CardContent } from "../ui/card";

/** Rows the profile reads: the API's page-size ceiling (DataTableService.MAX_PAGE_SIZE). */
const SAMPLE = 1000;

/**
 * Column insights: fills, distinct counts, min / mean / max with a small histogram for numbers, true / false split,
 * date range and most common values - computed in the browser from up to 1,000 rows fetched through the ordinary rows
 * endpoint, honouring the filter currently applied to the grid. When the table is larger than the sample, it says so.
 */
export default function TableInsights({ table, filters }: { table: DataTableSummary; filters: DataTableFilterRow[] }) {
  const t = useTranslations("dataTables");
  const [rows, setRows] = useState<DataTableRow[] | null>(null);
  const [total, setTotal] = useState(0);
  const [failed, setFailed] = useState(false);
  const query = buildRowsQuery({ filters, limit: SAMPLE });

  useEffect(() => {
    let live = true;
    setFailed(false);
    api
      .listDataTableRows(table.id, query)
      .then((p) => {
        if (!live) return;
        setRows(p.items);
        setTotal(p.total);
      })
      .catch(() => {
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
    // rowCount: an edit elsewhere on the page that adds or removes rows refreshes the profile.
  }, [table.id, table.rowCount, query]);

  const stats = useMemo(() => (rows ? tableStats(table.columns, rows) : []), [rows, table.columns]);

  return (
    <Card data-testid="data-table-insights">
      <CardContent className="flex flex-col gap-3 pt-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 className="text-sm font-semibold text-foreground">{t("insightsHeading")}</h3>
          {rows && (
            <span className="text-xs text-muted-foreground">
              {total > rows.length ? t("insightsSampled", { shown: rows.length, total }) : t("insightsAll", { count: rows.length })}
            </span>
          )}
        </div>
        {failed && (
          <p className="text-sm text-destructive" role="alert">
            {t("loadFailed")}
          </p>
        )}
        {!rows && !failed && <p className="text-sm text-muted-foreground">{t("loading")}</p>}
        {rows && rows.length === 0 && <p className="text-sm text-muted-foreground">{t("noRows")}</p>}
        {rows && rows.length > 0 && (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {stats.map((s) => (
              <ColumnCard key={s.name} stats={s} />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function ColumnCard({ stats }: { stats: ColumnStats }) {
  const t = useTranslations("dataTables");
  const heights = stats.histogram ? barHeights(stats.histogram) : [];
  const filledTotal = stats.filled + stats.empty;
  const fillPercent = filledTotal === 0 ? 0 : Math.round((stats.filled / filledTotal) * 100);
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-md border border-border p-3" data-testid="data-table-insight-column">
      <div className="flex items-baseline justify-between gap-2">
        <span className="truncate text-sm font-medium text-foreground">{stats.name}</span>
        <span className="text-xs text-muted-foreground">{t(`type_${stats.type}`)}</span>
      </div>
      <div
        className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
        role="img"
        aria-label={t("insightFilled", { filled: stats.filled, total: filledTotal })}
      >
        <div className="h-full bg-primary" style={{ width: `${fillPercent}%` }} />
      </div>
      <p className="text-xs text-muted-foreground">{t("insightFilled", { filled: stats.filled, total: filledTotal })} · {t("insightDistinct", { count: stats.distinct })}</p>

      {stats.type === "number" && stats.min !== undefined && (
        <>
          {heights.length > 0 && (
            <div className="flex h-10 items-end gap-0.5" aria-hidden="true">
              {heights.map((h, i) => (
                <div key={i} className="flex-1 rounded-sm bg-primary/60" style={{ height: `${Math.max(h, 4)}%` }} />
              ))}
            </div>
          )}
          <p className="text-xs text-foreground">
            {t("insightRange", { min: formatStat(stats.min), mean: formatStat(stats.mean), max: formatStat(stats.max) })}
          </p>
          <p className="text-xs text-muted-foreground">{t("insightSum", { sum: formatStat(stats.sum) })}</p>
        </>
      )}

      {stats.type === "boolean" && (
        <p className="text-xs text-foreground">{t("insightBoolean", { yes: stats.trueCount ?? 0, no: stats.falseCount ?? 0 })}</p>
      )}

      {stats.type === "date" && stats.earliest && (
        <p className="text-xs text-foreground">{t("insightDates", { from: stats.earliest.slice(0, 10), to: (stats.latest ?? stats.earliest).slice(0, 10) })}</p>
      )}

      {stats.top && stats.top.length > 0 && (
        <ul className="flex flex-col gap-0.5 text-xs">
          {stats.top.map((v) => (
            <li key={v.value} className="flex items-center justify-between gap-2">
              <span className="truncate text-foreground">{v.value.length > 40 ? `${v.value.slice(0, 40)}…` : v.value}</span>
              <span className="text-muted-foreground">{v.count}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
