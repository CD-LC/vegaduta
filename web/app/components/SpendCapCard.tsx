"use client";

import { useEffect, useState } from "react";
import { Wallet } from "lucide-react";
import { api } from "../lib/api";
import { useLocale, useTranslations } from "../lib/i18n";
import { Card, CardContent } from "./ui/card";
import { Badge } from "./ui/badge";
import {
  capUsageFraction,
  formatCapUsd,
  formatResetDate,
  nextMonthStartIso,
  type TenantSpendCap,
} from "../lib/spendCap";

// Read-only view of the tenant's monthly hosted-LLM spend cap
// (GET /api/tenant/spend-cap; set by a platform admin in web-admin). Renders
// nothing unless a cap is set: no cap, a non-admin's 403, an older core's 404
// or any failure all simply hide the card.
export default function SpendCapCard() {
  const t = useTranslations("usage");
  const locale = useLocale();
  const [cap, setCap] = useState<TenantSpendCap | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getTenantSpendCap()
      .then((c: TenantSpendCap | null) => {
        if (!cancelled) setCap(c && c.capUsd != null ? c : null);
      })
      .catch(() => {
        if (!cancelled) setCap(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!cap || cap.capUsd == null) return null;

  const fraction = capUsageFraction(cap);
  const percent =
    typeof cap.percentUsed === "number" && Number.isFinite(cap.percentUsed)
      ? Math.round(cap.percentUsed)
      : fraction != null
        ? Math.round(fraction * 100)
        : null;
  const width = percent == null ? 0 : Math.min(100, Math.max(0, percent));
  const resetDate = formatResetDate(nextMonthStartIso(cap.monthStart), locale);
  const tone =
    cap.blocked || cap.alertStage >= 100
      ? "var(--destructive)"
      : cap.alertStage >= 80
        ? "var(--upgrade)"
        : "var(--primary)";

  return (
    <Card data-testid="usage-spend-cap">
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
          <span
            className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-border bg-muted/40"
            style={{ color: tone }}
          >
            <Wallet className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1 space-y-0.5">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="text-sm font-semibold text-foreground">{t("capTitle")}</h3>
              {cap.blocked ? (
                <Badge variant="destructive">{t("capBlocked")}</Badge>
              ) : cap.alertStage >= 80 ? (
                <Badge variant="outline" style={{ borderColor: tone, color: tone }}>
                  {t("capAlert80")}
                </Badge>
              ) : null}
            </div>
            <p className="text-sm text-muted-foreground">{t("capSubtitle")}</p>
          </div>
        </div>
        <div className="space-y-1.5">
          <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
            <span className="font-medium text-foreground">
              {t("capUsedOf", { used: formatCapUsd(cap.monthToDateUsd), cap: formatCapUsd(cap.capUsd) })}
            </span>
            {percent != null && <span className="font-mono text-muted-foreground">{t("capPercent", { percent })}</span>}
          </div>
          <div
            className="relative h-2 w-full overflow-hidden rounded-full bg-muted"
            role="meter"
            aria-label={t("capTitle")}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={width}
          >
            <div className="h-full rounded-full" style={{ width: `${width}%`, backgroundColor: tone }} />
            <div
              className="absolute inset-y-0 w-px bg-foreground/50"
              style={{ left: "80%" }}
              title={t("capMarker")}
              aria-hidden="true"
            />
          </div>
          {cap.blocked ? (
            <p className="text-xs font-medium" style={{ color: "var(--destructive)" }}>
              {t("capBlockedNote", { date: resetDate || "" })}
            </p>
          ) : (
            resetDate && <p className="text-xs text-muted-foreground">{t("capResets", { date: resetDate })}</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
