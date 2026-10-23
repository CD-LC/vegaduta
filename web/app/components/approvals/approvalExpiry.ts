"use client";

// Expiry maths and hooks for pending approvals. Core closes a request that nobody decided within
// app.approvals.expiry.ttl (0 = never), measured from its creation; the response carries no expiry timestamp, so
// the deadline is createdAt + the effective TTL (or `expiresAt` if a newer core sends one).
import { useEffect, useState } from "react";
import { approvalsQueueApi } from "../../lib/api/approvalsQueue";
import type { ApprovalRequestItem, Translate } from "./approvalsModel";

/** The instant a PENDING request will be closed, or null when it never is / cannot be known. */
export function expiryDeadlineMs(
  item: Pick<ApprovalRequestItem, "status" | "createdAt"> & { expiresAt?: string | null },
  ttlSeconds: number | null
): number | null {
  if (item.status !== "PENDING") return null;
  if (item.expiresAt) {
    const direct = new Date(item.expiresAt).getTime();
    if (Number.isFinite(direct)) return direct;
  }
  if (ttlSeconds == null || ttlSeconds <= 0 || !item.createdAt) return null;
  const created = new Date(item.createdAt).getTime();
  return Number.isFinite(created) ? created + ttlSeconds * 1000 : null;
}

/** "3d 4h", "2h 5m", "12m", "40s" - two units at most, via i18n so the letters can be localised. */
export function formatRemaining(ms: number, t: Translate): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(total / 86_400);
  const h = Math.floor((total % 86_400) / 3_600);
  const m = Math.floor((total % 3_600) / 60);
  const s = total % 60;
  if (d > 0) return t("durationDaysHours", { d, h });
  if (h > 0) return t("durationHoursMinutes", { h, m });
  if (m > 0) return t("durationMinutes", { m });
  return t("durationSeconds", { s });
}

/** Under this much time left a countdown is shown as urgent. */
export const URGENT_MS = 15 * 60_000;

/** A clock that ticks while mounted: every second inside the last two minutes, every 15 s otherwise. */
export function useNow(fastWhenBelowMs?: number | null): number {
  const [now, setNow] = useState(() => Date.now());
  const fast = fastWhenBelowMs != null && fastWhenBelowMs < 120_000;
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), fast ? 1_000 : 15_000);
    return () => clearInterval(id);
  }, [fast]);
  return now;
}

/**
 * The workspace's pending-request lifetime in seconds: 0 = never expires, null = not known (no countdown is
 * shown for an unknown value; it is never guessed).
 */
export function useApprovalExpiryTtl(): number | null {
  const [ttl, setTtl] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    Promise.resolve()
      .then((): Promise<number | null> => approvalsQueueApi.getExpiryTtlSeconds())
      .then((value) => {
        if (live) setTtl(value ?? null);
      })
      .catch((): void => undefined);
    return () => {
      live = false;
    };
  }, []);
  return ttl;
}
