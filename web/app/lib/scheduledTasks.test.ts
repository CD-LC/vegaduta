import { describe, expect, it } from "vitest";
import {
  buildPayload,
  cronMinimumGapMinutes,
  cronToFiveFields,
  defaultTimezone,
  deliveryNeedsTarget,
  describeSchedule,
  draftFromTask,
  formatCost,
  formatRunTime,
  isValidTimeZone,
  nextRuns,
  normalizeDeliveryTarget,
  normalizeTime,
  offsetMinutes,
  statusTone,
  validateSchedule,
  zonedTimeToInstant,
  type ScheduleDraft,
  type ScheduledTask,
} from "./scheduledTasks";

function draft(over: Partial<ScheduleDraft> = {}): ScheduleDraft {
  return { kind: "daily", time: "08:00", day: "MON", cron: "", timezone: "UTC", ...over };
}

function runs(d: ScheduleDraft, from: string, count = 3): string[] {
  return nextRuns(d, new Date(from), count).map((x) => x.toISOString());
}

describe("normalizeTime", () => {
  it("pads and validates 24-hour times", () => {
    expect(normalizeTime("8:05")).toBe("08:05");
    expect(normalizeTime("08:05")).toBe("08:05");
    expect(normalizeTime(" 9:30 ")).toBe("09:30");
    expect(normalizeTime("23:59")).toBe("23:59");
    expect(normalizeTime("24:00")).toBeNull();
    expect(normalizeTime("8:5")).toBeNull();
    expect(normalizeTime("")).toBeNull();
    expect(normalizeTime("noon")).toBeNull();
  });
});

describe("time zones", () => {
  it("accepts IANA zones and rejects nonsense", () => {
    expect(isValidTimeZone("Asia/Kolkata")).toBe(true);
    expect(isValidTimeZone("UTC")).toBe(true);
    expect(isValidTimeZone("Mars/Olympus")).toBe(false);
    expect(isValidTimeZone("")).toBe(false);
  });

  it("always has a usable default zone", () => {
    expect(isValidTimeZone(defaultTimezone())).toBe(true);
  });

  it("reads the UTC offset in force at an instant, across a clock change", () => {
    expect(offsetMinutes(Date.parse("2026-10-04T00:00:00Z"), "Asia/Kolkata")).toBe(330);
    expect(offsetMinutes(Date.parse("2026-03-07T12:00:00Z"), "America/New_York")).toBe(-300);
    expect(offsetMinutes(Date.parse("2026-03-08T12:00:00Z"), "America/New_York")).toBe(-240);
    expect(offsetMinutes(Date.parse("2026-11-01T12:00:00Z"), "America/New_York")).toBe(-300);
  });

  it("maps a wall-clock reading back to an instant, handling the gap and the repeat", () => {
    const iso = (ms: number) => new Date(ms).toISOString();
    // An ordinary time.
    expect(iso(zonedTimeToInstant(2026, 3, 8, 8, 0, "America/New_York"))).toBe("2026-03-08T12:00:00.000Z");
    // 02:30 does not exist on 2026-03-08 in New York: shifted forward to 03:30 EDT.
    expect(iso(zonedTimeToInstant(2026, 3, 8, 2, 30, "America/New_York"))).toBe("2026-03-08T07:30:00.000Z");
    // 01:30 happens twice on 2026-11-01: the first occurrence wins.
    expect(iso(zonedTimeToInstant(2026, 11, 1, 1, 30, "America/New_York"))).toBe("2026-11-01T05:30:00.000Z");
  });
});

describe("validateSchedule", () => {
  it("accepts the friendly kinds with a good time", () => {
    expect(validateSchedule(draft(), 15)).toBeNull();
    expect(validateSchedule(draft({ kind: "weekdays" }), 15)).toBeNull();
    expect(validateSchedule(draft({ kind: "weekly", day: "FRI" }), 15)).toBeNull();
    expect(validateSchedule(draft({ kind: "monthly", day: "15" }), 15)).toBeNull();
    expect(validateSchedule(draft({ kind: "monthly", day: "last" }), 15)).toBeNull();
    expect(validateSchedule(draft({ kind: "monthly", day: "28" }), 15)).toBeNull();
  });

  it("names the first problem", () => {
    expect(validateSchedule(draft({ time: "" }), 15)).toBe("timeRequired");
    expect(validateSchedule(draft({ time: "25:00" }), 15)).toBe("timeInvalid");
    expect(validateSchedule(draft({ kind: "weekly", day: "" }), 15)).toBe("dayRequired");
    expect(validateSchedule(draft({ kind: "weekly", day: "FUNDAY" }), 15)).toBe("dayInvalid");
    expect(validateSchedule(draft({ kind: "monthly", day: "31" }), 15)).toBe("dayInvalid");
    expect(validateSchedule(draft({ kind: "monthly", day: "29" }), 15)).toBe("dayInvalid");
    expect(validateSchedule(draft({ timezone: "Mars/Olympus" }), 15)).toBe("timezoneInvalid");
  });

  it("checks a custom cron's shape and its obvious minimum interval", () => {
    const custom = (cron: string) => validateSchedule(draft({ kind: "custom", cron }), 15);
    expect(custom("")).toBe("cronRequired");
    expect(custom("* * *")).toBe("cronFields");
    expect(custom("30 0 8 * * *")).toBe("cronSeconds");
    expect(custom("0 8 * * $")).toBe("cronChars");
    expect(custom("*/5 * * * *")).toBe("cronTooFrequent");
    expect(custom("* * * * *")).toBe("cronTooFrequent");
    expect(custom("0,10 * * * *")).toBe("cronTooFrequent");
    expect(custom("*/15 * * * *")).toBeNull();
    expect(custom("0 8 * * 1-5")).toBeNull();
    expect(custom("0 0 8 * * MON-FRI")).toBeNull();
  });

  it("honours a different minimum interval", () => {
    expect(validateSchedule(draft({ kind: "custom", cron: "*/5 * * * *" }), 5)).toBeNull();
    expect(validateSchedule(draft({ kind: "custom", cron: "*/20 * * * *" }), 30)).toBe("cronTooFrequent");
  });
});

describe("cronMinimumGapMinutes", () => {
  it("reads the shapes it can and admits when it cannot", () => {
    expect(cronMinimumGapMinutes("* * * * *")).toBe(1);
    expect(cronMinimumGapMinutes("*/5 * * * *")).toBe(5);
    expect(cronMinimumGapMinutes("0-30/10 * * * *")).toBe(10);
    expect(cronMinimumGapMinutes("0,10 * * * *")).toBe(10);
    expect(cronMinimumGapMinutes("0 8 * * *")).toBeNull();
    expect(cronMinimumGapMinutes("0 */5 * * * *")).toBe(5);
    expect(cronMinimumGapMinutes("nonsense")).toBeNull();
  });
});

describe("normalizeDeliveryTarget", () => {
  it("accepts each channel's own address form and normalizes it", () => {
    expect(normalizeDeliveryTarget("in_app", "whatever")).toBe("");
    expect(normalizeDeliveryTarget("email", " me@example.com ")).toBe("me@example.com");
    expect(normalizeDeliveryTarget("whatsapp", "+91 98765-43210")).toBe("+919876543210");
    expect(normalizeDeliveryTarget("telegram", "-1001234567")).toBe("-1001234567");
    expect(normalizeDeliveryTarget("slack", "C0123456789")).toBe("C0123456789");
  });

  it("rejects malformed addresses", () => {
    expect(normalizeDeliveryTarget("email", "not-an-email")).toBeNull();
    expect(normalizeDeliveryTarget("email", "a@b.c,d@e.f")).toBeNull();
    expect(normalizeDeliveryTarget("whatsapp", "12")).toBeNull();
    expect(normalizeDeliveryTarget("telegram", "bob")).toBeNull();
    expect(normalizeDeliveryTarget("slack", "#general")).toBeNull();
  });

  it("knows which channels need a recipient", () => {
    expect(deliveryNeedsTarget("in_app")).toBe(false);
    expect(deliveryNeedsTarget("email")).toBe(true);
    expect(deliveryNeedsTarget("whatsapp")).toBe(true);
  });
});

describe("nextRuns", () => {
  it("fires at the local time in the task's zone", () => {
    // 08:00 IST (UTC+5:30, no DST) is 02:30 UTC.
    expect(runs(draft({ timezone: "Asia/Kolkata" }), "2026-10-04T00:00:00Z")).toEqual([
      "2026-10-04T02:30:00.000Z",
      "2026-10-05T02:30:00.000Z",
      "2026-10-06T02:30:00.000Z",
    ]);
  });

  it("is strictly after the reference instant", () => {
    expect(runs(draft(), "2026-10-04T08:00:00Z", 1)).toEqual(["2026-10-05T08:00:00.000Z"]);
    expect(runs(draft(), "2026-10-04T07:59:59Z", 1)).toEqual(["2026-10-04T08:00:00.000Z"]);
  });

  it("lets the zone decide which 08:00 is next", () => {
    const from = "2026-10-04T05:00:00Z";
    expect(runs(draft({ timezone: "Asia/Kolkata" }), from, 1)).toEqual(["2026-10-05T02:30:00.000Z"]);
    expect(runs(draft({ timezone: "America/New_York" }), from, 1)).toEqual(["2026-10-04T12:00:00.000Z"]);
  });

  it("skips the weekend for weekdays", () => {
    // 2026-10-02 is a Friday.
    expect(runs(draft({ kind: "weekdays" }), "2026-10-02T12:00:00Z")).toEqual([
      "2026-10-05T08:00:00.000Z",
      "2026-10-06T08:00:00.000Z",
      "2026-10-07T08:00:00.000Z",
    ]);
  });

  it("fires weekly on the chosen day only", () => {
    expect(runs(draft({ kind: "weekly", day: "MON", time: "9:30" }), "2026-10-04T00:00:00Z", 2)).toEqual([
      "2026-10-05T09:30:00.000Z",
      "2026-10-12T09:30:00.000Z",
    ]);
  });

  it("handles month ends: the last day of short months and a leap February", () => {
    expect(runs(draft({ kind: "monthly", day: "LAST" }), "2026-01-31T09:00:00Z")).toEqual([
      "2026-02-28T08:00:00.000Z",
      "2026-03-31T08:00:00.000Z",
      "2026-04-30T08:00:00.000Z",
    ]);
    expect(runs(draft({ kind: "monthly", day: "last" }), "2028-02-01T00:00:00Z", 1)).toEqual(["2028-02-29T08:00:00.000Z"]);
  });

  it("fires monthly on a fixed day", () => {
    expect(runs(draft({ kind: "monthly", day: "15", time: "09:00" }), "2026-12-20T00:00:00Z", 2)).toEqual([
      "2027-01-15T09:00:00.000Z",
      "2027-02-15T09:00:00.000Z",
    ]);
  });

  it("moves the UTC instant, not the local time, across the spring clock change", () => {
    const out = runs(draft({ timezone: "America/New_York" }), "2026-03-07T12:00:00Z");
    expect(out).toEqual(["2026-03-07T13:00:00.000Z", "2026-03-08T12:00:00.000Z", "2026-03-09T12:00:00.000Z"]);
    expect(Date.parse(out[1]) - Date.parse(out[0])).toBe(23 * 3_600_000);
  });

  it("moves the UTC instant the other way across the autumn clock change", () => {
    const out = runs(draft({ timezone: "America/New_York" }), "2026-10-31T13:00:00Z", 2);
    expect(out).toEqual(["2026-11-01T13:00:00.000Z", "2026-11-02T13:00:00.000Z"]);
  });

  it("shifts a time inside the spring gap forward to the next real instant and keeps going in order", () => {
    expect(runs(draft({ timezone: "America/New_York", time: "02:30" }), "2026-03-07T12:00:00Z")).toEqual([
      "2026-03-08T07:30:00.000Z",
      "2026-03-09T06:30:00.000Z",
      "2026-03-10T06:30:00.000Z",
    ]);
  });

  it("fires once at the first occurrence of a time that repeats in autumn", () => {
    expect(runs(draft({ timezone: "America/New_York", time: "01:30" }), "2026-10-31T12:00:00Z", 2)).toEqual([
      "2026-11-01T05:30:00.000Z",
      "2026-11-02T06:30:00.000Z",
    ]);
  });

  it("leaves a custom or invalid schedule to the server", () => {
    expect(runs(draft({ kind: "custom", cron: "0 8 * * 1-5" }), "2026-10-04T00:00:00Z")).toEqual([]);
    expect(runs(draft({ time: "99:99" }), "2026-10-04T00:00:00Z")).toEqual([]);
    expect(runs(draft({ kind: "monthly", day: "31" }), "2026-10-04T00:00:00Z")).toEqual([]);
    expect(runs(draft({ timezone: "Mars/Olympus" }), "2026-10-04T00:00:00Z")).toEqual([]);
  });

  it("returns as many runs as asked for, in increasing order", () => {
    const out = nextRuns(draft({ kind: "weekdays" }), new Date("2026-10-04T00:00:00Z"), 12);
    expect(out).toHaveLength(12);
    for (let i = 1; i < out.length; i++) expect(out[i].getTime()).toBeGreaterThan(out[i - 1].getTime());
  });
});

describe("describeSchedule", () => {
  it("returns a key and the values the sentence needs", () => {
    expect(describeSchedule(draft({ time: "8:00", timezone: "Asia/Kolkata" }))).toEqual({
      key: "describeDaily",
      params: { time: "08:00", timezone: "Asia/Kolkata" },
    });
    expect(describeSchedule(draft({ kind: "weekdays" })).key).toBe("describeWeekdays");
    expect(describeSchedule(draft({ kind: "weekly", day: "fri" })).params.day).toBe("FRI");
    expect(describeSchedule(draft({ kind: "monthly", day: "15" }))).toMatchObject({ key: "describeMonthly", params: { day: "15" } });
    expect(describeSchedule(draft({ kind: "monthly", day: "last" })).key).toBe("describeMonthlyLast");
    expect(describeSchedule(draft({ kind: "custom", cron: " 0 8 * * 1 " }))).toEqual({
      key: "describeCustom",
      params: { cron: "0 8 * * 1", timezone: "UTC" },
    });
  });
});

describe("display helpers", () => {
  it("formats an instant in the task's zone", () => {
    const text = formatRunTime("2026-10-05T02:30:00Z", "Asia/Kolkata", "en-US");
    expect(text).toContain("08:00");
    expect(text).toContain("2026");
    expect(formatRunTime("garbage", "UTC")).toBe("garbage");
    expect(formatRunTime("2026-10-05T02:30:00Z", "Mars/Olympus")).toBe("2026-10-05T02:30:00.000Z");
  });

  it("maps statuses to tones", () => {
    expect(statusTone("SUCCEEDED")).toBe("success");
    expect(statusTone("FAILED")).toBe("error");
    expect(statusTone("SKIPPED")).toBe("warning");
    expect(statusTone("CANCELLED")).toBe("warning");
    expect(statusTone("RUNNING")).toBe("running");
    expect(statusTone("ENQUEUED")).toBe("running");
    expect(statusTone(null)).toBe("idle");
  });

  it("formats a cost, with more digits for a fraction of a cent", () => {
    expect(formatCost(0.1234)).toBe("$0.12");
    expect(formatCost(0.004)).toBe("$0.0040");
    expect(formatCost(0)).toBe("$0.00");
    expect(formatCost(null)).toBe("");
    expect(formatCost(Number.NaN)).toBe("");
  });
});

describe("buildPayload", () => {
  const base = {
    agentId: "a1",
    name: "  Morning digest ",
    prompt: " Summarise my email ",
    delivery: "in_app" as const,
    deliveryTo: "",
    maxRuns: "",
    expiresOn: "",
  };

  it("builds a friendly schedule with trimmed text", () => {
    expect(buildPayload({ ...base, schedule: draft({ kind: "weekdays", time: "8:00", timezone: " Asia/Kolkata " }) })).toEqual({
      agentId: "a1",
      name: "Morning digest",
      prompt: "Summarise my email",
      frequency: "weekdays",
      time: "08:00",
      timezone: "Asia/Kolkata",
      delivery: "in_app",
    });
  });

  it("adds the day for weekly and monthly, upper-cased", () => {
    expect(buildPayload({ ...base, schedule: draft({ kind: "weekly", day: "fri" }) }).day).toBe("FRI");
    expect(buildPayload({ ...base, schedule: draft({ kind: "monthly", day: "last" }) }).day).toBe("LAST");
    expect(buildPayload({ ...base, schedule: draft() }).day).toBeUndefined();
  });

  it("sends the cron and no time for a custom schedule", () => {
    const p = buildPayload({ ...base, schedule: draft({ kind: "custom", cron: " 0 8 * * 1-5 " }) });
    expect(p.cron).toBe("0 8 * * 1-5");
    expect(p.time).toBeUndefined();
  });

  it("normalizes the recipient and carries the optional caps", () => {
    const p = buildPayload({
      ...base,
      schedule: draft(),
      delivery: "whatsapp",
      deliveryTo: "+91 98765-43210",
      maxRuns: "5",
      expiresOn: "2026-12-31",
    });
    expect(p.deliveryTo).toBe("+919876543210");
    expect(p.maxRuns).toBe(5);
    expect(p.expiresAt).toBe("2026-12-31T23:59:59Z");
    expect(buildPayload({ ...base, schedule: draft(), maxRuns: "0" }).maxRuns).toBeUndefined();
    expect(buildPayload({ ...base, schedule: draft(), maxRuns: "abc" }).maxRuns).toBeUndefined();
    expect(buildPayload({ ...base, schedule: draft() }).deliveryTo).toBeUndefined();
  });
});

describe("editing an existing task", () => {
  const task = (over: Partial<ScheduledTask>): ScheduledTask => ({
    id: "t1",
    agentId: "a1",
    name: "n",
    prompt: "p",
    frequency: "weekdays",
    time: "08:00",
    day: null,
    cron: "0 0 8 * * MON-FRI",
    timezone: "UTC",
    scheduleDescription: "",
    delivery: "in_app",
    deliveryTo: null,
    enabled: true,
    disabledReason: null,
    nextRunAt: null,
    upcomingRuns: [],
    lastRunAt: null,
    lastStatus: null,
    lastJobId: null,
    consecutiveFailures: 0,
    runCount: 0,
    maxRuns: null,
    expiresAt: null,
    ownerSubject: "u",
    createdAt: "",
    updatedAt: "",
    ...over,
  });

  it("round-trips the friendly parts the server stored", () => {
    expect(draftFromTask(task({}))).toEqual({ kind: "weekdays", time: "08:00", day: "MON", cron: "", timezone: "UTC" });
    expect(draftFromTask(task({ frequency: "monthly", day: "LAST" })).day).toBe("LAST");
    expect(draftFromTask(task({ frequency: "monthly", day: null })).day).toBe("1");
  });

  it("shows a custom cron in its familiar five-field form", () => {
    expect(cronToFiveFields("0 30 6 * * 1-5")).toBe("30 6 * * 1-5");
    expect(cronToFiveFields("30 6 * * 1-5")).toBe("30 6 * * 1-5");
    expect(draftFromTask(task({ frequency: "custom", cron: "0 */15 * * * *", time: null })).cron).toBe("*/15 * * * *");
  });
});
