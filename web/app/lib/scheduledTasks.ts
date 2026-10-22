// Scheduled tasks (core com.agenticai.core.agents.scheduledtask, /api/scheduled-tasks): types plus the pure helpers
// the /scheduled page uses - the friendly schedule picker's validation, the live "next runs" preview, delivery
// address checks and display formatting. No React, no fetch, no clock of its own (every function that depends on
// "now" takes it), so every rule is unit-tested (scheduledTasks.test.ts).
//
// The server is the source of truth: it re-validates everything here (ScheduledTaskSchedule / ScheduledTaskService)
// and computes the authoritative next runs with the cron library. The preview below mirrors those rules for the
// four friendly kinds so the form can show "next runs" instantly and offline; a CUSTOM cron is previewed by the
// server (POST /api/scheduled-tasks/preview) because a cron's full grammar is not worth re-implementing here.
// Where the two could disagree - a wall-clock time inside a spring-forward gap - the preview shifts forward to the
// next real instant, which is what the server's cron library does as well.

import { channelOffered } from "./edition";

export type ScheduleKind = "daily" | "weekdays" | "weekly" | "monthly" | "custom";
export const SCHEDULE_KINDS: readonly ScheduleKind[] = ["daily", "weekdays", "weekly", "monthly", "custom"];

export type DeliveryChannel =
  | "in_app"
  | "email"
  | "whatsapp"
  | "telegram"
  | "slack"
  | "signal"
  | "matrix"
  | "imessage"
  | "a2a"
  | "voice";
const ALL_DELIVERY_CHANNELS: readonly DeliveryChannel[] = [
  "in_app",
  "email",
  "whatsapp",
  "telegram",
  "slack",
  "signal",
  "matrix",
  "imessage",
  "a2a",
  "voice",
];

/**
 * The delivery channels the form and the explainer offer: ALL of them minus the ones this install does not run
 * (Slack on a personal install). The server's /capabilities list narrows this further. {@link normalizeDeliveryTarget}
 * still understands every channel, so an existing task keeps validating.
 */
export const DELIVERY_CHANNELS: readonly DeliveryChannel[] = ALL_DELIVERY_CHANNELS.filter((c) => channelOffered(c));

export const WEEKDAY_CODES = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;
export type WeekdayCode = (typeof WEEKDAY_CODES)[number];

/** Day-of-month choices a monthly task accepts: 1-28 or the last day. 29-31 are refused (shorter months). */
export const MONTH_DAY_CHOICES: readonly string[] = [...Array.from({ length: 28 }, (_, i) => String(i + 1)), "LAST"];

/** GET /api/scheduled-tasks/capabilities - readable even while the feature is off. */
export interface ScheduledTasksConfig {
  enabled: boolean;
  maxTasks: number;
  minIntervalMinutes: number;
  maxPromptChars: number;
  deliveryChannels: string[];
}

export type RunStatus = "ENQUEUED" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "SKIPPED";

/** How often a task list / run history is re-read while a run is in flight, and for how long at most (5 minutes). */
export const RUNNING_POLL_MS = 4000;
export const RUNNING_POLL_MAX_TICKS = 75;

/** A status that will still change without any action in this tab. */
export function isRunInFlight(status: string | null | undefined): boolean {
  return status === "ENQUEUED" || status === "RUNNING";
}

export interface ScheduledTask {
  id: string;
  agentId: string;
  name: string;
  prompt: string;
  frequency: ScheduleKind;
  time: string | null;
  day: string | null;
  cron: string;
  timezone: string;
  scheduleDescription: string;
  delivery: DeliveryChannel;
  deliveryTo: string | null;
  enabled: boolean;
  disabledReason: string | null;
  nextRunAt: string | null;
  upcomingRuns: string[];
  lastRunAt: string | null;
  lastStatus: string | null;
  lastJobId: string | null;
  consecutiveFailures: number;
  runCount: number;
  maxRuns: number | null;
  expiresAt: string | null;
  ownerSubject: string;
  createdAt: string;
  updatedAt: string;
}

export interface ScheduledTaskRun {
  id: string;
  taskId: string;
  trigger: "SCHEDULE" | "MANUAL" | "RETRY";
  status: RunStatus;
  attempt: number;
  startedAt: string;
  finishedAt: string | null;
  jobId: string | null;
  costUsd: number | null;
  deliveryStatus: "IN_APP" | "DELIVERED" | "PENDING_APPROVAL" | "FAILED" | null;
  error: string | null;
}

/** POST body of create / update (the server re-validates every field). */
export interface ScheduledTaskPayload {
  agentId: string;
  name: string;
  prompt: string;
  frequency: ScheduleKind;
  time?: string;
  day?: string;
  cron?: string;
  timezone: string;
  delivery: DeliveryChannel;
  deliveryTo?: string;
  maxRuns?: number;
  expiresAt?: string;
}

export interface SchedulePreview {
  cron: string;
  description: string;
  timezone: string;
  nextRuns: string[];
}

/** The schedule part of the form. */
export interface ScheduleDraft {
  kind: ScheduleKind;
  /** HH:mm (24h); ignored for custom. */
  time: string;
  /** weekly: MON..SUN; monthly: "1".."28" or "LAST"; ignored otherwise. */
  day: string;
  /** custom only: a 5-field cron. */
  cron: string;
  /** IANA zone. */
  timezone: string;
}

// ------------------------------------------------------------------- validation

const TIME_RE = /^([01]?\d|2[0-3]):([0-5]\d)$/;

/** "8:05" or "08:05" -> "08:05"; anything else -> null. */
export function normalizeTime(raw: string): string | null {
  const m = TIME_RE.exec(raw.trim());
  if (!m) return null;
  return `${m[1].padStart(2, "0")}:${m[2]}`;
}

export function isValidTimeZone(tz: string): boolean {
  if (!tz.trim()) return false;
  try {
    const probe = new Intl.DateTimeFormat("en-US", { timeZone: tz.trim() });
    return probe.resolvedOptions().timeZone.length > 0;
  } catch {
    return false;
  }
}

/** The browser's own zone, "UTC" when it cannot tell. */
export function defaultTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

export type ScheduleProblem =
  | "timeRequired"
  | "timeInvalid"
  | "dayRequired"
  | "dayInvalid"
  | "cronRequired"
  | "cronFields"
  | "cronSeconds"
  | "cronChars"
  | "cronTooFrequent"
  | "timezoneInvalid";

/** The most frequent firing a cron's minute field can produce, in minutes; null when it cannot tell. */
export function cronMinimumGapMinutes(cron: string): number | null {
  const fields = cron.trim().split(/\s+/);
  const five = fields.length === 6 ? fields.slice(1) : fields;
  if (five.length !== 5) return null;
  const [minute, hour] = five;
  if (minute === "*") return 1;
  const step = /^(?:\*|\d+-\d+)\/(\d+)$/.exec(minute);
  if (step) return Math.max(1, Number(step[1]));
  if (/^\d+(,\d+)+$/.test(minute) && hour === "*") {
    const values = minute.split(",").map(Number).sort((a, b) => a - b);
    let gap = 60 - values[values.length - 1] + values[0];
    for (let i = 1; i < values.length; i++) gap = Math.min(gap, values[i] - values[i - 1]);
    return Math.max(1, gap);
  }
  return null;
}

/**
 * The first thing wrong with a schedule, as a message key (or null when it is fine). Mirrors the server's
 * ScheduledTaskSchedule.compile for what is checkable without a cron library; the server stays authoritative
 * (a custom cron faster than the minimum interval is only caught here for the obvious minute-field shapes).
 */
export function validateSchedule(draft: ScheduleDraft, minIntervalMinutes: number): ScheduleProblem | null {
  if (!isValidTimeZone(draft.timezone)) return "timezoneInvalid";
  if (draft.kind === "custom") {
    const cron = draft.cron.trim();
    if (!cron) return "cronRequired";
    const fields = cron.split(/\s+/);
    if (fields.length !== 5 && fields.length !== 6) return "cronFields";
    if (fields.length === 6 && fields[0] !== "0") return "cronSeconds";
    if (!/^[0-9A-Za-z*/,\-?#]+$/.test(fields.join(""))) return "cronChars";
    const gap = cronMinimumGapMinutes(cron);
    if (gap !== null && gap < minIntervalMinutes) return "cronTooFrequent";
    return null;
  }
  if (!draft.time.trim()) return "timeRequired";
  if (normalizeTime(draft.time) === null) return "timeInvalid";
  if (draft.kind === "weekly") {
    if (!draft.day) return "dayRequired";
    if (!(WEEKDAY_CODES as readonly string[]).includes(draft.day.toUpperCase())) return "dayInvalid";
  }
  if (draft.kind === "monthly") {
    if (!draft.day) return "dayRequired";
    if (!MONTH_DAY_CHOICES.includes(draft.day.toUpperCase())) return "dayInvalid";
  }
  return null;
}

// ---------------------------------------------------------------- delivery target

const EMAIL_RE = /^[^@\s,;]+@[^@\s,;]+\.[^@\s,;]+$/;
const PHONE_RE = /^\+?[0-9]{7,15}$/;
const TELEGRAM_RE = /^-?[0-9]{3,20}$/;
const SLACK_RE = /^[A-Za-z0-9_-]{3,40}$/;

export function deliveryNeedsTarget(channel: DeliveryChannel): boolean {
  return channel !== "in_app";
}

/**
 * The normalized recipient for a channel, or null when it is not a valid address for that channel (the same
 * shapes the server accepts). In-app has no recipient and always answers "".
 */
export function normalizeDeliveryTarget(channel: DeliveryChannel, raw: string): string | null {
  const value = raw.trim();
  switch (channel) {
    case "in_app":
      return "";
    case "email":
      return EMAIL_RE.test(value) ? value : null;
    case "whatsapp": {
      const digits = value.replace(/[\s()-]/g, "");
      return PHONE_RE.test(digits) ? digits : null;
    }
    case "telegram":
      return TELEGRAM_RE.test(value) ? value : null;
    case "slack":
      return SLACK_RE.test(value) ? value : null;
    case "signal": {
      const digits = value.replace(/[\s()-]/g, "");
      return PHONE_RE.test(digits) ? digits : null;
    }
    case "matrix":
      return /^@[^@\s:]+:[^@\s]+$/.test(value) ? value : null;
    case "imessage": {
      const digits = value.replace(/[\s()-]/g, "");
      if (PHONE_RE.test(digits) || EMAIL_RE.test(value)) return value;
      return null;
    }
    case "a2a":
      return /^https?:\/\/\S+$/.test(value) ? value : null;
    case "voice": {
      const digits = value.replace(/[\s()-]/g, "");
      return PHONE_RE.test(digits) ? digits : null;
    }
    default:
      return null;
  }
}

// -------------------------------------------------------------- next-run preview

interface WallParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

const formatterCache = new Map<string, Intl.DateTimeFormat>();

function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let f = formatterCache.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    formatterCache.set(timeZone, f);
  }
  return f;
}

/** The wall-clock reading of an instant in a zone. */
export function zonedParts(epochMs: number, timeZone: string): WallParts {
  const out: Record<string, number> = {};
  for (const p of partsFormatter(timeZone).formatToParts(new Date(epochMs))) {
    if (p.type !== "literal") out[p.type] = Number(p.value);
  }
  return {
    year: out.year,
    month: out.month,
    day: out.day,
    hour: out.hour % 24,
    minute: out.minute,
    second: out.second,
  };
}

/** The zone's UTC offset at an instant, in minutes (positive east of UTC). */
export function offsetMinutes(epochMs: number, timeZone: string): number {
  const p = zonedParts(epochMs, timeZone);
  const wallAsUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return Math.round((wallAsUtc - Math.floor(epochMs / 1000) * 1000) / 60000);
}

const DAY_MS = 86_400_000;

/**
 * The instant at which a zone's wall clock reads y-m-d h:mi. A time that happens twice (the autumn clock change)
 * resolves to the FIRST occurrence; a time that does not exist (the spring gap) is shifted forward by the length of
 * the gap, i.e. to the next real instant.
 */
export function zonedTimeToInstant(y: number, m: number, d: number, h: number, mi: number, timeZone: string): number {
  const wall = Date.UTC(y, m - 1, d, h, mi, 0);
  const candidates = new Set([offsetMinutes(wall - DAY_MS, timeZone), offsetMinutes(wall + DAY_MS, timeZone)]);
  const valid: number[] = [];
  for (const offset of candidates) {
    const utc = wall - offset * 60_000;
    if (offsetMinutes(utc, timeZone) === offset) valid.push(utc);
  }
  if (valid.length > 0) return Math.min(...valid);
  return wall - offsetMinutes(wall - DAY_MS, timeZone) * 60_000;
}

const WEEKDAY_TO_DOW: Record<string, number> = { SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6 };

function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/**
 * The next `count` runs strictly after `from` for a friendly schedule, in order. Returns [] for a custom schedule
 * (previewed by the server) or an invalid draft. Walks local calendar days so month ends and clock changes behave:
 * a MONTHLY "LAST" task lands on Feb 28 / Feb 29 / Mar 31 as the calendar says.
 */
export function nextRuns(draft: ScheduleDraft, from: Date, count = 3): Date[] {
  if (draft.kind === "custom" || validateSchedule(draft, 0) !== null) return [];
  const time = normalizeTime(draft.time);
  if (time === null) return [];
  const [hour, minute] = time.split(":").map(Number);
  const zone = draft.timezone.trim();
  const start = zonedParts(from.getTime(), zone);
  const out: Date[] = [];
  const day = draft.day.toUpperCase();
  for (let i = 0; i < 800 && out.length < count; i++) {
    const date = new Date(Date.UTC(start.year, start.month - 1, start.day + i));
    const y = date.getUTCFullYear();
    const m = date.getUTCMonth() + 1;
    const dom = date.getUTCDate();
    const dow = date.getUTCDay();
    let matches: boolean;
    switch (draft.kind) {
      case "daily":
        matches = true;
        break;
      case "weekdays":
        matches = dow >= 1 && dow <= 5;
        break;
      case "weekly":
        matches = dow === WEEKDAY_TO_DOW[day];
        break;
      case "monthly":
        matches = day === "LAST" ? dom === daysInMonth(y, m) : dom === Number(day);
        break;
      default:
        matches = false;
    }
    if (!matches) continue;
    const instant = zonedTimeToInstant(y, m, dom, hour, minute, zone);
    if (instant > from.getTime()) out.push(new Date(instant));
  }
  return out;
}

// ------------------------------------------------------------------- description

export interface ScheduleDescription {
  /** A key in the "scheduledTasks" i18n namespace. */
  key: "describeDaily" | "describeWeekdays" | "describeWeekly" | "describeMonthly" | "describeMonthlyLast" | "describeCustom";
  params: Record<string, string>;
}

/** The structured plain-language description of a schedule; the page renders it through t(). */
export function describeSchedule(draft: ScheduleDraft): ScheduleDescription {
  const time = normalizeTime(draft.time) ?? draft.time;
  const timezone = draft.timezone;
  switch (draft.kind) {
    case "daily":
      return { key: "describeDaily", params: { time, timezone } };
    case "weekdays":
      return { key: "describeWeekdays", params: { time, timezone } };
    case "weekly":
      return { key: "describeWeekly", params: { time, timezone, day: draft.day.toUpperCase() } };
    case "monthly":
      return draft.day.toUpperCase() === "LAST"
        ? { key: "describeMonthlyLast", params: { time, timezone } }
        : { key: "describeMonthly", params: { time, timezone, day: draft.day } };
    default:
      return { key: "describeCustom", params: { cron: draft.cron.trim(), timezone } };
  }
}

// ----------------------------------------------------------------------- display

/** "Mon, 5 Oct 2026, 08:00 IST" - an instant in the TASK's zone (not the viewer's), with the zone named. */
export function formatRunTime(iso: string, timeZone: string, locale?: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(locale, {
      timeZone,
      weekday: "short",
      day: "numeric",
      month: "short",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZoneName: "short",
    }).format(date);
  } catch {
    return date.toISOString();
  }
}

export type StatusTone = "success" | "error" | "warning" | "running" | "idle";

/** The badge tone for a run / last status string. */
export function statusTone(status: string | null | undefined): StatusTone {
  switch (status) {
    case "SUCCEEDED":
      return "success";
    case "FAILED":
      return "error";
    case "SKIPPED":
    case "CANCELLED":
      return "warning";
    case "RUNNING":
    case "ENQUEUED":
      return "running";
    default:
      return "idle";
  }
}

/** A run cost in dollars with two decimals (four for a fraction of a cent); empty when unknown. Formatting, not a plan price. */
export function formatCost(costUsd: number | null | undefined): string {
  if (costUsd === null || costUsd === undefined || !Number.isFinite(costUsd)) return "";
  return `$${costUsd.toFixed(costUsd > 0 && costUsd < 0.01 ? 4 : 2)}`;
}

/** Turns the form's state into the request body the server expects. */
export function buildPayload(input: {
  agentId: string;
  name: string;
  prompt: string;
  schedule: ScheduleDraft;
  delivery: DeliveryChannel;
  deliveryTo: string;
  maxRuns: string;
  expiresOn: string;
}): ScheduledTaskPayload {
  const { schedule } = input;
  const payload: ScheduledTaskPayload = {
    agentId: input.agentId,
    name: input.name.trim(),
    prompt: input.prompt.trim(),
    frequency: schedule.kind,
    timezone: schedule.timezone.trim(),
    delivery: input.delivery,
  };
  if (schedule.kind === "custom") {
    payload.cron = schedule.cron.trim();
  } else {
    payload.time = normalizeTime(schedule.time) ?? schedule.time.trim();
    if (schedule.kind === "weekly" || schedule.kind === "monthly") payload.day = schedule.day.toUpperCase();
  }
  if (deliveryNeedsTarget(input.delivery)) {
    payload.deliveryTo = normalizeDeliveryTarget(input.delivery, input.deliveryTo) ?? input.deliveryTo.trim();
  }
  const runs = Number.parseInt(input.maxRuns, 10);
  if (Number.isFinite(runs) && runs > 0) payload.maxRuns = runs;
  if (input.expiresOn) payload.expiresAt = `${input.expiresOn}T23:59:59Z`;
  return payload;
}

/** The schedule part of the form for an existing task (the edit form round-trips what the server stored). */
export function draftFromTask(task: ScheduledTask): ScheduleDraft {
  return {
    kind: task.frequency,
    time: task.time ?? "08:00",
    day: task.day ?? (task.frequency === "monthly" ? "1" : "MON"),
    cron: task.frequency === "custom" ? cronToFiveFields(task.cron) : "",
    timezone: task.timezone,
  };
}

/** The server stores the 6-field Spring form; the editor shows the familiar 5-field one. */
export function cronToFiveFields(cron: string): string {
  const fields = cron.trim().split(/\s+/);
  return fields.length === 6 && fields[0] === "0" ? fields.slice(1).join(" ") : cron.trim();
}
