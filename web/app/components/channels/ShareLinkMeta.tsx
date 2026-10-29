"use client";

import { useEffect, useRef, useState } from "react";
import { Flag } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

/** The backend revokes a public share link automatically at this many abuse reports. */
export const SHARE_REPORT_LIMIT = 3;

export interface ShareStatusLike {
  active: boolean;
  viewerCount: number;
  reportCount: number;
  expiresAt?: string | null;
}

export type ShareEndReason = "reports" | "expired" | "ended" | null;

/**
 * Why a link that was active a moment ago is not any more. The status of an inactive link carries no reason
 * (the API answers a bare "inactive"), so this infers it from the last active status seen in this session:
 * past expiry means expired, one or more reports means the abuse-report auto-revoke may have fired.
 * An owner-initiated revoke is cleared by the panel (see `markOwnerRevoked`).
 */
export function inferShareEndReason(prev: ShareStatusLike | null, now: Date = new Date()): ShareEndReason {
  if (!prev || !prev.active) return null;
  if (prev.expiresAt && new Date(prev.expiresAt).getTime() <= now.getTime()) return "expired";
  if (prev.reportCount > 0) return "reports";
  return "ended";
}

/** Tracks the last active status and re-reads the status when the tab regains focus. */
export function useShareEndReason(status: ShareStatusLike | null, refetch: () => void) {
  const lastActive = useRef<ShareStatusLike | null>(null);
  const [reason, setReason] = useState<ShareEndReason>(null);

  useEffect(() => {
    if (!status) return;
    if (status.active) {
      lastActive.current = status;
      setReason(null);
    } else if (lastActive.current) {
      setReason(inferShareEndReason(lastActive.current));
      lastActive.current = null;
    }
  }, [status]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible") refetch();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [refetch]);

  return {
    reason,
    /** Call after the owner revokes by hand so that is not reported as an automatic end. */
    markOwnerRevoked: () => {
      lastActive.current = null;
      setReason(null);
    },
  };
}

/** Expiry and abuse-report lines for an active share link. */
export function ShareLinkMeta({ status }: { status: ShareStatusLike }) {
  const t = useTranslations("channelsUsageMcp");
  const expires = status.expiresAt ? new Date(status.expiresAt) : null;
  return (
    <div className="space-y-1 text-xs text-muted-foreground" data-testid="share-meta">
      <p data-testid="share-expiry">
        {expires ? t("shareExpiresOn", { date: expires.toLocaleString() }) : t("shareNoExpiry")}
      </p>
      <p>{t("shareViewers", { count: String(status.viewerCount || 0) })}</p>
      {status.reportCount > 0 && (
        <p className="text-[var(--state-warning)]" data-testid="share-reports">
          <Flag size={10} className="me-1 inline" aria-hidden="true" />
          {t("shareReports", { count: String(status.reportCount), limit: String(SHARE_REPORT_LIMIT) })}
        </p>
      )}
    </div>
  );
}

/** Explains why a link that was shared is no longer active. Renders nothing when there is no inferred reason. */
export function ShareEndNotice({ reason }: { reason: ShareEndReason }) {
  const t = useTranslations("channelsUsageMcp");
  if (!reason) return null;
  const key =
    reason === "reports" ? "shareEndedReports" : reason === "expired" ? "shareEndedExpired" : "shareEndedOther";
  return (
    <p
      role="status"
      className="rounded-md border border-[var(--warning-border)] bg-[var(--warning-bg)] p-2 text-xs text-[var(--warning-text)]"
      data-testid="share-end-notice"
    >
      {t(key, { limit: String(SHARE_REPORT_LIMIT) })}
    </p>
  );
}
