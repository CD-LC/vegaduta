// Pure helpers for spend caps and read-only view-as (lib/adminApi/governance.ts).
// Same "nullable means unavailable" contract as platformFormat.ts: a missing
// figure renders as "—", never as $0.

import { UNAVAILABLE } from "./platformFormat";

function finite(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

/** USD with a leading dollar sign, no forced decimals for whole amounts; "—" when unavailable. */
export function formatCapUsd(v: unknown): string {
  const n = finite(v);
  if (n == null) return UNAVAILABLE;
  return Number.isInteger(n)
    ? `$${n.toLocaleString("en-US")}`
    : `$${n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** Percent of cap used (rounded), preferring core's own figure; null without a cap. Not clamped (an overshoot is real). */
export function capPercent(capUsd: unknown, monthToDateUsd: unknown, percentUsed?: unknown): number | null {
  const p = finite(percentUsed);
  if (p != null) return Math.round(p);
  const cap = finite(capUsd);
  const used = finite(monthToDateUsd);
  if (cap == null || cap <= 0 || used == null) return null;
  return Math.round((used / cap) * 100);
}

/** Roster cell: "<month-to-date> / <cap> (<pct>%)", "No cap", or "— / <cap>" when the month-to-date is unknown. */
export function formatCapUsage(capUsd: unknown, monthToDateUsd: unknown): string {
  const cap = finite(capUsd);
  if (cap == null) return "No cap";
  const pct = capPercent(cap, monthToDateUsd);
  return `${formatCapUsd(monthToDateUsd)} / ${formatCapUsd(cap)}${pct == null ? "" : ` (${pct}%)`}`;
}

export type CapTone = "ok" | "warn" | "bad" | "muted";

/** Blocked or >=100% = bad, >=80% = warn, otherwise ok; muted without a cap. */
export function capTone(cap: {
  capUsd: unknown;
  monthToDateUsd: unknown;
  blocked?: boolean | null;
  alertStage?: number | null;
  percentUsed?: unknown;
}): CapTone {
  if (finite(cap.capUsd) == null) return "muted";
  if (cap.blocked) return "bad";
  const pct = capPercent(cap.capUsd, cap.monthToDateUsd, cap.percentUsed);
  const stage = finite(cap.alertStage) ?? 0;
  if (stage >= 100 || (pct != null && pct >= 100)) return "bad";
  if (stage >= 80 || (pct != null && pct >= 80)) return "warn";
  return "ok";
}

/** Width (0..100) of the month-to-date bar. */
export function capBarWidth(pct: number | null): number {
  if (pct == null || !Number.isFinite(pct)) return 0;
  return Math.max(0, Math.min(100, pct));
}

export const ALERT_STAGE_LABEL: Record<number, string> = {
  0: "Under 80%",
  80: "80% alert sent",
  100: "100% reached",
};

/**
 * Parses the cap editor input. Returns {ok:true, value} with value null for
 * "no cap", or {ok:false, error}. Two-decimal max, positive, bounded.
 */
export function parseCapInput(
  raw: string,
  noCap: boolean
): { ok: true; value: number | null } | { ok: false; error: string } {
  if (noCap) return { ok: true, value: null };
  const s = (raw || "").trim().replace(/^\$/, "").replace(/,/g, "");
  if (!s) return { ok: false, error: "Enter a monthly cap in USD, or tick No cap." };
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return { ok: false, error: "Use a plain dollar amount, e.g. 50 or 12.50." };
  const n = Number(s);
  if (!(n > 0)) return { ok: false, error: "The cap must be more than zero. Tick No cap to remove it." };
  if (n > 1_000_000) return { ok: false, error: "That cap is over the 1,000,000 USD maximum - check the amount." };
  return { ok: true, value: n };
}

// ---- view-as --------------------------------------------------------------

export const IMPERSONATION_REASON_MIN = 10;

export function reasonProblem(reason: string): string | null {
  const n = (reason || "").trim().length;
  if (n === 0) return "A reason is required. It is recorded and shown to the tenant admin.";
  if (n < IMPERSONATION_REASON_MIN)
    return `Give a little more detail (${IMPERSONATION_REASON_MIN - n} more character${IMPERSONATION_REASON_MIN - n === 1 ? "" : "s"}).`;
  return null;
}

/**
 * Only open what core handed back if it is an http(s) URL to the tenant app's
 * /view-as route with the grant in the FRAGMENT (never the query string).
 */
export function isSafeViewUrl(url: unknown): url is string {
  if (typeof url !== "string" || !url) return false;
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return false;
  }
  if (u.protocol !== "https:" && u.protocol !== "http:") return false;
  if (u.pathname !== "/view-as") return false;
  if (u.search.includes("grant")) return false;
  return /^#grant=[^&]+/.test(u.hash);
}

export type SessionState = "active" | "ended" | "expired";

export function impersonationState(
  s: { endedAt: string | null; expiresAt: string | null },
  now: number = Date.now()
): SessionState {
  if (s.endedAt) return "ended";
  const exp = s.expiresAt ? new Date(s.expiresAt).getTime() : NaN;
  if (!Number.isNaN(exp) && exp <= now) return "expired";
  return "active";
}

/** "29m left", "45s left", "expired". */
export function formatTimeLeft(expiresAt: string | null | undefined, now: number = Date.now()): string {
  if (!expiresAt) return UNAVAILABLE;
  const t = new Date(expiresAt).getTime();
  if (Number.isNaN(t)) return UNAVAILABLE;
  const ms = t - now;
  if (ms <= 0) return "expired";
  const secs = Math.ceil(ms / 1000);
  return secs >= 60 ? `${Math.ceil(secs / 60)}m left` : `${secs}s left`;
}
