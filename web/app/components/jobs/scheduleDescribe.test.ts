import { describe, expect, it } from "vitest";
import {
  describeCron,
  describeInterval,
  intervalToSeconds,
  secondsToAmountUnit,
  validateInterval,
} from "./scheduleDescribe";

// Echoes key + values so the test asserts on which message was chosen.
const t = (key: string, values?: Record<string, string | number>) =>
  values ? `${key}:${Object.values(values).join("|")}` : key;

describe("interval helpers", () => {
  it("converts units to seconds", () => {
    expect(intervalToSeconds(15, "minutes")).toBe(900);
    expect(intervalToSeconds(2, "hours")).toBe(7200);
    expect(intervalToSeconds(1, "days")).toBe(86400);
  });

  it("round-trips stored seconds into the largest exact unit", () => {
    expect(secondsToAmountUnit(86400)).toEqual({ amount: 1, unit: "days" });
    expect(secondsToAmountUnit(7200)).toEqual({ amount: 2, unit: "hours" });
    expect(secondsToAmountUnit(900)).toEqual({ amount: 15, unit: "minutes" });
  });

  it("enforces the 60 second minimum and the one year maximum", () => {
    expect(validateInterval(1, "minutes")).toBe(true);
    expect(validateInterval(0, "minutes")).toBe(false);
    expect(validateInterval(-3, "hours")).toBe(false);
    expect(validateInterval(Number.NaN, "minutes")).toBe(false);
    expect(validateInterval(366, "days")).toBe(false);
  });

  it("describes an interval", () => {
    expect(describeInterval(1800, t)).toBe("everyMinutes:30");
    expect(describeInterval(3 * 3600, t)).toBe("everyHours:3");
  });
});

describe("describeCron", () => {
  it("recognises the common shapes", () => {
    expect(describeCron("*/10 * * * *", t)).toBe("everyMinutes:10");
    expect(describeCron("0 * * * *", t)).toBe("everyHour");
    expect(describeCron("30 9 * * *", t)).toBe("dailyAt:09:30");
    expect(describeCron("0 9 * * MON-FRI", t)).toBe("weekdaysAt:09:00");
    expect(describeCron("0 0 9 * * 1-5", t)).toBe("weekdaysAt:09:00");
    expect(describeCron("0 18 * * MON,WED", t)).toBe("onDaysAt:Mon, Wed|18:00");
  });

  it("returns null for anything it cannot describe faithfully", () => {
    expect(describeCron("0 9 1 * *", t)).toBeNull();
    expect(describeCron("nonsense", t)).toBeNull();
    expect(describeCron("0 9 * * FOO", t)).toBeNull();
  });
});
