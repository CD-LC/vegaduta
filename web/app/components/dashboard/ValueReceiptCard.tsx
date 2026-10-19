"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import type { TenantUsageQuotaResponse } from "../../lib/api";
import { Card } from "../ui/card";
import { useTranslations } from "../../lib/i18n";
import { Sparkles } from "lucide-react";

// D2-lite value receipt (docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md):
// a compact "here's what your agents did for you this month" card for the
// console home screens (both / for members and /admin for tenant admins -
// admins are redirected off / so mounting on one page alone would miss half
// the audience). Fed ENTIRELY from the already-deployed
// GET /api/tenant/usage-quota (tokensUsedMonth) - no new backend dependency,
// no fabricated numbers. 2026-09-26: the quota meter went monthly-tokens-
// only, so this card now reports token volume rather than the retired
// message counter.
//
// Hide-don't-degrade rules, in order:
//   - fetch failed (older core, 403, network)  -> render nothing
//   - tokensUsedMonth missing from the body    -> render nothing
//   - zero usage this month                    -> render nothing (a "your
//     agents used 0 tokens" receipt is an anti-receipt; the dashboard's own
//     empty states already own the cold-start story)
export default function ValueReceiptCard() {
  const t = useTranslations("valueReceipt");
  const [quota, setQuota] = useState<TenantUsageQuotaResponse | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getUsageQuota()
      .then((q) => {
        if (!cancelled) setQuota(q);
      })
      .catch(() => {
        /* unavailable -> card stays hidden */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const tokens = typeof quota?.tokensUsedMonth === "number" ? quota.tokensUsedMonth : null;
  if (tokens === null || tokens <= 0) return null;

  return (
    <Card className="mb-0 gap-2 p-5">
      <div className="flex items-center gap-2">
        <Sparkles className="size-4 text-primary" aria-hidden="true" />
        <span className="font-mono text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          {t("eyebrow")}
        </span>
      </div>
      <div className="text-sm font-medium text-foreground">
        {t("headlineTokens", { tokens: tokens.toLocaleString("en-US") })}
      </div>
    </Card>
  );
}
