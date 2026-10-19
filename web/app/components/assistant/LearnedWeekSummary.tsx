"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { assistantApi, type LearningWeekSummary } from "../../lib/api/assistant";

/**
 * "What I learned this week", the top line of the Learned section (daily memory review). It reads its own
 * summary and re-reads when `refreshKey` changes (the number of suggestions waiting moves after every
 * approve or reject). Renders nothing while the review is off or unreachable, so a stack without it stays quiet.
 */
export default function LearnedWeekSummary({ refreshKey }: { refreshKey: unknown }) {
  const t = useTranslations("assistant");
  const [summary, setSummary] = useState<LearningWeekSummary | null>(null);

  useEffect(() => {
    let live = true;
    void assistantApi
      .getLearningWeekSummaryQuiet()
      .catch((): null => null)
      .then((next) => {
        if (live) setSummary(next && typeof next.approved === "number" ? next : null);
      });
    return () => {
      live = false;
    };
  }, [refreshKey]);

  if (!summary) return null;
  return (
    <div className="mt-1 rounded-lg bg-muted/40 px-2.5 py-2 text-xs" data-testid="learned-week-summary">
      <p className="font-medium text-foreground">{t("learnedWeekTitle")}</p>
      {summary.approved === 0 ? (
        <p className="text-muted-foreground">{t("learnedWeekNone")}</p>
      ) : (
        <p className="text-muted-foreground">
          {t("learnedWeekLearned", { n: summary.approved })}
          {summary.tidied > 0 && ` ${t("learnedWeekTidied", { n: summary.tidied })}`}
        </p>
      )}
    </div>
  );
}
