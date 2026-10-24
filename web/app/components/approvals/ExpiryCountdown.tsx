"use client";

import { Clock } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { expiryDeadlineMs, formatRemaining, URGENT_MS, useNow } from "./approvalExpiry";
import { fmtTime, type ApprovalRequestItem } from "./approvalsModel";
import { isExpiredRequest } from "./approvalsQueueModel";

/**
 * One line of expiry state for a request: a countdown while PENDING (when the workspace expires requests), an
 * "Expired" note once core closed it, nothing otherwise. The countdown is text, so colour is never the only cue.
 */
export default function ExpiryCountdown({
  item,
  ttlSeconds,
  className,
  showWhenNever = false,
}: {
  item: ApprovalRequestItem;
  ttlSeconds: number | null;
  className?: string;
  /** Detail view: say so when requests in this workspace never expire (only when that is known). */
  showWhenNever?: boolean;
}) {
  const t = useTranslations("approvalsQueue");
  const deadline = expiryDeadlineMs(item, ttlSeconds);
  const now = useNow(deadline == null ? null : deadline - Date.now());

  if (isExpiredRequest(item)) {
    return (
      <span className={cn("inline-flex items-center gap-1 text-xs text-muted-foreground", className)} data-testid="expiry-expired">
        <Clock size={12} className="size-3 shrink-0" aria-hidden="true" />
        {t("expiredNote", { time: fmtTime(item.decidedAt) })}
      </span>
    );
  }
  if (item.status !== "PENDING") return null;
  if (deadline == null) {
    if (!showWhenNever || ttlSeconds !== 0) return null;
    return (
      <span className={cn("text-xs text-muted-foreground", className)} data-testid="expiry-never">
        {t("neverExpires")}
      </span>
    );
  }
  const remaining = deadline - now;
  const urgent = remaining < URGENT_MS;
  const exact = new Date(deadline).toLocaleString();
  if (remaining <= 0) {
    return (
      <span
        className={cn("inline-flex items-center gap-1 text-xs font-medium text-destructive", className)}
        title={exact}
        data-testid="expiry-overdue"
      >
        <Clock size={12} className="size-3 shrink-0" aria-hidden="true" />
        {t("overdue")}
      </span>
    );
  }
  return (
    <span
      className={cn("inline-flex items-center gap-1 text-xs", urgent ? "font-medium text-destructive" : "text-muted-foreground", className)}
      title={t("expiresAt", { time: exact })}
      data-testid="expiry-countdown"
      data-urgent={urgent ? "true" : "false"}
    >
      <Clock size={12} className="size-3 shrink-0" aria-hidden="true" />
      {t("expiresIn", { time: formatRemaining(remaining, t) })}
    </span>
  );
}
