// local_datetime: instant arithmetic and zone formatting. Split out of ../localTools.ts.
// Pure computation: runtime imports are limited to sibling pure modules.

import { refuse } from "./common";

// ---------------------------------------------------------------------------
// local_datetime
// ---------------------------------------------------------------------------

export const OFFSET_UNIT_MS: Record<string, number> = {
  minutes: 60_000,
  hours: 3_600_000,
  days: 86_400_000,
  weeks: 604_800_000,
};

/**
 * Offsets are EXACT ELAPSED TIME, not calendar arithmetic: "+1 day" is 24h, so
 * across a DST transition in the target zone the wall-clock time moves by the
 * DST shift. Months and years are absent for the same reason — their length
 * depends on the calendar date in a particular zone, and a tool that quietly
 * guessed which would be worse than one that says it cannot.
 */
export function shiftInstant(nowMs: number, args: Record<string, unknown>): number {
  const offset = args.offset;
  if (offset === undefined || offset === null) return nowMs;
  if (typeof offset !== "number" || !Number.isFinite(offset)) {
    refuse('"offset" must be a finite number of units.');
  }
  const unit = args.unit === undefined || args.unit === null ? "days" : args.unit;
  if (typeof unit !== "string" || !(unit in OFFSET_UNIT_MS)) {
    refuse(`"unit" must be one of: ${Object.keys(OFFSET_UNIT_MS).join(", ")}.`);
  }
  const shifted = nowMs + offset * OFFSET_UNIT_MS[unit];
  if (!Number.isFinite(shifted) || Number.isNaN(new Date(shifted).getTime())) {
    refuse("That offset lands outside the range of representable dates.");
  }
  return shifted;
}

/**
 * Two checks, because neither alone is honest.
 *
 * SHAPE first: Intl on its own is too permissive to be a zone validator.
 * Measured on this repo's Node 24 ICU (2026-09-12), `new Intl.DateTimeFormat(
 * "en-GB", {timeZone: "IST"})` does NOT throw — ICU silently resolves the
 * legacy abbreviation to one of the several zones that use it (it printed
 * GMT+05:30, i.e. India, not Ireland or Israel). Accepting that would be the
 * tool inventing a zone, which is exactly what it must not do. So an
 * abbreviation is refused and the person is asked for an Area/Location id.
 *
 * EXISTENCE second, via Intl rather than a bundled list: `Intl.supportedValuesOf
 * ("timeZone")` cannot be used as an allowlist, because engines disagree on
 * which name is canonical — the same Node build above lists "Asia/Calcutta" and
 * not "Asia/Kolkata", though it formats both. Membership would refuse a zone id
 * the engine handles perfectly well.
 */
export function assertKnownTimeZone(zone: string): void {
  const looksLikeZoneId = zone.includes("/") || zone.toUpperCase() === "UTC";
  if (!looksLikeZoneId) {
    refuse(
      `"${zone}" is an abbreviation, and abbreviations are ambiguous (IST is India, Ireland and Israel). Use a full IANA zone id such as "Asia/Kolkata", "Europe/London", or "UTC".`
    );
  }
  try {
    new Intl.DateTimeFormat("en-GB", { timeZone: zone }).format(0);
  } catch {
    refuse(
      `"${zone}" is not an IANA time zone this browser knows. Use a full zone id such as "Asia/Kolkata", "Europe/London" or "UTC".`
    );
  }
}

export function zoneOffsetLabel(date: Date, zone: string): string {
  for (const style of ["longOffset", "shortOffset"] as const) {
    try {
      const parts = new Intl.DateTimeFormat("en-GB", { timeZone: zone, timeZoneName: style }).formatToParts(date);
      const found = parts.find((p) => p.type === "timeZoneName");
      if (found && found.value) return found.value;
    } catch {
      // Older Intl without this timeZoneName style — try the next one.
    }
  }
  return "";
}

export function formatInZone(date: Date, zone: string): string {
  // Parts are assembled by hand into ISO-ish order rather than trusting a
  // locale's date order: 03/04 is ambiguous to a reader and to a model.
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: zone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const get = (type: string): string => {
    const found = parts.find((p) => p.type === type);
    return found ? found.value : "??";
  };
  const weekday = new Intl.DateTimeFormat("en-GB", { timeZone: zone, weekday: "long" }).format(date);
  const offset = zoneOffsetLabel(date, zone);
  return (
    `${get("year")}-${get("month")}-${get("day")} ${get("hour")}:${get("minute")}:${get("second")}` +
    ` (${zone}${offset ? `, ${offset}` : ""})\n` +
    `${weekday}\n` +
    `UTC instant: ${date.toISOString()}`
  );
}

export function localTimeZone(): string {
  try {
    const zone = new Intl.DateTimeFormat().resolvedOptions().timeZone;
    return zone || "UTC";
  } catch {
    return "UTC";
  }
}

