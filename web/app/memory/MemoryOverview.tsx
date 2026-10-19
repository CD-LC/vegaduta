"use client";

import { useMemo } from "react";
import { ChartFrame, RankedBars, StackedStatusBar } from "../components/charts";
import { useLocale, useTranslations } from "../lib/i18n";
import type { MemoryFact } from "./memoryTypes";
import { factLabel, memoryStats } from "./memoryStats";

/** Rows in the "most used" ranking; the rest is folded into the kit's "and N more" line. */
const TOP_USED = 5;
const DATE_STYLE: Intl.DateTimeFormatOptions = { day: "numeric", month: "short", year: "numeric" };

function formatDate(iso: string | null, locale: string | undefined): string {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "-";
  // Month as a word: "10/4/2026" reads as 4 October or 10 April depending on the reader.
  try {
    return d.toLocaleDateString(locale || undefined, DATE_STYLE);
  } catch {
    // An app locale tag the browser's Intl rejects: fall back to its own.
    return d.toLocaleDateString(undefined, DATE_STYLE);
  }
}

/**
 * The figures above the facts list on /memory: how the facts split by scope, which ones are actually recalled,
 * how many never were, and how old the collection is.
 *
 * Every number is a total over the user's facts: GET /api/memory is neither capped nor paginated (see
 * memoryStats.ts for the check and for why "never used" is `useCount == 0`). The page's own search / filter does
 * not change this panel - it describes everything remembered, not the filtered view.
 */
export default function MemoryOverview({ facts }: { facts: MemoryFact[] }) {
  const t = useTranslations("memoryPage");
  const locale: string | undefined = useLocale();
  const stats = useMemo(() => memoryStats(facts), [facts]);

  if (stats.total === 0) {
    return (
      <p className="rounded-lg border border-dashed border-border bg-card/40 px-4 py-3 text-sm text-muted-foreground" data-testid="memory-overview-empty">
        {t("overviewEmpty")}
      </p>
    );
  }

  const scopeSummary = t(stats.total === 1 ? "overviewScopeSummaryOne" : "overviewScopeSummary", {
    total: stats.total,
    agent: stats.agent,
    shared: stats.shared,
  });
  const top = stats.used.slice(0, TOP_USED);
  const usedSummary =
    stats.used.length === 0
      ? t("overviewUsedNone")
      : t(stats.used.length === 1 ? "overviewUsedSummaryOne" : "overviewUsedSummary", {
          used: stats.used.length,
          total: stats.total,
          top: stats.used[0].useCount ?? 0,
        });
  const uses = (n: number) => t(n === 1 ? "usageOnce" : "usageMany", { count: n });

  return (
    <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2" data-testid="memory-overview">
      <div className="min-w-0 space-y-4 rounded-lg border border-border bg-card p-4">
        <ChartFrame
          title={t("overviewScopeTitle")}
          summary={scopeSummary}
          table={{
            columns: [
              { key: "scope", header: t("overviewColScope") },
              { key: "count", header: t("overviewColFacts"), align: "right" },
            ],
            rows: [
              { id: "agent", scope: t("overviewScopeAgent"), count: stats.agent },
              { id: "shared", scope: t("overviewScopeShared"), count: stats.shared },
            ],
          }}
        >
          <StackedStatusBar
            showPercent
            segments={[
              { key: "agent", label: t("overviewScopeAgent"), count: stats.agent, state: "running" },
              { key: "shared", label: t("overviewScopeShared"), count: stats.shared, state: "success" },
            ]}
          />
        </ChartFrame>

        <dl className="grid grid-cols-1 gap-x-6 gap-y-1 text-xs text-muted-foreground sm:grid-cols-3" data-testid="memory-overview-facts">
          {stats.usageKnown && (
            <div>
              <dt className="font-medium">{t("overviewNeverUsed")}</dt>
              <dd className="text-sm tabular-nums text-foreground">{t("overviewNeverUsedValue", { count: stats.neverUsed, total: stats.total })}</dd>
            </div>
          )}
          <div>
            <dt className="font-medium">{t("overviewNewest")}</dt>
            <dd className="text-sm tabular-nums text-foreground">{formatDate(stats.newest, locale)}</dd>
          </div>
          <div>
            <dt className="font-medium">{t("overviewOldest")}</dt>
            <dd className="text-sm tabular-nums text-foreground">{formatDate(stats.oldest, locale)}</dd>
          </div>
        </dl>
      </div>

      {stats.usageKnown && (
        <ChartFrame
          className="min-w-0 rounded-lg border border-border bg-card p-4"
          title={t("overviewUsedTitle")}
          summary={usedSummary}
          empty={stats.used.length === 0}
          emptyTitle={t("overviewUsedEmptyTitle")}
          emptyDescription={t("overviewUsedEmptyHint")}
          table={{
            columns: [
              { key: "fact", header: t("overviewColFact") },
              { key: "scope", header: t("overviewColScope") },
              { key: "uses", header: t("overviewColUses"), align: "right" },
            ],
            rows: top.map((f) => ({
              id: `${f.scope}:${f.id}`,
              fact: factLabel(f.content, 120),
              scope: t(f.scope === "shared" ? "overviewScopeShared" : "overviewScopeAgent"),
              uses: f.useCount ?? 0,
            })),
            caption: stats.used.length > top.length ? t("overviewUsedCaption", { shown: top.length, used: stats.used.length }) : undefined,
          }}
        >
          <RankedBars
            sort={false}
            cap={TOP_USED}
            items={stats.used.map((f) => ({
              key: `${f.scope}:${f.id}`,
              label: factLabel(f.content),
              value: f.useCount ?? 0,
              valueText: uses(f.useCount ?? 0),
            }))}
          />
        </ChartFrame>
      )}
    </div>
  );
}
