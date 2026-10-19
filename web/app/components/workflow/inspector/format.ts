import type { LogLevel } from "./types";

export const ERR_RE = /error|exception|fatal|traceback/i;
export const WARN_RE = /warn/i;

export function classifyLine(line: string): LogLevel {
  if (ERR_RE.test(line)) return "error";
  if (WARN_RE.test(line)) return "warn";
  return "info";
}

export const isNum = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

export function formatDuration(ms: number): string {
  if (!isNum(ms) || ms < 0) return "-";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m ${String(s).padStart(2, "0")}s`;
}

export const clockFmt = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
export function formatClock(ms: number): string {
  // Millis matter here: sibling nodes routinely start inside the same second.
  // Splice millis right after the seconds so 12h locales read "7:51:11.898 PM".
  const frac = String(Math.floor(ms % 1000)).padStart(3, "0");
  return clockFmt.formatToParts(ms).map((p) => (p.type === "second" ? `${p.value}.${frac}` : p.value)).join("");
}

export const intFmt = new Intl.NumberFormat();
export const rateFmt = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
export function formatUsd(v: number): string {
  // Sub-cent LLM costs are the common case; significant digits keep them readable.
  const opts: Intl.NumberFormatOptions =
    v !== 0 && Math.abs(v) < 0.01
      ? { style: "currency", currency: "USD", maximumSignificantDigits: 3 }
      : { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 4 };
  return new Intl.NumberFormat(undefined, opts).format(v);
}
