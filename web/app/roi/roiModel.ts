// Pure view rules for the ROI page (app/roi). No React, no fetching, so the rules that decide what a number
// is allowed to look like are unit-tested (roiModel.test.ts):
//   - null is "no data", shown as an em dash, never as 0 or 0%;
//   - every cost is "estimated", and a cost that excludes unpriced calls says so;
//   - every ROI figure carries "based on your assumptions" (the server sends the phrase; the page prints it).
import type { AgentRoiRowDto, Money } from "../lib/api/roi";

export const DASH = "—";

export function toNumber(value: Money | undefined): number | null {
  if (value === null || value === undefined || value === "") return null;
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

export function formatUsd(value: Money | undefined, digits = 2): string {
  const n = toNumber(value);
  if (n === null) return DASH;
  // Sub-cent costs are real for cheap models; "$0.00" would read as free.
  if (n > 0 && n < 0.01) return "<$0.01";
  return `$${n.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

export function formatPercentFromRatio(value: Money | undefined): string {
  const n = toNumber(value);
  if (n === null) return DASH;
  return `${(n * 100).toLocaleString("en-US", { maximumFractionDigits: 1 })}%`;
}

export function formatRoiPercent(value: Money | undefined): string {
  const n = toNumber(value);
  if (n === null) return DASH;
  return `${n.toLocaleString("en-US", { maximumFractionDigits: 1 })}%`;
}

export function formatCount(n: number | null | undefined): string {
  return typeof n === "number" ? n.toLocaleString("en-US") : DASH;
}

/** Money in the tenant's own currency. No conversion is ever attempted. */
export function formatMoney(value: Money | undefined, currency: string | null | undefined): string {
  const n = toNumber(value);
  if (n === null) return DASH;
  const text = n.toLocaleString("en-US", { maximumFractionDigits: 2 });
  return currency ? `${text} ${currency}` : text;
}

/** Platform charges are stored in minor units (cents/paise). Null when there are none or the currency is unknown. */
export function formatCharges(minor: number, currency: string | null): string {
  if (!minor || !currency) return DASH;
  return formatMoney(minor / 100, currency);
}

export type Tone = "ok" | "warn" | "over" | "none";

/** Budget bar colour: warn from 80% (the first alert), over from 100% (the second). */
export function budgetTone(percent: Money | undefined, capConfigured: boolean): Tone {
  const n = toNumber(percent);
  if (!capConfigured || n === null) return "none";
  if (n >= 100) return "over";
  if (n >= 80) return "warn";
  return "ok";
}

/** The bar fills to at most 100%, whatever the overshoot. */
export function budgetFill(percent: Money | undefined): number {
  const n = toNumber(percent);
  if (n === null) return 0;
  return Math.min(100, Math.max(0, n));
}

/** i18n key suffix for a server ROI note, or null when the figure is complete. */
export function roiNoteKey(note: string): string | null {
  switch (note) {
    case "NO_ASSUMPTIONS":
      return "roiNoteNoAssumptions";
    case "NO_VALUE_ASSUMPTION":
      return "roiNoteNoValue";
    case "CURRENCY_MISMATCH":
      return "roiNoteCurrency";
    case "COST_INCOMPLETE":
      return "roiNoteIncomplete";
    case "NO_COST_RECORDED":
      return "roiNoteNoCost";
    default:
      return null;
  }
}

export interface Totals {
  conversations: number;
  resolved: number;
  estimatedCostUsd: number;
  costComplete: boolean;
}

export function totals(rows: AgentRoiRowDto[]): Totals {
  return rows.reduce<Totals>(
    (acc, r) => ({
      conversations: acc.conversations + r.conversations,
      resolved: acc.resolved + r.resolved,
      estimatedCostUsd: acc.estimatedCostUsd + (toNumber(r.estimatedCostUsd) ?? 0),
      costComplete: acc.costComplete && r.costComplete,
    }),
    { conversations: 0, resolved: 0, estimatedCostUsd: 0, costComplete: true },
  );
}

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** yyyy-MM-dd for a Date, in UTC (the server's day boundary). */
export function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function presetRange(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = isoDay(now);
  const from = isoDay(new Date(now.getTime() - (days - 1) * 86_400_000));
  return { from, to };
}

/** null when the pair is acceptable; otherwise an i18n key suffix. */
export function validateRange(from: string, to: string): string | null {
  if (!ISO_DAY.test(from) || !ISO_DAY.test(to)) return "rangeInvalid";
  if (from > to) return "rangeOrder";
  const span = (Date.parse(to) - Date.parse(from)) / 86_400_000;
  return span > 365 ? "rangeTooLong" : null;
}

/** Parses a number typed by the tenant; blank is null (no assumption), junk or negative is undefined (invalid). */
export function parseAmount(text: string): number | null | undefined {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  const n = Number(trimmed);
  return Number.isFinite(n) && n >= 0 ? n : undefined;
}
