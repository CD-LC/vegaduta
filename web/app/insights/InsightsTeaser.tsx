"use client";

// Home-page teaser for /insights: the last 7 days of AI calls as one small bar
// chart plus a "See insights" link. Mounted on both home screens (the member
// dashboard and /admin - tenant admins are redirected off the member one).
//
// Hide-don't-degrade, same posture as ValueReceiptCard:
//   - no admin role and no VIEW_USAGE permission -> nothing, and no request
//   - the call fails for any reason (403, older core, network) -> nothing
//   - no calls in the window                      -> nothing (the dashboard's
//     own first-run cards own the cold start)
// It never shows an error of its own. Numbers come from the same model as the
// Insights page, so the two can not disagree.
import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "../components/AuthProvider";
import { ChartFrame, MiniBars } from "../components/charts";
import { Card } from "../components/ui/card";
import { insightsApi, type DayUsageDto, type SpendSummaryDto } from "../lib/api/insights";
import { useTranslations } from "../lib/i18n";
import { buildTeaser, formatCount, rangeForPeriod } from "./insightsModel";

export default function InsightsTeaser({ className }: { className?: string }) {
  const t = useTranslations("insights");
  const { isAdmin, hasPermission } = useAuth();
  const allowed = isAdmin || hasPermission("VIEW_USAGE");
  const [data, setData] = useState<{ summary: SpendSummaryDto; byDay: DayUsageDto[] } | null>(null);

  useEffect(() => {
    if (!allowed) return undefined;
    let cancelled = false;
    const range = rangeForPeriod(7);
    void (async () => {
      try {
        const summary = await insightsApi.getSpendSummary(range);
        const byDay = await insightsApi.getSpendByDay(range);
        if (!cancelled) setData({ summary, byDay });
      } catch {
        /* unavailable -> the teaser stays hidden */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [allowed]);

  if (!allowed || !data) return null;
  const teaser = buildTeaser(data.summary, data.byDay);
  if (!teaser) return null;

  const soFar = t("soFar");
  return (
    <Card className={className ?? "min-w-0 gap-0 p-5"} data-testid="insights-teaser">
      <ChartFrame
        title={t("teaserTitle")}
        summary={t(teaser.summary.key, teaser.summary.values)}
        periodSwitch={
          <Link href="/insights" className="text-sm font-medium text-primary underline-offset-4 hover:underline">
            {t("teaserLink")}
          </Link>
        }
      >
        <MiniBars
          height={64}
          data={teaser.series.days.map((day) => ({
            label: day.label,
            value: day.calls,
            valueText: t(day.calls === 1 ? "callsValueOne" : "callsValue", { count: formatCount(day.calls) }),
          }))}
          partialLast={teaser.series.partialLast}
          partialLabel={soFar}
        />
      </ChartFrame>
    </Card>
  );
}
