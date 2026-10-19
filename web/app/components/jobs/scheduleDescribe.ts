// Pure helpers for SchedulePanel: turning the picker's state into a
// human-readable preview and into the API payload. No React, no i18n - callers
// pass a translator so this stays unit-testable.

export type Unit = "minutes" | "hours" | "days";

export const UNIT_SECONDS: Record<Unit, number> = { minutes: 60, hours: 3600, days: 86400 };

/** Server bounds (AgentScheduleCadence): 60s .. 1 year. */
export const MIN_INTERVAL_SECONDS = 60;
export const MAX_INTERVAL_SECONDS = 365 * 24 * 3600;

export function intervalToSeconds(amount: number, unit: Unit): number {
  return Math.round(amount) * UNIT_SECONDS[unit];
}

/** Picks the largest unit that divides the stored seconds exactly, for round-tripping a saved schedule into the form. */
export function secondsToAmountUnit(seconds: number): { amount: number; unit: Unit } {
  if (seconds % UNIT_SECONDS.days === 0) return { amount: seconds / UNIT_SECONDS.days, unit: "days" };
  if (seconds % UNIT_SECONDS.hours === 0) return { amount: seconds / UNIT_SECONDS.hours, unit: "hours" };
  return { amount: Math.max(1, Math.round(seconds / 60)), unit: "minutes" };
}

export function validateInterval(amount: number, unit: Unit): boolean {
  if (!Number.isFinite(amount) || amount <= 0) return false;
  const s = intervalToSeconds(amount, unit);
  return s >= MIN_INTERVAL_SECONDS && s <= MAX_INTERVAL_SECONDS;
}

type Translate = (key: string, values?: Record<string, string | number>) => string;

export function describeInterval(seconds: number, t: Translate): string {
  const { amount, unit } = secondsToAmountUnit(seconds);
  const key = unit === "minutes" ? "everyMinutes" : unit === "hours" ? "everyHours" : "everyDays";
  return t(key, { count: amount });
}

const pad = (n: string) => n.padStart(2, "0");

const DOW: Record<string, string> = {
  "0": "Sun", "7": "Sun", "1": "Mon", "2": "Tue", "3": "Wed", "4": "Thu", "5": "Fri", "6": "Sat",
  SUN: "Sun", MON: "Mon", TUE: "Tue", WED: "Wed", THU: "Thu", FRI: "Fri", SAT: "Sat",
};

/**
 * Best-effort description of the common cron shapes a person actually writes
 * (5-field; a 6-field Spring cron has its leading seconds field dropped when it
 * is "0"). Returns null for anything else so the caller shows the raw cron
 * instead of a wrong guess.
 */
export function describeCron(raw: string, t: Translate): string | null {
  let parts = raw.trim().split(/\s+/);
  if (parts.length === 6 && parts[0] === "0") parts = parts.slice(1);
  if (parts.length !== 5) return null;
  const [min, hour, dom, mon, dow] = parts;

  const stepMin = /^\*\/(\d+)$/.exec(min);
  if (stepMin && hour === "*" && dom === "*" && mon === "*" && dow === "*") {
    return t("everyMinutes", { count: Number(stepMin[1]) });
  }
  if (min === "0" && hour === "*" && dom === "*" && mon === "*" && dow === "*") return t("everyHour");
  if (/^\d+$/.test(min) && /^\d+$/.test(hour) && dom === "*" && mon === "*") {
    const time = `${pad(hour)}:${pad(min)}`;
    if (dow === "*") return t("dailyAt", { time });
    if (dow.toUpperCase() === "MON-FRI" || dow === "1-5") return t("weekdaysAt", { time });
    const days = dow.split(",").map((d) => DOW[d.toUpperCase()]);
    if (days.length > 0 && days.every(Boolean)) return t("onDaysAt", { days: days.join(", "), time });
  }
  return null;
}
