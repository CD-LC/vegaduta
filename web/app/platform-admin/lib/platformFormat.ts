// Formatting for the platform-admin figures whose contract is "nullable means
// unavailable" (GET /api/platform/tenants, /tenant-detail, /users, ...).
//
// A null/undefined/non-finite value is NEVER rendered as 0 or $0 - core sends
// null exactly when it could not measure the figure (tenant schema unreadable,
// usage table missing, ...), and a zero there would read as "measured, none".

export const UNAVAILABLE = "—";

function finite(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

/** USD with a leading dollar sign, 2 decimals (4 for sub-dollar amounts); "—" when unavailable. */
export function formatUsdOrUnavailable(v: unknown): string {
  const n = finite(v);
  if (n == null) return UNAVAILABLE;
  return `$${n.toFixed(Math.abs(n) < 1 && n !== 0 ? 4 : 2)}`;
}

/** "1,234", "—" when unavailable. */
export function formatCountOrUnavailable(v: unknown): string {
  const n = finite(v);
  return n == null ? UNAVAILABLE : n.toLocaleString("en-US");
}

function parseIso(iso: unknown): Date | null {
  if (typeof iso !== "string" || !iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Full local timestamp for tooltips; "—" for a missing or unparseable value. */
export function formatExact(iso: unknown): string {
  const d = parseIso(iso);
  return d ? d.toLocaleString() : UNAVAILABLE;
}

/** Calendar date only ("Oct 9, 2026"); "—" when missing. */
export function formatDay(iso: unknown): string {
  const d = parseIso(iso);
  return d ? d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : UNAVAILABLE;
}

/**
 * "just now", "5m ago", "3h ago", "12d ago", "4mo ago", "2y ago" - or the
 * future equivalents ("in 3h") for clock skew. "—" when missing.
 */
export function formatRelative(iso: unknown, now: number = Date.now()): string {
  const d = parseIso(iso);
  if (!d) return UNAVAILABLE;
  const diffMs = now - d.getTime();
  const future = diffMs < 0;
  const mins = Math.floor(Math.abs(diffMs) / 60000);
  let span: string;
  if (mins < 1) return "just now";
  if (mins < 60) span = `${mins}m`;
  else if (mins < 60 * 24) span = `${Math.floor(mins / 60)}h`;
  else if (mins < 60 * 24 * 30) span = `${Math.floor(mins / (60 * 24))}d`;
  else if (mins < 60 * 24 * 365) span = `${Math.floor(mins / (60 * 24 * 30))}mo`;
  else span = `${Math.floor(mins / (60 * 24 * 365))}y`;
  return future ? `in ${span}` : `${span} ago`;
}
